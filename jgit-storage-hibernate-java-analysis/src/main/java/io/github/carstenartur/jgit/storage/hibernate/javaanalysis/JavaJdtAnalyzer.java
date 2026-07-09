/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaAnalysisRun;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaReferenceIndex;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaSymbolIndex;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.internal.JavaAnalysisHashes;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.internal.JavaBindingKeys;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.ArrayType;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NameQualifiedType;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.QualifiedType;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

/**
 * Plain Maven, JDT Core based Java analyzer.
 *
 * <p>The analyzer asks JDT for bindings by default and persists the quality of the returned binding
 * data. Its public API exposes only this module's own DTOs/entities, never JDT AST or binding types.
 */
public class JavaJdtAnalyzer {

  public JavaAnalysisResult analyze(
      JavaSourceSnapshot snapshot, JavaAnalysisConfiguration configuration) {
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(configuration, "configuration");

    Map<String, String> compilerOptions = compilerOptions(configuration);
    JavaAnalysisRun run = startRun(snapshot, configuration, compilerOptions);

    try {
      CompilationUnit unit = parse(snapshot, configuration, compilerOptions);
      IProblem[] problems = unit.getProblems();
      int errorCount = countErrors(problems);
      run.setProblemCount(problems.length);
      run.setErrorCount(errorCount);
      run.setStatus(errorCount == 0 ? JavaAnalysisStatus.COMPLETED : JavaAnalysisStatus.COMPLETED_WITH_ERRORS);

      JavaIndexVisitor visitor = new JavaIndexVisitor(snapshot, configuration, unit, packageName(unit));
      unit.accept(visitor);
      run.setCompletedAt(Instant.now());
      return new JavaAnalysisResult(run, visitor.symbols(), visitor.references());
    } catch (RuntimeException e) {
      run.setStatus(JavaAnalysisStatus.FAILED);
      run.setCompletedAt(Instant.now());
      run.setFailureMessage(e.getMessage());
      return new JavaAnalysisResult(run, List.of(), List.of());
    }
  }

  private static CompilationUnit parse(
      JavaSourceSnapshot snapshot,
      JavaAnalysisConfiguration configuration,
      Map<String, String> compilerOptions) {
    ASTParser parser = ASTParser.newParser(AST.JLS21);
    parser.setKind(ASTParser.K_COMPILATION_UNIT);
    parser.setCompilerOptions(compilerOptions);
    parser.setSource(snapshot.source().toCharArray());
    parser.setUnitName(unitName(snapshot.path()));

    if (configuration.resolveBindings()) {
      parser.setResolveBindings(true);
      parser.setBindingsRecovery(configuration.recoverBindings());
      parser.setEnvironment(
          configuration.classpathEntries().toArray(String[]::new),
          configuration.sourcepathEntries().toArray(String[]::new),
          encodings(configuration),
          configuration.includeRunningVmBootClasspath());
    }

    return (CompilationUnit) parser.createAST(null);
  }

  private static String[] encodings(JavaAnalysisConfiguration configuration) {
    if (configuration.encodings().isEmpty()) {
      return null;
    }
    if (configuration.encodings().size() != configuration.sourcepathEntries().size()) {
      return null;
    }
    return configuration.encodings().toArray(String[]::new);
  }

