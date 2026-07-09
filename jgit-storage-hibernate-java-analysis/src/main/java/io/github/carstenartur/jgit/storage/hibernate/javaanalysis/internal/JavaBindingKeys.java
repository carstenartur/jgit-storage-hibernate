/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis.internal;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.BindingStatus;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;

/** Converts JDT binding objects to persistable, tool-independent fields. */
public final class JavaBindingKeys {

  private JavaBindingKeys() {}

  public static BindingStatus status(IBinding binding, boolean bindingRequested) {
    if (!bindingRequested) {
      return BindingStatus.NONE;
    }
    if (binding == null) {
      return BindingStatus.PARTIAL;
    }
    return binding.isRecovered() ? BindingStatus.RECOVERED : BindingStatus.FULL;
  }

  public static String rawKey(IBinding binding) {
    return binding == null ? null : binding.getKey();
  }

  public static String declarationKey(IBinding binding) {
    if (binding instanceof ITypeBinding typeBinding) {
      return rawKey(typeBinding.getTypeDeclaration());
    }
    if (binding instanceof IMethodBinding methodBinding) {
      return rawKey(methodBinding.getMethodDeclaration());
    }
    if (binding instanceof IVariableBinding variableBinding) {
      return rawKey(variableBinding.getVariableDeclaration());
    }
    return rawKey(binding);
  }

  public static String declaringTypeKey(IBinding binding) {
    if (binding instanceof ITypeBinding typeBinding) {
      ITypeBinding declaringClass = typeBinding.getDeclaringClass();
      return rawKey(declaringClass == null ? null : declaringClass.getTypeDeclaration());
    }
    if (binding instanceof IMethodBinding methodBinding) {
      ITypeBinding declaringClass = methodBinding.getDeclaringClass();
      return rawKey(declaringClass == null ? null : declaringClass.getTypeDeclaration());
    }
    if (binding instanceof IVariableBinding variableBinding) {
      ITypeBinding declaringClass = variableBinding.getDeclaringClass();
      return rawKey(declaringClass == null ? null : declaringClass.getTypeDeclaration());
    }
    return null;
  }

  public static String typeKey(IBinding binding) {
    if (binding instanceof ITypeBinding typeBinding) {
      return rawKey(typeBinding.getTypeDeclaration());
    }
    if (binding instanceof IMethodBinding methodBinding) {
      ITypeBinding returnType = methodBinding.getReturnType();
      return rawKey(returnType == null ? null : returnType.getTypeDeclaration());
    }
    if (binding instanceof IVariableBinding variableBinding) {
      ITypeBinding type = variableBinding.getType();
      return rawKey(type == null ? null : type.getTypeDeclaration());
    }
    return null;
  }

  public static String stableKey(String kind, String qualifiedName, String signature) {
    return kind + ":" + Objects.toString(qualifiedName, "") + ":" + Objects.toString(signature, "");
  }

  public static String stableTypeKey(ITypeBinding binding, String syntaxQualifiedName) {
    if (binding == null) {
      return stableKey("TYPE", syntaxQualifiedName, null);
    }
    ITypeBinding declaration = binding.getTypeDeclaration();
    String qualifiedName = declaration.getQualifiedName();
    return stableKey("TYPE", qualifiedName.isBlank() ? syntaxQualifiedName : qualifiedName, null);
  }

  public static String stableMethodKey(IMethodBinding binding, String syntaxQualifiedName) {
    if (binding == null) {
      return stableKey("METHOD", syntaxQualifiedName, null);
    }
    IMethodBinding declaration = binding.getMethodDeclaration();
    String declaringType = qualifiedName(declaration.getDeclaringClass());
    String parameters =
        Arrays.stream(declaration.getParameterTypes())
            .map(JavaBindingKeys::qualifiedName)
            .collect(Collectors.joining(","));
    return stableKey("METHOD", declaringType + "#" + declaration.getName(), "(" + parameters + ")");
  }

  public static String stableVariableKey(IVariableBinding binding, String syntaxQualifiedName) {
    if (binding == null) {
      return stableKey("FIELD", syntaxQualifiedName, null);
    }
    IVariableBinding declaration = binding.getVariableDeclaration();
    String owner = declaration.isField() ? qualifiedName(declaration.getDeclaringClass()) : "<local>";
    return stableKey("FIELD", owner + "#" + declaration.getName(), qualifiedName(declaration.getType()));
  }

  public static String qualifiedName(ITypeBinding binding) {
    if (binding == null) {
      return null;
    }
    ITypeBinding declaration = binding.getTypeDeclaration();
    String qualifiedName = declaration.getQualifiedName();
    if (!qualifiedName.isBlank()) {
      return qualifiedName;
    }
    return declaration.getName();
  }

  public static String parameterTypes(IMethodBinding binding) {
    if (binding == null) {
      return null;
    }
    return Arrays.stream(binding.getParameterTypes())
        .map(JavaBindingKeys::qualifiedName)
        .collect(Collectors.joining(","));
  }
}
