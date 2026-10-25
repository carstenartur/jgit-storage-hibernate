/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.QueryBuilder;

/**
 * Immutable, closeable search generation over caller-supplied historical files.
 *
 * <p>Construction indexes each (repository, documentId) once, retaining the first input's complete
 * provenance. The full index occupies memory proportional to the supplied corpus; no corpus cap or
 * implicit history truncation is applied. Callers replace complete generations and close old ones.
 * Searches may run concurrently; close waits for active searches and is idempotent.
 *
 * <p>Queries filter before ranking, sort by score then documentId, and use bounded search-after
 * traversal. Deep offsets take time proportional to skipped hits, without allocating offset-sized
 * arrays. Returned lists contain only one page and do not claim an exact total hit count.
 */
public final class JavaFileSearchIndex implements AutoCloseable {
  public static final String ANALYSIS_PROFILE = "java-file-facets-v1";
  private static final String REPOSITORY = "repository";
  private static final String ID = "identity";
  private static final String MODULE = "module";
  private static final String FILE_TYPE = "fileType";
  private static final Sort ORDER = new Sort(SortField.FIELD_SCORE, new SortField(ID, SortField.Type.STRING));
  private static final int SKIP_PAGE = 128;

  private final Directory directory;
  private final DirectoryReader reader;
  private final JavaFileSearchAnalyzer analyzer;
  private final IndexSearcher searcher;
  private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
  private boolean closed;

  private JavaFileSearchIndex(Directory directory, DirectoryReader reader, JavaFileSearchAnalyzer analyzer) {
    this.directory = directory;
    this.reader = reader;
    this.analyzer = analyzer;
    searcher = new IndexSearcher(reader);
  }

  /** Build one complete generation; input iteration or indexing failures close all acquired resources. */
  public static JavaFileSearchIndex build(Iterable<JavaFileSearchDocument> documents) throws IOException {
    Objects.requireNonNull(documents, "documents");
    Directory directory = new ByteBuffersDirectory();
    JavaFileSearchAnalyzer analyzer = new JavaFileSearchAnalyzer();
    try {
      try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
        var identities = new HashSet<Identity>();
        for (JavaFileSearchDocument source : documents) {
          Objects.requireNonNull(source, "document");
          if (identities.add(new Identity(source.repositoryName(), source.documentId()))) {
            writer.addDocument(toDocument(source));
          }
        }
      }
      return new JavaFileSearchIndex(directory, DirectoryReader.open(directory), analyzer);
    } catch (IOException | RuntimeException | Error failure) {
      try {
        analyzer.close();
      } catch (RuntimeException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      try {
        directory.close();
      } catch (IOException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }

  /** Return the requested stable page; blank or tokenless literal text returns an empty list. */
  public List<JavaFileSearchHit> search(JavaFileSearchQuery request) throws IOException {
    Objects.requireNonNull(request, "request");
    lifecycle.readLock().lock();
    try {
      if (closed) {
        throw new IllegalStateException("Java file search index is closed");
      }
      if (request.literalText().isBlank()) {
        return List.of();
      }
      Query query = toQuery(request);
      if (query == null) {
        return List.of();
      }
      ScoreDoc after = null;
      int remaining = request.offset();
      while (remaining > 0) {
        int size = Math.min(SKIP_PAGE, remaining);
        var skipped = searcher.searchAfter(after, query, size, ORDER, true).scoreDocs;
        if (skipped.length < size) {
          return List.of();
        }
        after = skipped[skipped.length - 1];
        remaining -= skipped.length;
      }
      var matches = searcher.searchAfter(after, query, request.limit(), ORDER, true).scoreDocs;
      var stored = reader.storedFields();
      var hits = new ArrayList<JavaFileSearchHit>(matches.length);
      for (ScoreDoc match : matches) {
        Document document = stored.document(match.doc);
        hits.add(new JavaFileSearchHit(document.get(REPOSITORY), document.get(ID), document.get("commit"),
            document.get("blob"), document.get("path"), document.get(MODULE), document.get(FILE_TYPE), match.score));
      }
      return List.copyOf(hits);
    } finally {
      lifecycle.readLock().unlock();
    }
  }

  private Query toQuery(JavaFileSearchQuery request) {
    QueryBuilder builder = new QueryBuilder(analyzer);
    BooleanQuery.Builder text = new BooleanQuery.Builder();
    for (JavaFileSearchField field : JavaFileSearchField.values()) {
      Float boost = request.boosts().get(field);
      if (boost == null) {
        continue;
      }
      Query analyzed = builder.createBooleanQuery(field.name(), request.literalText());
      if (analyzed != null) {
        text.add(new BoostQuery(analyzed, boost), BooleanClause.Occur.SHOULD);
      }
    }
    BooleanQuery terms = text.build();
    if (terms.clauses().isEmpty()) {
      return null;
    }
    var result = new BooleanQuery.Builder().add(terms, BooleanClause.Occur.MUST);
    result.add(new TermQuery(new Term(REPOSITORY, request.repositoryName())), BooleanClause.Occur.FILTER);
    if (request.module() != null) {
      result.add(new TermQuery(new Term(MODULE, request.module())), BooleanClause.Occur.FILTER);
    }
    if (request.fileType() != null) {
      result.add(new TermQuery(new Term(FILE_TYPE, request.fileType())), BooleanClause.Occur.FILTER);
    }
    return result.build();
  }

  private static Document toDocument(JavaFileSearchDocument source) {
    Document document = new Document();
    document.add(new StringField(REPOSITORY, source.repositoryName(), Field.Store.YES));
    document.add(new StringField(ID, source.documentId(), Field.Store.YES));
    document.add(new SortedDocValuesField(ID, new BytesRef(source.documentId())));
    document.add(new StoredField("commit", source.commitId()));
    document.add(new StoredField("blob", source.blobId()));
    document.add(new StoredField("path", source.path()));
    document.add(new StringField(MODULE, source.module(), Field.Store.YES));
    document.add(new StringField(FILE_TYPE, source.fileType(), Field.Store.YES));
    source.fields().forEach((field, value) -> document.add(new TextField(field.name(), value, Field.Store.NO)));
    return document;
  }

  @Override
  public void close() throws IOException {
    lifecycle.writeLock().lock();
    try {
      if (closed) {
        return;
      }
      closed = true;
      try {
        reader.close();
      } finally {
        try {
          analyzer.close();
        } finally {
          directory.close();
        }
      }
    } finally {
      lifecycle.writeLock().unlock();
    }
  }

  private record Identity(String repositoryName, String documentId) {}
}
