/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis.candidate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Mutable in-memory registry of semantic candidates. */
public final class CandidateRegistry {

  private final Map<String, SemanticCandidate> candidates = new LinkedHashMap<>();

  public CandidateRegistry() {}

  public SemanticCandidate register(CandidateId id, CandidateEvidence evidence) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(evidence, "evidence");
    SemanticCandidate existing = candidates.get(id.id());
    SemanticCandidate updated = existing == null
        ? SemanticCandidate.discover(id, evidence)
        : existing.withEvidence(evidence);
    candidates.put(id.id(), updated);
    return updated;
  }

  public Optional<SemanticCandidate> find(CandidateId id) {
    Objects.requireNonNull(id, "id");
    return Optional.ofNullable(candidates.get(id.id()));
  }

  public List<SemanticCandidate> findByLifecycle(CandidateLifecycle lifecycle) {
    Objects.requireNonNull(lifecycle, "lifecycle");
    return candidates.values().stream().filter(candidate -> candidate.lifecycle() == lifecycle).toList();
  }

  public SemanticCandidate transition(CandidateId id, CandidateLifecycle newLifecycle) {
    SemanticCandidate candidate = find(id)
        .orElseThrow(() -> new IllegalArgumentException("Unknown candidate " + id.id()));
    SemanticCandidate updated = candidate.transitionTo(newLifecycle);
    candidates.put(id.id(), updated);
    return updated;
  }

  public String exportJson() {
    StringBuilder json = new StringBuilder("[");
    boolean first = true;
    for (SemanticCandidate candidate : candidates.values()) {
      if (!first) {
        json.append(',');
      }
      CandidateId id = candidate.candidateId();
      json.append('{')
          .append(field("repositoryName", id.repositoryName())).append(',')
          .append(field("sourceCommitId", id.sourceCommitId())).append(',')
          .append(field("category", id.category())).append(',')
          .append(field("analyzerName", id.analyzerName())).append(',')
          .append(field("semanticPayload", id.semanticPayload())).append(',')
          .append(field("id", id.id())).append(',')
          .append(field("lifecycle", candidate.lifecycle().name())).append(',')
          .append("\"evidence\":").append(evidenceArrayToJson(candidate.evidence())).append(',')
          .append(field("discoveredAt", candidate.discoveredAt().toString())).append(',')
          .append(field("lastModifiedAt", candidate.lastModifiedAt().toString())).append(',')
          .append(field("notes", candidate.notes()))
          .append('}');
      first = false;
    }
    return json.append(']').toString();
  }

  private static String evidenceArrayToJson(List<CandidateEvidence> evidences) {
    StringBuilder sb = new StringBuilder("[");
    boolean first = true;
    for (CandidateEvidence e : evidences) {
      if (!first) {
        sb.append(',');
      }
      sb.append('{').append(field("description", e.description()));
      if (e.beforeExample() != null) {
        sb.append(',').append(field("beforeExample", e.beforeExample()));
      }
      if (e.afterExample() != null) {
        sb.append(',').append(field("afterExample", e.afterExample()));
      }
      if (!e.negativeExamples().isEmpty()) {
        sb.append(",\"negativeExamples\":[");
        boolean firstNeg = true;
        for (String neg : e.negativeExamples()) {
          if (!firstNeg) {
            sb.append(',');
          }
          sb.append('"').append(escape(neg)).append('"');
          firstNeg = false;
        }
        sb.append(']');
      }
      sb.append('}');
      first = false;
    }
    return sb.append(']').toString();
  }

  public List<SemanticCandidate> importJson(String json) {
    Objects.requireNonNull(json, "json");
    candidates.clear();
    Parser parser = new Parser(json);
    List<SemanticCandidate> imported = parser.parseCandidateArray();
    for (SemanticCandidate candidate : imported) {
      candidates.put(candidate.candidateId().id(), candidate);
    }
    return List.copyOf(candidates.values());
  }

  public int size() {
    return candidates.size();
  }

  private static String field(String name, String value) {
    return "\"" + escape(name) + "\":\"" + escape(value) + "\"";
  }

  private static String escape(String value) {
    StringBuilder escaped = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\\' -> escaped.append("\\\\");
        case '"' -> escaped.append("\\\"");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> escaped.append(c);
      }
    }
    return escaped.toString();
  }

  private static final class Parser {
    private final String json;
    private int index;

    private Parser(String json) {
      this.json = json.trim();
    }

    private List<Map<String, String>> parseArray() {
      skipWhitespace();
      expect('[');
      List<Map<String, String>> values = new ArrayList<>();
      skipWhitespace();
      if (peek() == ']') {
        index++;
        return values;
      }
      while (true) {
        values.add(parseObject());
        skipWhitespace();
        char next = peek();
        if (next == ',') {
          index++;
          continue;
        }
        if (next == ']') {
          index++;
          return values;
        }
        throw new IllegalArgumentException("Unexpected character '" + next + "' at position " + index);
      }
    }

    private Map<String, String> parseObject() {
      skipWhitespace();
      expect('{');
      Map<String, String> values = new LinkedHashMap<>();
      skipWhitespace();
      if (peek() == '}') {
        index++;
        return values;
      }
      while (true) {
        String key = parseString();
        skipWhitespace();
        expect(':');
        skipWhitespace();
        String value = peek() == '"' ? parseString() : parseNumber();
        values.put(key, value);
        skipWhitespace();
        char next = peek();
        if (next == ',') {
          index++;
          continue;
        }
        if (next == '}') {
          index++;
          return values;
        }
        throw new IllegalArgumentException("Unexpected character '" + next + "' at position " + index);
      }
    }

    private String parseString() {
      expect('"');
      StringBuilder value = new StringBuilder();
      while (index < json.length()) {
        char c = json.charAt(index++);
        if (c == '"') {
          return value.toString();
        }
        if (c == '\\') {
          if (index >= json.length()) {
            throw new IllegalArgumentException("Unterminated escape sequence at position " + (index - 1));
          }
          char escaped = json.charAt(index++);
          switch (escaped) {
            case '"' -> value.append('"');
            case '\\' -> value.append('\\');
            case 'n' -> value.append('\n');
            case 'r' -> value.append('\r');
            case 't' -> value.append('\t');
            default -> value.append(escaped);
          }
        } else {
          value.append(c);
        }
      }
      throw new IllegalArgumentException("Unterminated JSON string at position " + index);
    }

    private List<SemanticCandidate> parseCandidateArray() {
      skipWhitespace();
      expect('[');
      List<SemanticCandidate> result = new ArrayList<>();
      skipWhitespace();
      if (peek() == ']') {
        index++;
        return result;
      }
      while (true) {
        result.add(parseCandidateObject());
        skipWhitespace();
        char next = peek();
        if (next == ',') {
          index++;
          continue;
        }
        if (next == ']') {
          index++;
          return result;
        }
        throw new IllegalArgumentException("Unexpected character '" + next + "' at position " + index);
      }
    }

    private SemanticCandidate parseCandidateObject() {
      skipWhitespace();
      expect('{');
      Map<String, String> stringValues = new LinkedHashMap<>();
      List<CandidateEvidence> evidence = new ArrayList<>();
      skipWhitespace();
      if (peek() == '}') {
        index++;
        return buildCandidate(stringValues, evidence);
      }
      while (true) {
        String key = parseString();
        skipWhitespace();
        expect(':');
        skipWhitespace();
        if (key.equals("evidence") && peek() == '[') {
          evidence = parseEvidenceArray();
        } else if (peek() == '"') {
          stringValues.put(key, parseString());
        } else {
          stringValues.put(key, parseNumber());
        }
        skipWhitespace();
        char next = peek();
        if (next == ',') {
          index++;
          skipWhitespace();
          continue;
        }
        if (next == '}') {
          index++;
          return buildCandidate(stringValues, evidence);
        }
        throw new IllegalArgumentException("Unexpected character '" + next + "' at position " + index);
      }
    }

    private static SemanticCandidate buildCandidate(
        Map<String, String> values, List<CandidateEvidence> evidence) {
      CandidateId id = new CandidateId(
          values.get("repositoryName"),
          values.get("sourceCommitId"),
          values.get("category"),
          values.get("analyzerName"),
          values.get("semanticPayload"),
          values.get("id"));
      return new SemanticCandidate(
          id,
          CandidateLifecycle.valueOf(values.get("lifecycle")),
          evidence,
          Instant.parse(values.get("discoveredAt")),
          Instant.parse(values.get("lastModifiedAt")),
          values.getOrDefault("notes", ""));
    }

    private List<CandidateEvidence> parseEvidenceArray() {
      expect('[');
      List<CandidateEvidence> result = new ArrayList<>();
      skipWhitespace();
      if (peek() == ']') {
        index++;
        return result;
      }
      while (true) {
        result.add(parseEvidenceObject());
        skipWhitespace();
        char next = peek();
        if (next == ',') {
          index++;
          continue;
        }
        if (next == ']') {
          index++;
          return result;
        }
        throw new IllegalArgumentException("Unexpected character '" + next + "' at position " + index);
      }
    }

    private CandidateEvidence parseEvidenceObject() {
      skipWhitespace();
      expect('{');
      Map<String, String> values = new LinkedHashMap<>();
      List<String> negativeExamples = new ArrayList<>();
      skipWhitespace();
      if (peek() == '}') {
        index++;
        return new CandidateEvidence(values.get("description"), null, null, negativeExamples);
      }
      while (true) {
        String key = parseString();
        skipWhitespace();
        expect(':');
        skipWhitespace();
        if (key.equals("negativeExamples") && peek() == '[') {
          negativeExamples = parseStringArray();
        } else if (peek() == '"') {
          values.put(key, parseString());
        } else {
          values.put(key, parseNumber());
        }
        skipWhitespace();
        char next = peek();
        if (next == ',') {
          index++;
          skipWhitespace();
          continue;
        }
        if (next == '}') {
          index++;
          return new CandidateEvidence(
              values.get("description"),
              values.get("beforeExample"),
              values.get("afterExample"),
              negativeExamples);
        }
        throw new IllegalArgumentException("Unexpected character '" + next + "' at position " + index);
      }
    }

    private List<String> parseStringArray() {
      expect('[');
      List<String> result = new ArrayList<>();
      skipWhitespace();
      if (peek() == ']') {
        index++;
        return result;
      }
      while (true) {
        skipWhitespace();
        result.add(parseString());
        skipWhitespace();
        char next = peek();
        if (next == ',') {
          index++;
          continue;
        }
        if (next == ']') {
          index++;
          return result;
        }
        throw new IllegalArgumentException("Unexpected character '" + next + "' at position " + index);
      }
    }

    private String parseNumber() {
      int start = index;
      while (index < json.length() && Character.isDigit(json.charAt(index))) {
        index++;
      }
      return json.substring(start, index);
    }

    private void expect(char expected) {
      skipWhitespace();
      if (peek() != expected) {
        throw new IllegalArgumentException("Expected '" + expected + "' at position " + index);
      }
      index++;
    }

    private char peek() {
      if (index >= json.length()) {
        throw new IllegalArgumentException("Unexpected end of JSON input");
      }
      return json.charAt(index);
    }

    private void skipWhitespace() {
      while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
        index++;
      }
    }
  }
}
