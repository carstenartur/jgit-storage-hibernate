/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search.profile;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.hibernate.SessionFactory;

/** Bounded, configurable changed-file content policy for Search projection indexing. */
public final class SearchContentPolicy {

  public static final String MAX_FILE_BYTES_PROPERTY =
      "jgit.storage.hibernate.search.content.max_file_bytes";
  public static final String MAX_COMMIT_CHARS_PROPERTY =
      "jgit.storage.hibernate.search.content.max_commit_chars";
  public static final String ALLOW_EXTENSIONS_PROPERTY =
      "jgit.storage.hibernate.search.content.allow_extensions";
  public static final String DENY_EXTENSIONS_PROPERTY =
      "jgit.storage.hibernate.search.content.deny_extensions";
  public static final String ALLOW_MIME_TYPES_PROPERTY =
      "jgit.storage.hibernate.search.content.allow_mime_types";
  public static final String DENY_MIME_TYPES_PROPERTY =
      "jgit.storage.hibernate.search.content.deny_mime_types";
  public static final String REJECT_BINARY_PROPERTY =
      "jgit.storage.hibernate.search.content.reject_binary";
  public static final String REJECT_INVALID_UTF8_PROPERTY =
      "jgit.storage.hibernate.search.content.reject_invalid_utf8";
  public static final String SKIP_GENERATED_PROPERTY =
      "jgit.storage.hibernate.search.content.skip_generated";
  public static final String SKIP_MINIFIED_PROPERTY =
      "jgit.storage.hibernate.search.content.skip_minified";

  /** Pre-profile compatibility limit. */
  public static final int DEFAULT_MAX_FILE_BYTES = 256 * 1024;

  /** Pre-profile compatibility limit. */
  public static final int DEFAULT_MAX_COMMIT_CHARS = 250_000;

  /** Hard safety ceiling even when operators raise the configured per-file bound. */
  public static final int ABSOLUTE_MAX_FILE_BYTES = 16 * 1024 * 1024;

  /** Hard safety ceiling even when operators raise the configured per-commit bound. */
  public static final int ABSOLUTE_MAX_COMMIT_CHARS = 4_000_000;

  private static final int BINARY_SAMPLE_BYTES = 8 * 1024;
  private static final int MINIFIED_LONGEST_LINE = 2_000;
  private static final int MINIFIED_AVERAGE_LINE = 500;
  private static final String UNKNOWN_MIME_TYPE = "application/octet-stream";
  private static final Map<String, String> MIME_BY_EXTENSION =
      Map.ofEntries(
          Map.entry(".c", "text/x-c"),
          Map.entry(".cc", "text/x-c++"),
          Map.entry(".cpp", "text/x-c++"),
          Map.entry(".css", "text/css"),
          Map.entry(".csv", "text/csv"),
          Map.entry(".go", "text/x-go"),
          Map.entry(".h", "text/x-c"),
          Map.entry(".hpp", "text/x-c++"),
          Map.entry(".html", "text/html"),
          Map.entry(".java", "text/x-java-source"),
          Map.entry(".js", "text/javascript"),
          Map.entry(".json", "application/json"),
          Map.entry(".kt", "text/x-kotlin"),
          Map.entry(".kts", "text/x-kotlin"),
          Map.entry(".md", "text/markdown"),
          Map.entry(".properties", "text/plain"),
          Map.entry(".py", "text/x-python"),
          Map.entry(".rs", "text/x-rust"),
          Map.entry(".sh", "application/x-sh"),
          Map.entry(".sql", "application/sql"),
          Map.entry(".ts", "text/typescript"),
          Map.entry(".txt", "text/plain"),
          Map.entry(".xml", "application/xml"),
          Map.entry(".yaml", "application/yaml"),
          Map.entry(".yml", "application/yaml"));

  private final int maxFileBytes;
  private final int maxCommitChars;
  private final Set<String> allowedExtensions;
  private final Set<String> deniedExtensions;
  private final Set<String> allowedMimeTypes;
  private final Set<String> deniedMimeTypes;
  private final boolean rejectBinary;
  private final boolean rejectInvalidUtf8;
  private final boolean skipGenerated;
  private final boolean skipMinified;

