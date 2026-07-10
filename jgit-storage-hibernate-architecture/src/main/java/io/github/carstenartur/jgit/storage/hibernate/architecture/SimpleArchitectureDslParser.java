/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.architecture;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaGraphEdgeKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reference line-oriented DSL proving the SPI end to end.
 *
 * <pre>
 * element api layer "Public API" codePattern=^TYPE:com.example.api.*
 * relation api-domain depends api -> domain
 * rule no-ui-db forbid REFERENCES_TYPE from ui to database evidence=adr-7 reason="UI must not access DB"
 * evidence adr-7 for no-ui-db kind=ADR path=docs/adr/0007.md rationale="Layering decision"
 * </pre>
 */
public final class SimpleArchitectureDslParser implements ArchitectureDslParser {

  @Override public String dslId() { return "simple-architecture"; }
  @Override public boolean supports(String path, String content) {
    return path.endsWith(".architecture") || path.endsWith(".archdsl");
  }

  @Override
  public ArchitectureDslParseResult parse(ArchitectureDslSource source) {
    List<ArchitectureElement> elements = new ArrayList<>();
    List<ArchitectureRelation> relations = new ArrayList<>();
    List<ArchitectureRule> rules = new ArrayList<>();
    List<ArchitectureEvidence> evidence = new ArrayList<>();
    List<String> diagnostics = new ArrayList<>();
    String[] lines = source.content().split("\\R");
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].strip();
      if (line.isEmpty() || line.startsWith("#")) continue;
      try {
        List<String> tokens = tokenize(line);
        switch (tokens.getFirst()) {
          case "element" -> elements.add(parseElement(tokens));
          case "relation" -> relations.add(parseRelation(tokens));
          case "rule" -> rules.add(parseRule(tokens));
          case "evidence" -> evidence.add(parseEvidence(tokens, source));
          default -> diagnostics.add("Line " + (i + 1) + ": unknown statement " + tokens.getFirst());
        }
      } catch (RuntimeException exception) {
        diagnostics.add("Line " + (i + 1) + ": " + exception.getMessage());
      }
    }
    ArchitectureSnapshot snapshot = new ArchitectureSnapshot(
        source.repositoryName(), source.commitId(), dslId(), "1", elements, relations, rules, evidence);
    return new ArchitectureDslParseResult(snapshot, diagnostics);
  }

  private static ArchitectureElement parseElement(List<String> t) {
    require(t, 4, "element <id> <kind> <name> [key=value]");
    return new ArchitectureElement(t.get(1), t.get(2), t.get(3), attributes(t, 4));
  }

  private static ArchitectureRelation parseRelation(List<String> t) {
    require(t, 6, "relation <id> <kind> <source> -> <target>");
    if (!"->".equals(t.get(4))) throw new IllegalArgumentException("expected ->");
    return new ArchitectureRelation(t.get(1), t.get(2), t.get(3), t.get(5), attributes(t, 6));
  }

  private static ArchitectureRule parseRule(List<String> t) {
    require(t, 8, "rule <id> <allow|forbid|require> <edge> from <source> to <target>");
    if (!"from".equals(t.get(4)) || !"to".equals(t.get(6))) throw new IllegalArgumentException("expected from/to");
    Map<String, String> attributes = attributes(t, 8);
    return new ArchitectureRule(
        t.get(1), ArchitectureRuleEffect.valueOf(t.get(2).toUpperCase()), JavaGraphEdgeKind.valueOf(t.get(3)),
        t.get(5), t.get(7), attributes.get("reason"), attributes.get("evidence"));
  }

  private static ArchitectureEvidence parseEvidence(List<String> t, ArchitectureDslSource source) {
    require(t, 4, "evidence <id> for <subject> key=value");
    if (!"for".equals(t.get(2))) throw new IllegalArgumentException("expected for");
    Map<String, String> a = attributes(t, 4);
    double confidence = Double.parseDouble(a.getOrDefault("confidence", "1.0"));
    Integer line = a.containsKey("line") ? Integer.valueOf(a.get("line")) : null;
    return new ArchitectureEvidence(
        t.get(1), t.get(3), a.getOrDefault("kind", "DOCUMENT"), source.repositoryName(), source.commitId(),
        a.getOrDefault("path", source.path()), line, a.getOrDefault("rationale", "Declared in architecture DSL"),
        confidence, a);
  }

  private static Map<String, String> attributes(List<String> tokens, int start) {
    Map<String, String> result = new LinkedHashMap<>();
    for (int i = start; i < tokens.size(); i++) {
      String token = tokens.get(i);
      int separator = token.indexOf('=');
      if (separator < 1) throw new IllegalArgumentException("expected key=value but got " + token);
      result.put(token.substring(0, separator), token.substring(separator + 1));
    }
    return result;
  }

  private static List<String> tokenize(String line) {
    List<String> tokens = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '"') { quoted = !quoted; continue; }
      if (Character.isWhitespace(c) && !quoted) {
        if (!current.isEmpty()) { tokens.add(current.toString()); current.setLength(0); }
      } else current.append(c);
    }
    if (quoted) throw new IllegalArgumentException("unterminated quote");
    if (!current.isEmpty()) tokens.add(current.toString());
    return tokens;
  }

  private static void require(List<String> tokens, int minimum, String syntax) {
    if (tokens.size() < minimum) throw new IllegalArgumentException("expected " + syntax);
  }
}
