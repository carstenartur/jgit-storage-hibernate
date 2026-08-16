/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/** Verifies linkage and supported public signatures from the packaged runtime artifacts. */
class PackagedRuntimeJarLinkageTest {

  private static final String PROJECT_PACKAGE = "io.github.carstenartur.jgit.storage.hibernate";
  private static final String JGIT_INTERNAL_PREFIX = "org.eclipse.jgit.internal.";
  private static final String INTERNAL_API_ANNOTATION = PROJECT_PACKAGE + ".InternalApi";
  private static final List<String> RUNTIME_MODULES =
      List.of(
          "jgit-storage-hibernate-core",
          "jgit-storage-hibernate-security",
          "jgit-storage-hibernate-smart-http",
          "jgit-storage-hibernate-search",
          "jgit-storage-hibernate-java-analysis",
          "jgit-storage-hibernate-architecture");

  @Test
  void packagedRuntimeClassesAndSignaturesResolveWithoutMissingDependencies() throws Exception {
    Path root = reactorRoot();
    List<Path> artifacts = RUNTIME_MODULES.stream().map(module -> packagedJar(root, module)).toList();
    List<URL> classPath = new ArrayList<>();
    for (Path artifact : artifacts) {
      classPath.add(artifact.toUri().toURL());
    }
    for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
      if (!entry.isBlank()) {
        classPath.add(Path.of(entry).toAbsolutePath().normalize().toUri().toURL());
      }
    }

    try (URLClassLoader loader =
        new URLClassLoader(classPath.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
      for (Path artifact : artifacts) {
        verifyArtifact(artifact, loader);
      }
    }
  }

  private static void verifyArtifact(Path artifact, ClassLoader loader) throws Exception {
    URL artifactUrl = artifact.toUri().toURL();
    List<String> classNames = classNames(artifact);
    assertFalse(classNames.isEmpty(), () -> "No classes found in packaged artifact " + artifact);

    for (String className : classNames) {
      try {
        Class<?> type = Class.forName(className, false, loader);
        assertEquals(
            artifactUrl,
            type.getProtectionDomain().getCodeSource().getLocation(),
            () -> className + " was not loaded from packaged artifact " + artifact);
        resolveCompleteClassSurface(type);
        if (Modifier.isPublic(type.getModifiers()) && !isInternal(type)) {
          assertSupportedPublicSignature(type);
        }
      } catch (LinkageError | TypeNotPresentException exception) {
        throw new AssertionError(
            "Packaged class " + className + " has unresolved linkage in " + artifact, exception);
      }
    }
  }

  private static List<String> classNames(Path artifact) throws Exception {
    try (JarFile jar = new JarFile(artifact.toFile())) {
      return jar.stream()
          .map(JarEntry::getName)
          .filter(name -> name.endsWith(".class"))
          .filter(name -> !name.startsWith("META-INF/versions/"))
          .filter(name -> !name.equals("module-info.class"))
          .map(name -> name.substring(0, name.length() - ".class".length()).replace('/', '.'))
          .sorted()
          .toList();
    }
  }

  private static void resolveCompleteClassSurface(Class<?> type) {
    resolveType(type.getGenericSuperclass());
    for (Type interfaceType : type.getGenericInterfaces()) {
      resolveType(interfaceType);
    }
    resolveAnnotations(type);
    for (Constructor<?> constructor : type.getDeclaredConstructors()) {
      resolveExecutable(constructor.getGenericParameterTypes(), constructor.getGenericExceptionTypes());
      resolveAnnotations(constructor);
      resolveAnnotations(constructor.getParameterAnnotations());
    }
    for (Method method : type.getDeclaredMethods()) {
      resolveType(method.getGenericReturnType());
      resolveExecutable(method.getGenericParameterTypes(), method.getGenericExceptionTypes());
      resolveAnnotations(method);
      resolveAnnotations(method.getParameterAnnotations());
      if (method.getDefaultValue() instanceof Class<?> defaultClass) {
        defaultClass.getName();
      }
    }
    for (Field field : type.getDeclaredFields()) {
      resolveType(field.getGenericType());
      resolveAnnotations(field);
    }
    for (RecordComponent component : type.getRecordComponents() == null
        ? new RecordComponent[0]
        : type.getRecordComponents()) {
      resolveType(component.getGenericType());
      resolveAnnotations(component);
    }
  }

  private static void resolveExecutable(Type[] parameters, Type[] exceptions) {
    for (Type parameter : parameters) {
      resolveType(parameter);
    }
    for (Type exception : exceptions) {
      resolveType(exception);
    }
  }

  private static void resolveType(Type type) {
    if (type == null) {
      return;
    }
    if (type instanceof Class<?> clazz) {
      clazz.getName();
      if (clazz.isArray()) {
        resolveType(clazz.getComponentType());
      }
      return;
    }
    if (type instanceof ParameterizedType parameterized) {
      resolveType(parameterized.getRawType());
      resolveType(parameterized.getOwnerType());
      for (Type argument : parameterized.getActualTypeArguments()) {
        resolveType(argument);
      }
      return;
    }
    if (type instanceof GenericArrayType array) {
      resolveType(array.getGenericComponentType());
      return;
    }
    if (type instanceof WildcardType wildcard) {
      for (Type bound : wildcard.getUpperBounds()) {
        resolveType(bound);
      }
      for (Type bound : wildcard.getLowerBounds()) {
        resolveType(bound);
      }
      return;
    }
    if (type instanceof TypeVariable<?> variable) {
      for (Type bound : variable.getBounds()) {
        resolveType(bound);
      }
    }
  }