  private SearchContentPolicy(
      int maxFileBytes,
      int maxCommitChars,
      Set<String> allowedExtensions,
      Set<String> deniedExtensions,
      Set<String> allowedMimeTypes,
      Set<String> deniedMimeTypes,
      boolean rejectBinary,
      boolean rejectInvalidUtf8,
      boolean skipGenerated,
      boolean skipMinified) {
    this.maxFileBytes = maxFileBytes;
    this.maxCommitChars = maxCommitChars;
    this.allowedExtensions = Set.copyOf(allowedExtensions);
    this.deniedExtensions = Set.copyOf(deniedExtensions);
    this.allowedMimeTypes = Set.copyOf(allowedMimeTypes);
    this.deniedMimeTypes = Set.copyOf(deniedMimeTypes);
    this.rejectBinary = rejectBinary;
    this.rejectInvalidUtf8 = rejectInvalidUtf8;
    this.skipGenerated = skipGenerated;
    this.skipMinified = skipMinified;
  }

  /** Resolve policy from Hibernate properties. Defaults preserve the previous CONTENT behavior. */
  public static SearchContentPolicy resolve(SessionFactory sessionFactory) {
    Objects.requireNonNull(sessionFactory, "sessionFactory");
    var properties = sessionFactory.getProperties();
    return new SearchContentPolicy(
        boundedInteger(
            properties.get(MAX_FILE_BYTES_PROPERTY),
            DEFAULT_MAX_FILE_BYTES,
            ABSOLUTE_MAX_FILE_BYTES,
            MAX_FILE_BYTES_PROPERTY),
        boundedInteger(
            properties.get(MAX_COMMIT_CHARS_PROPERTY),
            DEFAULT_MAX_COMMIT_CHARS,
            ABSOLUTE_MAX_COMMIT_CHARS,
            MAX_COMMIT_CHARS_PROPERTY),
        extensions(properties.get(ALLOW_EXTENSIONS_PROPERTY)),
        extensions(properties.get(DENY_EXTENSIONS_PROPERTY)),
        mimeTypes(properties.get(ALLOW_MIME_TYPES_PROPERTY)),
        mimeTypes(properties.get(DENY_MIME_TYPES_PROPERTY)),
        bool(properties.get(REJECT_BINARY_PROPERTY), false, REJECT_BINARY_PROPERTY),
        bool(properties.get(REJECT_INVALID_UTF8_PROPERTY), false, REJECT_INVALID_UTF8_PROPERTY),
        bool(properties.get(SKIP_GENERATED_PROPERTY), false, SKIP_GENERATED_PROPERTY),
        bool(properties.get(SKIP_MINIFIED_PROPERTY), false, SKIP_MINIFIED_PROPERTY));
  }

  public int maxFileBytes() {
    return maxFileBytes;
  }

  public int maxCommitChars() {
    return maxCommitChars;
  }

  public Set<String> allowedExtensions() {
    return allowedExtensions;
  }

  public Set<String> deniedExtensions() {
    return deniedExtensions;
  }

  public Set<String> allowedMimeTypes() {
    return allowedMimeTypes;
  }

  public Set<String> deniedMimeTypes() {
    return deniedMimeTypes;
  }

  public boolean rejectBinary() {
    return rejectBinary;
  }

  public boolean rejectInvalidUtf8() {
    return rejectInvalidUtf8;
  }

  public boolean skipGenerated() {
    return skipGenerated;
  }

  public boolean skipMinified() {
    return skipMinified;
  }

  /** Return the deterministic path-derived MIME type used by the allow/deny policy. */
  public String mimeType(String path) {
    Objects.requireNonNull(path, "path");
    return MIME_BY_EXTENSION.getOrDefault(extension(path.toLowerCase(Locale.ROOT)), UNKNOWN_MIME_TYPE);
  }

  /** Return whether the path is eligible before loading its blob. */
  public boolean acceptsPath(String path) {
    Objects.requireNonNull(path, "path");
    String lower = path.toLowerCase(Locale.ROOT);
    if (skipGenerated && looksGenerated(lower)) {
      return false;
    }
    String extension = extension(lower);
    if (deniedExtensions.contains(extension)) {
      return false;
    }
    if (!allowedExtensions.isEmpty() && !allowedExtensions.contains(extension)) {
      return false;
    }

    String mimeType = MIME_BY_EXTENSION.getOrDefault(extension, UNKNOWN_MIME_TYPE);
    if (matchesMimeRule(deniedMimeTypes, mimeType)) {
      return false;
    }
    return allowedMimeTypes.isEmpty() || matchesMimeRule(allowedMimeTypes, mimeType);
  }

  /** Decode a bounded candidate blob, or return {@code null} when the configured policy rejects it. */
  public String decode(String path, byte[] bytes) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(bytes, "bytes");
    if (bytes.length > maxFileBytes) {
      return null;
    }
    if (rejectBinary && containsNul(bytes)) {
      return null;
    }

