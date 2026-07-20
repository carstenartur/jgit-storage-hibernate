/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search.analysis;

import java.util.Objects;
import java.util.Properties;
import org.hibernate.search.engine.backend.analysis.AnalyzerNames;

/** Stable configuration contract for natural-language analysis in the Git commit index. */
public final class GitTextAnalysis {

  /** Default Hibernate Search index name of the generic commit projection. */
  public static final String INDEX_NAME = "GitCommitIndex";

  /** Configurable analyzer slot used only for natural-language commit-message fields. */
  public static final String NATURAL_LANGUAGE_ANALYZER = AnalyzerNames.DEFAULT;

  /** Fixed language-neutral analyzer used for changed paths and changed-file text. */
  public static final String STRUCTURED_TEXT_ANALYZER = AnalyzerNames.STANDARD;

  /** Hibernate Search property for an analysis configurer scoped to the commit index. */
  public static final String INDEX_CONFIGURER_PROPERTY =
      "hibernate.search.backend.indexes." + INDEX_NAME + ".analysis.configurer";

  /** Application-visible property identifying the configured analysis profile. */
  public static final String PROFILE_ID_PROPERTY =
      "jgit.storage.hibernate.search.text-analysis.profile";

  /** Profile identity when no custom configurer is supplied. */
  public static final String DEFAULT_PROFILE_ID = "neutral-standard-v1";

  private GitTextAnalysis() {}

  /**
   * Configure a custom natural-language profile for the generic commit index.
   *
   * <p>The configurer may be a {@code LuceneAnalysisConfigurer} instance, a Hibernate Search bean
   * reference, a class reference string or any other value accepted by Hibernate Search for the
   * analysis-configurer property. It must override the analyzer named {@link
   * #NATURAL_LANGUAGE_ANALYZER}. Path, identifier and changed-file fields remain mapped to their
   * explicit language-neutral analyzers.
   *
   * @param properties Hibernate configuration properties
   * @param configurer Hibernate Search analysis configurer reference
   * @param profileId stable operator-visible profile identity
   */
  public static void configure(Properties properties, Object configurer, String profileId) {
    Objects.requireNonNull(properties, "properties");
    properties.put(INDEX_CONFIGURER_PROPERTY, Objects.requireNonNull(configurer, "configurer"));
    properties.setProperty(PROFILE_ID_PROPERTY, requireNotBlank(profileId, "profileId"));
  }

  /**
   * Return the configured profile identity for diagnostics.
   *
   * @param properties Hibernate configuration properties
   * @return configured identity or {@link #DEFAULT_PROFILE_ID}
   */
  public static String profileId(Properties properties) {
    Objects.requireNonNull(properties, "properties");
    String configured = properties.getProperty(PROFILE_ID_PROPERTY);
    return configured == null || configured.isBlank()
        ? DEFAULT_PROFILE_ID
        : configured.trim();
  }

  private static String requireNotBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    String normalized = value.trim();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return normalized;
  }
}
