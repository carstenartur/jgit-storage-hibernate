/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class PublicApiBoundaryTest {

  private static final String BASE_PACKAGE = "io.github.carstenartur.jgit.storage.hibernate";
  private static final String FORBIDDEN_JGIT_INTERNAL_PREFIX = "org.eclipse.jgit.internal.";
  private static final Set<String> REQUIRED_INTERNAL_PACKAGES =
      Set.of(BASE_PACKAGE + ".objects", BASE_PACKAGE + ".refs", BASE_PACKAGE + ".repository");

  @Test
  void supportedPublicApiDoesNotExposeJGitInternalTypes() throws Exception {
    Path classesRoot =
        Path.of(HibernateGitStorage.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    Path baseDirectory = classesRoot.resolve(BASE_PACKAGE.replace('.', '/'));

    assertTrue(
        Files.isDirectory(baseDirectory),
        () -> "Public API class directory does not exist: " + baseDirectory);

    List<Class<?>> publicClasses = publicClasses(classesRoot, baseDirectory);
    for (String packageName : REQUIRED_INTERNAL_PACKAGES) {
      assertTrue(
          publicClasses.stream()
              .map(Class::getPackage)
              .filter(java.util.Objects::nonNull)
              .anyMatch(pack -> pack.getName().equals(packageName) && isInternal(pack)),
          () -> "Expected implementation package is not marked @InternalApi: " + packageName);
    }

    for (Class<?> apiClass : publicClasses) {
      if (!isInternal(apiClass) && !isInternal(apiClass.getPackage())) {
        assertClassBoundary(apiClass);
      }
    }
  }

  private static List<Class<?>> publicClasses(Path classesRoot, Path baseDirectory) throws Exception {
    try (Stream<Path> classFiles = Files.walk(baseDirectory)) {
      return classFiles
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".class"))
          .filter(path -> !path.getFileName().toString().contains("$"))
          .filter(path -> !path.getFileName().toString().equals("package-info.class"))
          .<Class<?>>map(path -> loadClass(classesRoot, path))
          .filter(clazz -> Modifier.isPublic(clazz.getModifiers()))
          .toList();
    }
  }

  private static Class<?> loadClass(Path classesRoot, Path classFile) {
    String className =
        classesRoot
            .relativize(classFile)
            .toString()
            .replace(java.io.File.separatorChar, '.')
            .replaceAll("\\.class$", "");
    try {
      return Class.forName(className);
    } catch (ClassNotFoundException exception) {
      throw new AssertionError("Could not load public API class " + className, exception);
    }
  }

  private static boolean isInternal(Class<?> type) {
    return type != null && type.isAnnotationPresent(InternalApi.class);
  }

  private static boolean isInternal(Package packageObject) {
    return packageObject != null && packageObject.isAnnotationPresent(InternalApi.class);
  }

  private static void assertClassBoundary(Class<?> apiClass) {
    for (Constructor<?> constructor : apiClass.getDeclaredConstructors()) {
      if (isPublicOrProtected(constructor.getModifiers())) {
        assertNoForbiddenTypes(constructor, constructor.getGenericParameterTypes());
        assertNoForbiddenTypes(constructor, constructor.getGenericExceptionTypes());
      }
    }

    for (Method method : apiClass.getDeclaredMethods()) {
      if (isPublicOrProtected(method.getModifiers())) {
        assertNoForbiddenType(method, method.getGenericReturnType());
        assertNoForbiddenTypes(method, method.getGenericParameterTypes());
        assertNoForbiddenTypes(method, method.getGenericExceptionTypes());
      }
    }

    for (Field field : apiClass.getDeclaredFields()) {
      if (isPublicOrProtected(field.getModifiers())) {
        assertNoForbiddenType(field, field.getGenericType());
      }
    }
  }

  private static boolean isPublicOrProtected(int modifiers) {
    return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
  }

  private static void assertNoForbiddenTypes(Object owner, Type[] types) {
    for (Type type : types) {
      assertNoForbiddenType(owner, type);
    }
  }

  private static void assertNoForbiddenType(Object owner, Type type) {
    if (type == null) {
      return;
    }
    if (type instanceof Class<?> clazz) {
      assertNoForbiddenClass(owner, clazz);
      return;
    }
    if (type instanceof ParameterizedType parameterizedType) {
      assertNoForbiddenType(owner, parameterizedType.getRawType());
      assertNoForbiddenType(owner, parameterizedType.getOwnerType());
      assertNoForbiddenTypes(owner, parameterizedType.getActualTypeArguments());
      return;
    }
    if (type instanceof GenericArrayType genericArrayType) {
      assertNoForbiddenType(owner, genericArrayType.getGenericComponentType());
      return;
    }
    if (type instanceof WildcardType wildcardType) {
      assertNoForbiddenTypes(owner, wildcardType.getUpperBounds());
      assertNoForbiddenTypes(owner, wildcardType.getLowerBounds());
      return;
    }
    if (type instanceof TypeVariable<?> typeVariable) {
      assertNoForbiddenTypes(owner, typeVariable.getBounds());
    }
  }

  private static void assertNoForbiddenClass(Object owner, Class<?> clazz) {
    if (clazz.isArray()) {
      assertNoForbiddenClass(owner, clazz.getComponentType());
      return;
    }
    if (clazz.getName().startsWith(FORBIDDEN_JGIT_INTERNAL_PREFIX)) {
      fail("Supported public API exposes JGit internal type " + clazz.getName() + " through " + owner);
    }
  }
}