    String text;
    if (rejectInvalidUtf8) {
      try {
        text =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
      } catch (CharacterCodingException exception) {
        return null;
      }
    } else {
      text = new String(bytes, StandardCharsets.UTF_8);
    }

    if (skipMinified && looksMinified(path, text)) {
      return null;
    }
    return text;
  }

  private static int boundedInteger(Object configured, int defaultValue, int maximum, String name) {
    if (configured == null || configured.toString().isBlank()) {
      return defaultValue;
    }
    int value;
    try {
      value = Integer.parseInt(configured.toString().trim());
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(
          name + " must be an integer but was '" + configured + "'", exception);
    }
    if (value <= 0 || value > maximum) {
      throw new IllegalArgumentException(
          name + " must be between 1 and " + maximum + " but was " + value);
    }
    return value;
  }

  private static boolean bool(Object configured, boolean defaultValue, String name) {
    if (configured == null || configured.toString().isBlank()) {
      return defaultValue;
    }
    String value = configured.toString().trim().toLowerCase(Locale.ROOT);
    if ("true".equals(value)) {
      return true;
    }
    if ("false".equals(value)) {
      return false;
    }
    throw new IllegalArgumentException(name + " must be true or false but was '" + configured + "'");
  }

  private static Set<String> extensions(Object configured) {
    if (configured == null || configured.toString().isBlank()) {
      return Set.of();
    }
    Set<String> result = new LinkedHashSet<>();
    Arrays.stream(configured.toString().split(","))
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .map(value -> value.toLowerCase(Locale.ROOT))
        .map(value -> value.startsWith(".") ? value : "." + value)
        .forEach(result::add);
    return result;
  }

  private static Set<String> mimeTypes(Object configured) {
    if (configured == null || configured.toString().isBlank()) {
      return Set.of();
    }
    Set<String> result = new LinkedHashSet<>();
    Arrays.stream(configured.toString().split(","))
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .map(value -> value.toLowerCase(Locale.ROOT))
        .peek(SearchContentPolicy::validateMimeRule)
        .forEach(result::add);
    return result;
  }

  private static void validateMimeRule(String rule) {
    int slash = rule.indexOf('/');
    if (slash <= 0 || slash == rule.length() - 1 || rule.indexOf('/', slash + 1) >= 0) {
      throw new IllegalArgumentException("Invalid MIME rule '" + rule + "'");
    }
    if (rule.contains("*") && !rule.endsWith("/*")) {
      throw new IllegalArgumentException(
          "MIME wildcard is supported only as a subtype wildcard, for example text/*: '"
              + rule
              + "'");
    }
  }

  private static boolean matchesMimeRule(Set<String> rules, String mimeType) {
    for (String rule : rules) {
      if (rule.equals(mimeType)) {
        return true;
      }
      if (rule.endsWith("/*")
          && mimeType.startsWith(rule.substring(0, rule.length() - 1))) {
        return true;
      }
    }
    return false;
  }

  private static String extension(String path) {
    int slash = path.lastIndexOf('/');
    int dot = path.lastIndexOf('.');
    return dot > slash ? path.substring(dot) : "";
  }

  private static boolean containsNul(byte[] bytes) {
    int sample = Math.min(bytes.length, BINARY_SAMPLE_BYTES);
    for (int index = 0; index < sample; index++) {
      if (bytes[index] == 0) {
        return true;
      }
    }
    return false;
  }

  private static boolean looksGenerated(String lowerPath) {
    return lowerPath.contains("/generated/")
        || lowerPath.startsWith("generated/")
        || lowerPath.contains("/target/")
        || lowerPath.startsWith("target/")
        || lowerPath.contains("/build/")
        || lowerPath.startsWith("build/")
        || lowerPath.contains("/dist/")
        || lowerPath.startsWith("dist/")
        || lowerPath.endsWith(".generated.java")
        || lowerPath.endsWith(".g.cs");
  }

  private static boolean looksMinified(String path, String text) {
    String lower = path.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".min.js") || lower.endsWith(".min.css")) {
      return true;
    }
    if (text.isEmpty()) {
      return false;
    }
    int lines = 1;
    int current = 0;
    int longest = 0;
    for (int index = 0; index < text.length(); index++) {
      if (text.charAt(index) == '\n') {
        lines++;
        longest = Math.max(longest, current);
        current = 0;
      } else {
        current++;
      }
    }
    longest = Math.max(longest, current);
    return longest >= MINIFIED_LONGEST_LINE && text.length() / lines >= MINIFIED_AVERAGE_LINE;
  }
}
