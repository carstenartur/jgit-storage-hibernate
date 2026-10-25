/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.KeywordTokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.core.StopFilter;
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilterFactory;
import org.apache.lucene.analysis.pattern.PatternReplaceFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;

/** Internal versioned analysis profile shared by indexing and literal query analysis. */
final class JavaFileSearchAnalyzer extends Analyzer {
  JavaFileSearchAnalyzer() {
    super(PER_FIELD_REUSE_STRATEGY);
  }

  @Override
  protected TokenStreamComponents createComponents(String fieldName) {
    var analysis = JavaFileSearchField.valueOf(fieldName).analysis;
    Tokenizer tokenizer;
    TokenStream stream;
    if (analysis == JavaFileSearchField.Analysis.QUALIFIED) {
      tokenizer = new KeywordTokenizer();
      stream = new PatternReplaceFilter(tokenizer, Pattern.compile("\\."), " ", true);
    } else {
      tokenizer = new StandardTokenizer();
      stream = new WordDelimiterGraphFilterFactory(new HashMap<>(Map.of(
          "splitOnCaseChange", "1", "generateWordParts", "1", "preserveOriginal", "1"))).create(tokenizer);
    }
    stream = new LowerCaseFilter(stream);
    if (analysis == JavaFileSearchField.Analysis.TEXT) {
      stream = new StopFilter(stream, EnglishAnalyzer.ENGLISH_STOP_WORDS_SET);
    }
    return new TokenStreamComponents(tokenizer, stream);
  }
}