  private static void resolveAnnotations(AnnotatedElement element) {
    for (Annotation annotation : element.getAnnotations()) {
      annotation.annotationType().getDeclaredMethods();
      annotation.toString();
    }
  }

  private static void resolveAnnotations(Annotation[][] annotations) {
    for (Annotation[] parameterAnnotations : annotations) {
      for (Annotation annotation : parameterAnnotations) {
        annotation.annotationType().getDeclaredMethods();
        annotation.toString();
      }
    }
  }

  private static boolean isInternal(Class<?> type) {
    return hasAnnotation(type, INTERNAL_API_ANNOTATION)
        || hasAnnotation(type.getPackage(), INTERNAL_API_ANNOTATION);
  }

  private static boolean hasAnnotation(AnnotatedElement element, String annotationName) {
    if (element == null) {
      return false;
    }
    for (Annotation annotation : element.getAnnotations()) {
      if (annotation.annotationType().getName().equals(annotationName)) {
        return true;
      }
    }
    return false;
  }

  private static void assertSupportedPublicSignature(Class<?> type) {
    assertNoJGitInternalType(type, type.getGenericSuperclass());
    assertNoJGitInternalTypes(type, type.getGenericInterfaces());
    for (Constructor<?> constructor : type.getDeclaredConstructors()) {
      if (isPublicOrProtected(constructor.getModifiers())) {
        assertNoJGitInternalTypes(constructor, constructor.getGenericParameterTypes());
        assertNoJGitInternalTypes(constructor, constructor.getGenericExceptionTypes());
      }
    }
    for (Method method : type.getDeclaredMethods()) {
      if (isPublicOrProtected(method.getModifiers())) {
        assertNoJGitInternalType(method, method.getGenericReturnType());
        assertNoJGitInternalTypes(method, method.getGenericParameterTypes());
        assertNoJGitInternalTypes(method, method.getGenericExceptionTypes());
      }
    }
    for (Field field : type.getDeclaredFields()) {
      if (isPublicOrProtected(field.getModifiers())) {
        assertNoJGitInternalType(field, field.getGenericType());
      }
    }
  }

  private static boolean isPublicOrProtected(int modifiers) {
    return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
  }

  private static void assertNoJGitInternalTypes(Object owner, Type[] types) {
    for (Type type : types) {
      assertNoJGitInternalType(owner, type);
    }
  }

  private static void assertNoJGitInternalType(Object owner, Type type) {
    Set<Class<?>> classes = new LinkedHashSet<>();
    collectClasses(type, classes);
    for (Class<?> clazz : classes) {
      if (clazz.getName().startsWith(JGIT_INTERNAL_PREFIX)) {
        fail("Supported public API exposes JGit internal type " + clazz.getName() + " through " + owner);
      }
    }
  }

  private static void collectClasses(Type type, Set<Class<?>> classes) {
    if (type == null) {
      return;
    }
    if (type instanceof Class<?> clazz) {
      classes.add(clazz.isArray() ? clazz.getComponentType() : clazz);
      return;
    }
    if (type instanceof ParameterizedType parameterized) {
      collectClasses(parameterized.getRawType(), classes);
      collectClasses(parameterized.getOwnerType(), classes);
      for (Type argument : parameterized.getActualTypeArguments()) {
        collectClasses(argument, classes);
      }
      return;
    }
    if (type instanceof GenericArrayType array) {
      collectClasses(array.getGenericComponentType(), classes);
      return;
    }
    if (type instanceof WildcardType wildcard) {
      for (Type bound : wildcard.getUpperBounds()) {
        collectClasses(bound, classes);
      }
      for (Type bound : wildcard.getLowerBounds()) {
        collectClasses(bound, classes);
      }
      return;
    }
    if (type instanceof TypeVariable<?> variable) {
      for (Type bound : variable.getBounds()) {
        collectClasses(bound, classes);
      }
    }
  }

  private static Path packagedJar(Path root, String module) {
    Path target = root.resolve(module).resolve("target");
    try (var files = Files.list(target)) {
      return files
          .filter(path -> path.getFileName().toString().startsWith(module + "-"))
          .filter(path -> path.getFileName().toString().endsWith(".jar"))
          .filter(path -> !path.getFileName().toString().endsWith("-sources.jar"))
          .filter(path -> !path.getFileName().toString().endsWith("-javadoc.jar"))
          .min(Comparator.comparing(Path::toString))
          .orElseThrow(() -> new AssertionError("Packaged JAR not found for " + module));
    } catch (Exception exception) {
      throw new AssertionError("Cannot inspect packaged JAR for " + module, exception);
    }
  }

  private static Path reactorRoot() {
    String configured = System.getProperty("maven.multiModuleProjectDirectory");
    if (configured != null && !configured.isBlank()) {
      return Path.of(configured).toAbsolutePath().normalize();
    }
    Path current = Path.of("").toAbsolutePath().normalize();
    assertTrue(
        current.getFileName().toString().equals("jgit-storage-hibernate-benchmarks"),
        () -> "Unexpected test working directory: " + current);
    return current.getParent();
  }
}