  private static Map<String, String> compilerOptions(JavaAnalysisConfiguration configuration) {
    Map<String, String> options = JavaCore.getOptions();
    options.put(JavaCore.COMPILER_SOURCE, configuration.sourceLevel());
    options.put(JavaCore.COMPILER_COMPLIANCE, configuration.sourceLevel());
    options.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, configuration.sourceLevel());
    options.put(JavaCore.COMPILER_PB_ENABLE_PREVIEW_FEATURES, JavaCore.DISABLED);
    return options;
  }

  private static JavaAnalysisRun startRun(
      JavaSourceSnapshot snapshot,
      JavaAnalysisConfiguration configuration,
      Map<String, String> compilerOptions) {
    JavaAnalysisRun run = new JavaAnalysisRun();
    run.setRepositoryName(snapshot.repositoryName());
    run.setCommitId(snapshot.commitId());
    run.setAnalyzerVersion(configuration.analyzerVersion());
    run.setJdtVersion(jdtVersion());
    run.setSourceLevel(configuration.sourceLevel());
    run.setBindingMode(configuration.bindingMode());
    run.setStatus(JavaAnalysisStatus.RUNNING);
    run.setStartedAt(Instant.now());
    run.setCompilerOptionsHash(JavaAnalysisHashes.hash(compilerOptions));
    run.setClasspathHash(JavaAnalysisHashes.hash(configuration.classpathEntries()));
    run.setSourcepathHash(JavaAnalysisHashes.hash(configuration.sourcepathEntries()));
    run.setModulepathHash(JavaAnalysisHashes.hash(configuration.modulepathEntries()));
    return run;
  }

  private static String jdtVersion() {
    Package jdtPackage = JavaCore.class.getPackage();
    String implementationVersion = jdtPackage == null ? null : jdtPackage.getImplementationVersion();
    return implementationVersion == null ? "unknown" : implementationVersion;
  }

  private static String unitName(String path) {
    String normalized = path.replace('\\', '/');
    if (!normalized.endsWith(".java")) {
      normalized = normalized + ".java";
    }
    return normalized.startsWith("/") ? normalized : "/" + normalized;
  }

  private static int countErrors(IProblem[] problems) {
    int errors = 0;
    for (IProblem problem : problems) {
      if (problem.isError()) {
        errors++;
      }
    }
    return errors;
  }

  private static String packageName(CompilationUnit unit) {
    return unit.getPackage() == null ? "" : unit.getPackage().getName().getFullyQualifiedName();
  }

  private static final class JavaIndexVisitor extends ASTVisitor {
    private final JavaSourceSnapshot snapshot;
    private final JavaAnalysisConfiguration configuration;
    private final CompilationUnit unit;
    private final String packageName;
    private final List<JavaSymbolIndex> symbols = new ArrayList<>();
    private final List<JavaReferenceIndex> references = new ArrayList<>();
    private final Deque<String> declaringTypes = new ArrayDeque<>();
    private final Deque<String> declaringSymbolKeys = new ArrayDeque<>();

    private JavaIndexVisitor(
        JavaSourceSnapshot snapshot,
        JavaAnalysisConfiguration configuration,
        CompilationUnit unit,
        String packageName) {
      this.snapshot = snapshot;
      this.configuration = configuration;
      this.unit = unit;
      this.packageName = packageName;
    }

    private List<JavaSymbolIndex> symbols() {
      return symbols;
    }

    private List<JavaReferenceIndex> references() {
      return references;
    }

    @Override
    public boolean visit(ImportDeclaration node) {
      IBinding binding = node.resolveBinding();
      addReference(node, JavaReferenceKind.IMPORT, node.getName().getFullyQualifiedName(), binding);
      return false;
    }

    @Override
    public boolean visit(TypeDeclaration node) {
      ITypeBinding binding = node.resolveBinding();
      JavaSymbolIndex symbol = addTypeSymbol(node, JavaSymbolKind.TYPE, node.getName().getIdentifier(), binding);
      pushDeclaringType(symbol);
      return true;
    }

    @Override
    public void endVisit(TypeDeclaration node) {
      popDeclaringType();
    }

    @Override
    public boolean visit(EnumDeclaration node) {
      ITypeBinding binding = node.resolveBinding();
      JavaSymbolIndex symbol = addTypeSymbol(node, JavaSymbolKind.ENUM, node.getName().getIdentifier(), binding);
      pushDeclaringType(symbol);
      return true;
    }

    @Override
    public void endVisit(EnumDeclaration node) {
      popDeclaringType();
    }

    @Override
    public boolean visit(RecordDeclaration node) {
      ITypeBinding binding = node.resolveBinding();
      JavaSymbolIndex symbol = addTypeSymbol(node, JavaSymbolKind.RECORD, node.getName().getIdentifier(), binding);
      pushDeclaringType(symbol);
      return true;
    }

    @Override
    public void endVisit(RecordDeclaration node) {
      popDeclaringType();
    }

    @Override
    public boolean visit(AnnotationTypeDeclaration node) {
      ITypeBinding binding = node.resolveBinding();
      JavaSymbolIndex symbol =
          addTypeSymbol(node, JavaSymbolKind.ANNOTATION_TYPE, node.getName().getIdentifier(), binding);
      pushDeclaringType(symbol);
      return true;
    }

    @Override
    public void endVisit(AnnotationTypeDeclaration node) {
      popDeclaringType();
    }

    @Override
    public boolean visit(MethodDeclaration node) {
      IMethodBinding binding = node.resolveBinding();
      JavaSymbolIndex symbol = baseSymbol(node);
      String simpleName = node.getName().getIdentifier();
      String declaringType = declaringTypes.peek();
      String qualifiedName = declaringType == null ? simpleName : declaringType + "#" + simpleName;
      String signature = methodSignature(node, binding);
      symbol.setSymbolKind(node.isConstructor() ? JavaSymbolKind.CONSTRUCTOR : JavaSymbolKind.METHOD);
      symbol.setBindingStatus(JavaBindingKeys.status(binding, configuration.resolveBindings()));
      symbol.setPackageName(packageName);
      symbol.setSimpleName(simpleName);
      symbol.setQualifiedName(qualifiedName);
      symbol.setDeclaringType(declaringType);
      symbol.setSignature(signature);
      symbol.setReturnType(returnType(node, binding));
      symbol.setParameterTypes(parameterTypes(node, binding));
      symbol.setModifiers(Modifier.toString(node.getModifiers()));
      symbol.setAnnotations(annotations(node));
      symbol.setRawBindingKey(JavaBindingKeys.rawKey(binding));
      symbol.setDeclarationBindingKey(JavaBindingKeys.declarationKey(binding));
      symbol.setDeclaringTypeBindingKey(JavaBindingKeys.declaringTypeKey(binding));
      symbol.setTypeBindingKey(JavaBindingKeys.typeKey(binding));
      symbol.setStableSemanticKey(JavaBindingKeys.stableMethodKey(binding, qualifiedName));
      symbols.add(symbol);
      return true;
    }

    @Override
    public boolean visit(FieldDeclaration node) {
      for (Object fragmentObject : node.fragments()) {
        VariableDeclarationFragment fragment = (VariableDeclarationFragment) fragmentObject;
        IVariableBinding binding = fragment.resolveBinding();
        JavaSymbolIndex symbol = baseSymbol(fragment);
        String simpleName = fragment.getName().getIdentifier();
        String declaringType = declaringTypes.peek();
        String qualifiedName = declaringType == null ? simpleName : declaringType + "#" + simpleName;
        symbol.setSymbolKind(JavaSymbolKind.FIELD);
        symbol.setBindingStatus(JavaBindingKeys.status(binding, configuration.resolveBindings()));
        symbol.setPackageName(packageName);
        symbol.setSimpleName(simpleName);
        symbol.setQualifiedName(qualifiedName);
        symbol.setDeclaringType(declaringType);
        symbol.setSignature(node.getType().toString() + " " + simpleName);
        symbol.setReturnType(null);
        symbol.setParameterTypes(null);
        symbol.setModifiers(Modifier.toString(node.getModifiers()));
        symbol.setAnnotations(annotations(node));
        symbol.setRawBindingKey(JavaBindingKeys.rawKey(binding));
        symbol.setDeclarationBindingKey(JavaBindingKeys.declarationKey(binding));
        symbol.setDeclaringTypeBindingKey(JavaBindingKeys.declaringTypeKey(binding));
        symbol.setTypeBindingKey(JavaBindingKeys.typeKey(binding));
        symbol.setStableSemanticKey(JavaBindingKeys.stableVariableKey(binding, qualifiedName));
        symbols.add(symbol);
      }
      return true;
    }

    @Override
    public boolean visit(MethodInvocation node) {
      IMethodBinding binding = node.resolveMethodBinding();
      addReference(node, JavaReferenceKind.METHOD_INVOCATION, node.getName().getIdentifier(), binding);
      return true;
    }

    @Override
    public boolean visit(ClassInstanceCreation node) {
      IMethodBinding binding = node.resolveConstructorBinding();
      addReference(node, JavaReferenceKind.CONSTRUCTOR_INVOCATION, node.getType().toString(), binding);
      return true;
    }

    @Override
    public boolean visit(FieldAccess node) {
      IVariableBinding binding = node.resolveFieldBinding();
      addReference(node, JavaReferenceKind.FIELD_ACCESS, node.getName().getIdentifier(), binding);
      return true;
    }

    @Override
    public boolean visit(SimpleType node) {
      addTypeReference(node, node.resolveBinding(), node.toString());
      return true;
    }

    @Override
    public boolean visit(QualifiedType node) {
      addTypeReference(node, node.resolveBinding(), node.toString());
      return true;
    }

    @Override
    public boolean visit(NameQualifiedType node) {
      addTypeReference(node, node.resolveBinding(), node.toString());
      return true;
    }

    @Override
    public boolean visit(ParameterizedType node) {
      addTypeReference(node, node.resolveBinding(), node.toString());
      return true;
    }

    @Override
    public boolean visit(ArrayType node) {
      addTypeReference(node, node.resolveBinding(), node.toString());
      return true;
    }

    @Override
    public boolean visit(NormalAnnotation node) {
      addAnnotationReference(node);
      return true;
    }

    @Override
    public boolean visit(SingleMemberAnnotation node) {
      addAnnotationReference(node);
      return true;
    }

    @Override
    public boolean visit(org.eclipse.jdt.core.dom.MarkerAnnotation node) {
      addAnnotationReference(node);
      return true;
    }

    private JavaSymbolIndex addTypeSymbol(
        BodyDeclaration node, JavaSymbolKind kind, String simpleName, ITypeBinding binding) {
      JavaSymbolIndex symbol = baseSymbol(node);
      String qualifiedName = typeQualifiedName(simpleName, binding);
      symbol.setSymbolKind(kind);
      symbol.setBindingStatus(JavaBindingKeys.status(binding, configuration.resolveBindings()));
      symbol.setPackageName(packageName);
      symbol.setSimpleName(simpleName);
      symbol.setQualifiedName(qualifiedName);
      symbol.setDeclaringType(declaringTypes.peek());
      symbol.setSignature(qualifiedName);
      symbol.setReturnType(null);
      symbol.setParameterTypes(null);
      symbol.setModifiers(Modifier.toString(node.getModifiers()));
      symbol.setAnnotations(annotations(node));
      symbol.setRawBindingKey(JavaBindingKeys.rawKey(binding));
      symbol.setDeclarationBindingKey(JavaBindingKeys.declarationKey(binding));
      symbol.setDeclaringTypeBindingKey(JavaBindingKeys.declaringTypeKey(binding));
      symbol.setTypeBindingKey(JavaBindingKeys.typeKey(binding));
      symbol.setStableSemanticKey(JavaBindingKeys.stableTypeKey(binding, qualifiedName));
      symbols.add(symbol);
      return symbol;
    }

    private JavaSymbolIndex baseSymbol(ASTNode node) {
      JavaSymbolIndex symbol = new JavaSymbolIndex();
      symbol.setRepositoryName(snapshot.repositoryName());
      symbol.setCommitId(snapshot.commitId());
      symbol.setBlobId(snapshot.blobId());
      symbol.setPath(snapshot.path());
      symbol.setStartPosition(node.getStartPosition());
      symbol.setSourceLength(node.getLength());
      symbol.setStartLine(line(node.getStartPosition()));
      symbol.setEndLine(line(node.getStartPosition() + Math.max(0, node.getLength() - 1)));
      return symbol;
    }

    private void addTypeReference(Type node, ITypeBinding binding, String referenceName) {
      addReference(node, JavaReferenceKind.TYPE_REFERENCE, referenceName, binding);
    }

    private void addAnnotationReference(Annotation node) {
      ITypeBinding annotationType = node.resolveAnnotationBinding() == null
          ? null
          : node.resolveAnnotationBinding().getAnnotationType();
      addReference(node, JavaReferenceKind.ANNOTATION_USE, node.getTypeName().getFullyQualifiedName(), annotationType);
    }

    private void addReference(ASTNode node, JavaReferenceKind kind, String name, IBinding binding) {
      JavaReferenceIndex reference = new JavaReferenceIndex();
      reference.setRepositoryName(snapshot.repositoryName());
      reference.setCommitId(snapshot.commitId());
      reference.setBlobId(snapshot.blobId());
      reference.setPath(snapshot.path());
      reference.setReferenceKind(kind);
      reference.setBindingStatus(JavaBindingKeys.status(binding, configuration.resolveBindings()));
      reference.setReferenceName(name);
      reference.setSourceSymbolKey(declaringSymbolKeys.peek());
      reference.setRawBindingKey(JavaBindingKeys.rawKey(binding));
      reference.setDeclarationBindingKey(JavaBindingKeys.declarationKey(binding));
      reference.setTargetTypeBindingKey(JavaBindingKeys.typeKey(binding));
      reference.setTargetStableSemanticKey(stableReferenceKey(kind, name, binding));
      reference.setStartPosition(node.getStartPosition());
      reference.setSourceLength(node.getLength());
      reference.setStartLine(line(node.getStartPosition()));
      reference.setEndLine(line(node.getStartPosition() + Math.max(0, node.getLength() - 1)));
      references.add(reference);
    }

    private String stableReferenceKey(JavaReferenceKind kind, String name, IBinding binding) {
      if (binding instanceof ITypeBinding typeBinding) {
        return JavaBindingKeys.stableTypeKey(typeBinding, name);
      }
      if (binding instanceof IMethodBinding methodBinding) {
        return JavaBindingKeys.stableMethodKey(methodBinding, name);
      }
      if (binding instanceof IVariableBinding variableBinding) {
        return JavaBindingKeys.stableVariableKey(variableBinding, name);
      }
      return JavaBindingKeys.stableKey(kind.name(), name, null);
    }

    private String typeQualifiedName(String simpleName, ITypeBinding binding) {
      String bindingName = JavaBindingKeys.qualifiedName(binding);
      if (bindingName != null && !bindingName.isBlank()) {
        return bindingName;
      }
      String declaringType = declaringTypes.peek();
      if (declaringType != null && !declaringType.isBlank()) {
        return declaringType + "." + simpleName;
      }
      return packageName == null || packageName.isBlank() ? simpleName : packageName + "." + simpleName;
    }

    private String methodSignature(MethodDeclaration node, IMethodBinding binding) {
      if (binding != null) {
        return node.getName().getIdentifier() + "(" + JavaBindingKeys.parameterTypes(binding) + ")";
      }
      StringJoiner joiner = new StringJoiner(",");
      for (Object parameterObject : node.parameters()) {
        SingleVariableDeclaration parameter = (SingleVariableDeclaration) parameterObject;
        joiner.add(parameter.getType().toString());
      }
      return node.getName().getIdentifier() + "(" + joiner + ")";
    }

    private String parameterTypes(MethodDeclaration node, IMethodBinding binding) {
      String bindingParameters = JavaBindingKeys.parameterTypes(binding);
      if (bindingParameters != null) {
        return bindingParameters;
      }
      StringJoiner joiner = new StringJoiner(",");
      for (Object parameterObject : node.parameters()) {
        SingleVariableDeclaration parameter = (SingleVariableDeclaration) parameterObject;
        joiner.add(parameter.getType().toString());
      }
      return joiner.toString();
    }

    private String returnType(MethodDeclaration node, IMethodBinding binding) {
      if (binding != null) {
        return JavaBindingKeys.qualifiedName(binding.getReturnType());
      }
      return node.getReturnType2() == null ? null : node.getReturnType2().toString();
    }

    private String annotations(BodyDeclaration declaration) {
      return declaration.modifiers().stream()
          .filter(Annotation.class::isInstance)
          .map(Object::toString)
          .reduce((left, right) -> left + "\n" + right)
          .orElse(null);
    }

    private int line(int position) {
      if (position < 0) {
        return -1;
      }
      return unit.getLineNumber(position);
    }

    private void pushDeclaringType(JavaSymbolIndex symbol) {
      declaringTypes.push(symbol.getQualifiedName());
      declaringSymbolKeys.push(symbol.getStableSemanticKey());
    }

    private void popDeclaringType() {
      declaringTypes.pop();
      declaringSymbolKeys.pop();
    }
  }
}
