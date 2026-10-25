/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.NameQualifiedType;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.QualifiedType;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;

/** Extracts declaration kinds and parent syntax using the configured source level, without a classpath. */
public final class JavaSourceStructureExtractor {

  public JavaSourceStructure extract(JavaSourceSnapshot snapshot, JavaAnalysisConfiguration configuration) {
    var unit = JavaSyntaxParser.parse(snapshot, configuration);
    List<JavaSourceStructure.TypeDeclaration> declarations = new ArrayList<>();
    unit.accept(new ASTVisitor() {
      @Override
      public boolean visit(TypeDeclaration node) {
        List<JavaSourceStructure.ParentType> extended = new ArrayList<>();
        if (node.getSuperclassType() != null) {
          extended.add(parent(node.getSuperclassType()));
        }
        var interfaces = parents(node.superInterfaceTypes());
        if (node.isInterface()) {
          extended.addAll(interfaces);
        }
        add(node, node.isInterface() ? JavaSourceStructure.TypeKind.INTERFACE : JavaSourceStructure.TypeKind.CLASS,
            extended, node.isInterface() ? List.of() : interfaces);
        return true;
      }

      @Override
      public boolean visit(EnumDeclaration node) {
        add(node, JavaSourceStructure.TypeKind.ENUM, List.of(), parents(node.superInterfaceTypes()));
        return true;
      }

      @Override
      public boolean visit(RecordDeclaration node) {
        add(node, JavaSourceStructure.TypeKind.RECORD, List.of(), parents(node.superInterfaceTypes()));
        return true;
      }

      @Override
      public boolean visit(AnnotationTypeDeclaration node) {
        add(node, JavaSourceStructure.TypeKind.ANNOTATION_TYPE, List.of(), List.of());
        return true;
      }

      private void add(AbstractTypeDeclaration node, JavaSourceStructure.TypeKind kind,
          List<JavaSourceStructure.ParentType> extended, List<JavaSourceStructure.ParentType> implemented) {
        int start = node.getStartPosition();
        declarations.add(new JavaSourceStructure.TypeDeclaration(node.getName().getIdentifier(), kind,
            start, node.getLength(), unit.getLineNumber(start),
            unit.getLineNumber(start + Math.max(0, node.getLength() - 1)), extended, implemented));
      }
    });
    int errors = 0;
    for (IProblem problem : unit.getProblems()) {
      if (problem.isError()) {
        errors++;
      }
    }
    List<JavaSourceStructure.ImportDeclaration> imports = new ArrayList<>();
    for (Object item : unit.imports()) {
      ImportDeclaration declaration = (ImportDeclaration) item;
      imports.add(new JavaSourceStructure.ImportDeclaration(declaration.getName().getFullyQualifiedName(),
          declaration.isStatic(), declaration.isOnDemand()));
    }
    return new JavaSourceStructure(declarations, imports, unit.getProblems().length, errors);
  }

  private static List<JavaSourceStructure.ParentType> parents(List<?> types) {
    return types.stream().map(Type.class::cast).map(JavaSourceStructureExtractor::parent).toList();
  }

  private static JavaSourceStructure.ParentType parent(Type type) {
    return new JavaSourceStructure.ParentType(type.toString().trim(), name(type), type.getStartPosition());
  }

  private static String name(Type type) {
    return switch (type) {
      case ParameterizedType parameterized -> name(parameterized.getType());
      case SimpleType simple -> simple.getName().getFullyQualifiedName();
      case QualifiedType qualified -> name(qualified.getQualifier()) + "." + qualified.getName();
      case NameQualifiedType qualified -> qualified.getQualifier().getFullyQualifiedName() + "." + qualified.getName();
      default -> type.toString().trim();
    };
  }
}
