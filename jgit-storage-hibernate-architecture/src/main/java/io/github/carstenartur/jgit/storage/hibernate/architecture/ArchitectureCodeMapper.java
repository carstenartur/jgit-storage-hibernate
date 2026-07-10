/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.architecture;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaSoftwareGraph;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaSymbolIndex;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Maps semantic Java symbols to architecture elements through versioned selectors. */
public final class ArchitectureCodeMapper {

  public ArchitectureCodeMapping map(ArchitectureSnapshot architecture, JavaSoftwareGraph graph) {
    Map<String, Pattern> compiledPatterns = compilePatterns(architecture);
    Map<String, String> mapped = new LinkedHashMap<>();
    Map<String, List<String>> ambiguous = new LinkedHashMap<>();
    List<String> unmapped = new ArrayList<>();

    for (JavaSymbolIndex symbol : graph.symbols().values()) {
      String key = symbol.getStableSemanticKey();
      if (key == null) continue;
      List<String> matches = architecture.elements().stream()
          .filter(element -> matches(element, symbol, compiledPatterns))
          .map(ArchitectureElement::id)
          .toList();
      if (matches.size() == 1) mapped.put(key, matches.getFirst());
      else if (matches.isEmpty()) unmapped.add(key);
      else ambiguous.put(key, matches);
    }
    return new ArchitectureCodeMapping(mapped, ambiguous, unmapped);
  }

  private static Map<String, Pattern> compilePatterns(ArchitectureSnapshot architecture) {
    Map<String, Pattern> patterns = new LinkedHashMap<>();
    for (ArchitectureElement element : architecture.elements()) {
      String codePattern = element.attributes().get("codePattern");
      if (codePattern != null) {
        try {
          patterns.put(element.id(), Pattern.compile(codePattern));
        } catch (PatternSyntaxException ignored) {
          // Invalid pattern: element will not match by codePattern
        }
      }
    }
    return patterns;
  }

  private static boolean matches(
      ArchitectureElement element, JavaSymbolIndex symbol, Map<String, Pattern> compiledPatterns) {
    Pattern pattern = compiledPatterns.get(element.id());
    if (pattern != null && pattern.matcher(symbol.getStableSemanticKey()).matches()) return true;
    String packagePrefix = element.attributes().get("packagePrefix");
    if (packagePrefix != null && symbol.getPackageName() != null
        && symbol.getPackageName().startsWith(packagePrefix)) return true;
    String pathPrefix = element.attributes().get("pathPrefix");
    return pathPrefix != null && symbol.getPath() != null && symbol.getPath().startsWith(pathPrefix);
  }
}
