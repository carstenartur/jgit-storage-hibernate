/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.reproducer;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.management.ObjectName;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.search.engine.backend.types.Projectable;
import org.hibernate.search.engine.backend.types.Sortable;
import org.hibernate.search.engine.search.query.SearchScroll;
import org.hibernate.search.mapper.orm.Search;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.FullTextField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.GenericField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.Indexed;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.KeywordField;

/** Public-API reproducer; no application code, entity loading, or JDBC connection is involved. */
public final class RankedScrollMemoryProbe {
  private static final Instant BASE = Instant.parse("2026-11-08T00:00:00.123456789Z");
  private static final Pattern FIELD_DOC =
      Pattern.compile("(?m)^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+org\\.apache\\.lucene\\.search\\.FieldDoc(?:\\s+\\(.*\\))?\\s*$");
  private static final Pattern SCORE_DOC_ARRAY =
      Pattern.compile("(?m)^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+\\[Lorg\\.apache\\.lucene\\.search\\.ScoreDoc;(?:\\s+\\(.*\\))?\\s*$");

  private RankedScrollMemoryProbe() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 3 || args.length > 4) {
      throw new IllegalArgumentException("Usage: documents chunk-size output-directory [retained-hit-budget]");
    }
    int documents = Integer.parseInt(args[0]);
    int chunkSize = Integer.parseInt(args[1]);
    Path output = Path.of(args[2]).toAbsolutePath();
    long budget = args.length == 4 ? Long.parseLong(args[3]) : Long.MAX_VALUE;
    if (documents < 1 || chunkSize < 1 || chunkSize > documents || budget < 1) {
      throw new IllegalArgumentException("Require documents >= chunk-size >= 1 and a positive budget");
    }
    Files.createDirectories(output);
    List<Snapshot> snapshots = new ArrayList<>();
    String lifecycle;
    try (SessionFactory factory = factory()) {
      seed(factory, documents);
      snapshots.add(snapshot(output, "before_open", 0));
      // The method boundary releases the raw SearchScroll and its final result before after_close.
      consume(factory, documents, chunkSize, output, snapshots);
      snapshots.add(snapshot(output, "after_close", documents));
      var statistics = factory.getStatistics();
      if (statistics.getEntityLoadCount() != 0 || statistics.getPrepareStatementCount() != 0) {
        throw new AssertionError("Compact field projections must not hydrate entities or issue SQL");
      }
      if (statistics.getSessionOpenCount() != statistics.getSessionCloseCount()) {
        throw new AssertionError("The reproducer left an ORM session open");
      }
      lifecycle = "entity-loads=" + statistics.getEntityLoadCount() + "\n"
          + "prepared-statements=" + statistics.getPrepareStatementCount() + "\n"
          + "sessions-opened=" + statistics.getSessionOpenCount() + "\n"
          + "sessions-closed=" + statistics.getSessionCloseCount() + "\n";
    }

    List<String> rows = new ArrayList<>();
    rows.add("phase,consumed_rows,live_field_docs,field_doc_shallow_bytes,score_doc_array_shallow_bytes");
    snapshots.forEach(snapshot -> rows.add(snapshot.csv()));
    Files.write(output.resolve("retained-hits.csv"), rows);
    Files.writeString(
        output.resolve("environment.txt"),
        "captured-at=" + Instant.now() + "\n"
            + "java=" + Runtime.version() + "\n"
            + "hibernate=" + org.hibernate.Version.getVersionString() + "\n"
            + "hibernate-search=" + org.hibernate.search.engine.Version.versionString() + "\n"
            + "lucene=" + org.apache.lucene.util.Version.LATEST + "\n"
            + "documents=" + documents + "\nchunk-size=" + chunkSize + "\n"
            + "maximum-heap-bytes=" + Runtime.getRuntime().maxMemory() + "\n"
            + "measurement=HotSpot DiagnosticCommand.gcClassHistogram; -all omitted\n"
            + "retained-hit-budget=" + (budget == Long.MAX_VALUE ? "unset" : budget) + "\n"
            + lifecycle);
    rows.forEach(System.out::println);

    long baseline = snapshots.getFirst().fieldDocs();
    if (snapshots.get(1).fieldDocs() <= baseline) {
      throw new AssertionError("No live FieldDoc sample found; the probe cannot classify this implementation");
    }
    if (snapshots.getLast().fieldDocs() > baseline) {
      throw new AssertionError("Ranked hits remain reachable after releasing the closed scroll");
    }
    long maximum = snapshots.stream().mapToLong(Snapshot::fieldDocs).max().orElseThrow() - baseline;
    if (maximum > budget) {
      throw new AssertionError("Retained " + maximum + " ranked hits; configured budget is " + budget);
    }
  }

  private static SessionFactory factory() {
    return new Configuration()
        .addAnnotatedClass(Document.class)
        .setProperty("hibernate.boot.allow_jdbc_metadata_access", "false")
        .setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
        .setProperty("hibernate.hbm2ddl.auto", "none")
        .setProperty("hibernate.generate_statistics", "true")
        .setProperty("hibernate.session.events.log", "false")
        .setProperty("hibernate.search.backend.type", "lucene")
        .setProperty("hibernate.search.backend.directory.type", "local-heap")
        .setProperty("hibernate.search.indexing.plan.synchronization.strategy", "sync")
        .buildSessionFactory();
  }

  private static void seed(SessionFactory factory, int documents) {
    for (int first = 0; first < documents; first += 256) {
      try (var session = factory.openSession()) {
        var plan = Search.session(session).indexingPlan();
        for (int ordinal = first; ordinal < Math.min(first + 256, documents); ordinal++) {
          plan.addOrUpdate(new Document(ordinal));
        }
        plan.execute();
      }
    }
  }

  private static void consume(
      SessionFactory factory, int documents, int chunkSize, Path output, List<Snapshot> snapshots)
      throws Exception {
    try (var session = factory.openSession();
        SearchScroll<List<?>> scroll = Search.session(session)
            .search(Document.class)
            .select(f -> f.composite(
                f.field("objectId", String.class),
                f.field("committerTime", Instant.class),
                f.field("nullableAuthor", String.class)))
            .where(f -> f.match().field("body").matching("needle"))
            .sort(f -> f.composite().add(f.score())
                .add(f.field("committerTime").desc()).add(f.field("objectId").asc()))
            .scroll(chunkSize)) {
      var expected = new Expected(documents);
      int chunks = 0;
      while (consumeChunk(scroll, chunkSize, expected)) {
        chunks++;
        if ((chunks & (chunks - 1)) == 0 || expected.rows == documents) {
          snapshots.add(snapshot(output, "chunk_" + chunks, expected.rows));
        }
      }
      if (expected.rows != documents) {
        throw new AssertionError("Expected " + documents + " rows, got " + expected.rows);
      }
    }
  }

  private static boolean consumeChunk(SearchScroll<List<?>> scroll, int chunkSize, Expected expected) {
    var result = scroll.next();
    if (!result.hasHits()) {
      return false;
    }
    if (result.hits().size() > chunkSize) {
      throw new AssertionError("Chunk exceeded the requested size");
    }
    for (List<?> hit : result.hits()) {
      expected.verify(hit);
    }
    return true;
  }

  private static Snapshot snapshot(Path output, String phase, int rows) throws Exception {
    Path histogram = output.resolve(phase + ".histogram.txt");
    // This is the same HotSpot command as jcmd GC.class_histogram, through its public JMX entry.
    // Omitting -all excludes unreachable objects. No attach permission or backend introspection.
    String text = (String) ManagementFactory.getPlatformMBeanServer().invoke(
        new ObjectName("com.sun.management:type=DiagnosticCommand"), "gcClassHistogram",
        new Object[] {new String[0]}, new String[] {String[].class.getName()});
    Files.writeString(histogram, text);
    var fieldDocs = FIELD_DOC.matcher(text);
    var arrays = SCORE_DOC_ARRAY.matcher(text);
    long count = 0;
    long bytes = 0;
    if (fieldDocs.find()) {
      count = Long.parseLong(fieldDocs.group(1));
      bytes = Long.parseLong(fieldDocs.group(2));
    }
    return new Snapshot(phase, rows, count, bytes, arrays.find() ? Long.parseLong(arrays.group(2)) : 0);
  }

  private record Snapshot(String phase, int rows, long fieldDocs, long hitBytes, long arrayBytes) {
    String csv() {
      return phase + "," + rows + "," + fieldDocs + "," + hitBytes + "," + arrayBytes;
    }
  }

  private static final class Expected {
    private final int documents;
    private int group;
    private int ordinal;
    private int rows;

    Expected(int documents) {
      this.documents = documents;
      this.group = ((documents - 1) / 4) * 4;
      this.ordinal = group;
    }

    void verify(List<?> hit) {
      if (!String.format("%040d", ordinal).equals(hit.get(0))
          || !BASE.plusSeconds(ordinal / 4).equals(hit.get(1)) || hit.get(2) != null) {
        throw new AssertionError("Unexpected order or compact projection at row " + rows + ": " + hit);
      }
      rows++;
      ordinal++;
      if (ordinal == Math.min(group + 4, documents)) {
        group -= 4;
        ordinal = group;
      }
    }
  }

  @Entity(name = "RankedScrollDocument")
  @Indexed
  public static class Document {
    @Id private Integer id;
    @KeywordField(projectable = Projectable.YES, sortable = Sortable.YES)
    private String objectId;
    @GenericField(projectable = Projectable.YES, sortable = Sortable.YES)
    private Instant committerTime;
    @KeywordField(projectable = Projectable.YES)
    private String nullableAuthor;
    @FullTextField private String body;

    public Document() {}

    Document(int ordinal) {
      id = ordinal;
      objectId = String.format("%040d", ordinal);
      committerTime = BASE.plusSeconds(ordinal / 4);
      body = "needle";
    }
  }
}
