/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import java.util.Map;
import java.util.Objects;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;

/** Shared syntax-only parser configuration for public source facets. */
final class JavaSyntaxParser {

  private JavaSyntaxParser() {}

  static CompilationUnit parse(JavaSourceSnapshot snapshot, JavaAnalysisConfiguration configuration) {
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(configuration, "configuration");
    Map<String, String> options = JavaCore.getOptions();
    JavaCore.setComplianceOptions(configuration.sourceLevel(), options);
    options.put(JavaCore.COMPILER_DOC_COMMENT_SUPPORT, JavaCore.ENABLED);
    ASTParser parser = ASTParser.newParser(AST.JLS21);
    parser.setKind(ASTParser.K_COMPILATION_UNIT);
    parser.setCompilerOptions(options);
    parser.setSource(snapshot.source().toCharArray());
    parser.setUnitName(snapshot.path().replace('\\', '/'));
    return (CompilationUnit) parser.createAST(null);
  }
}
