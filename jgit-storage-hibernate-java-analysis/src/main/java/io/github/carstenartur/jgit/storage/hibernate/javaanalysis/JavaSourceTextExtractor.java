/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.TextBlock;

/** Extracts Javadoc comments and literal text from a source snapshot without a classpath. */
public final class JavaSourceTextExtractor {

  /** Uses the configured source level; binding and classpath settings are intentionally unnecessary. */
  public JavaSourceText extract(JavaSourceSnapshot snapshot, JavaAnalysisConfiguration configuration) {
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
    CompilationUnit unit = (CompilationUnit) parser.createAST(null);
    List<JavaSourceText.Fragment> documentation = new ArrayList<>();
    List<JavaSourceText.Fragment> literals = new ArrayList<>();
    // Module Javadoc is present in the comment table even when JDT does not attach it to the AST.
    for (Object comment : unit.getCommentList()) {
      if (comment instanceof Javadoc node) {
        documentation.add(fragment(node, snapshot.source().substring(
            node.getStartPosition(), node.getStartPosition() + node.getLength()), unit));
      }
    }
    unit.accept(new ASTVisitor() {
      @Override
      public boolean visit(StringLiteral node) {
        literals.add(fragment(node, node.getLiteralValue(), unit));
        return false;
      }

      @Override
      public boolean visit(TextBlock node) {
        literals.add(fragment(node, node.getLiteralValue(), unit));
        return false;
      }
    });
    int errors = 0;
    for (IProblem problem : unit.getProblems()) {
      if (problem.isError()) {
        errors++;
      }
    }
    return new JavaSourceText(documentation, literals, unit.getProblems().length, errors);
  }

  private static JavaSourceText.Fragment fragment(ASTNode node, String text, CompilationUnit unit) {
    int start = node.getStartPosition();
    return new JavaSourceText.Fragment(text, start, node.getLength(), unit.getLineNumber(start),
        unit.getLineNumber(start + Math.max(0, node.getLength() - 1)));
  }
}
