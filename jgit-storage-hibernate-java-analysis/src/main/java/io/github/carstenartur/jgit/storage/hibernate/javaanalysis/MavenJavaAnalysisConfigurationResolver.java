/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Lightweight Maven model resolver for commit snapshots.
 *
 * <p>It reads parent properties, modules, source roots and dependency coordinates from POM files and
 * maps already available dependencies to the local Maven repository. It deliberately does not download
 * artifacts; unresolved coordinates remain explicit diagnostics.
 */
public final class MavenJavaAnalysisConfigurationResolver {

  public record Resolution(
      JavaAnalysisConfiguration configuration,
      List<String> sourceRoots,
      List<String> unresolvedDependencies,
      List<String> modules) {
    public Resolution {
      sourceRoots = List.copyOf(sourceRoots);
      unresolvedDependencies = List.copyOf(unresolvedDependencies);
      modules = List.copyOf(modules);
    }
  }

  private final Path localRepository;

  public MavenJavaAnalysisConfigurationResolver() {
    this(Path.of(System.getProperty("user.home"), ".m2", "repository"));
  }

  public MavenJavaAnalysisConfigurationResolver(Path localRepository) {
    this.localRepository = Objects.requireNonNull(localRepository, "localRepository");
  }

  public Resolution resolve(Map<String, String> repositoryFiles) {
    Objects.requireNonNull(repositoryFiles, "repositoryFiles");
    Set<String> sourceRoots = new LinkedHashSet<>();
    Set<String> modules = new LinkedHashSet<>();
    Set<String> classpath = new LinkedHashSet<>();
    List<String> unresolved = new ArrayList<>();
    Map<String, String> inheritedProperties = new HashMap<>();
    String sourceLevel = "21";

    List<String> pomPaths = repositoryFiles.keySet().stream()
        .filter(path -> path.equals("pom.xml") || path.endsWith("/pom.xml"))
        .sorted()
        .toList();
    for (String pomPath : pomPaths) {
      Element project = parse(repositoryFiles.get(pomPath));
      Map<String, String> properties = new HashMap<>(inheritedProperties);
      properties.putAll(readProperties(project));
      sourceLevel = firstNonBlank(
          resolve(properties.get("maven.compiler.release"), properties),
          resolve(properties.get("maven.compiler.source"), properties),
          sourceLevel);

      String moduleBase = pomPath.equals("pom.xml") ? "" : pomPath.substring(0, pomPath.length() - "pom.xml".length());
      String configuredSource = text(project, "build", "sourceDirectory");
      sourceRoots.add(normalize(moduleBase + firstNonBlank(resolve(configuredSource, properties), "src/main/java")));

      for (String module : childTexts(project, "modules", "module")) {
        modules.add(normalize(moduleBase + resolve(module, properties)));
      }
      for (Element dependency : children(project, "dependencies", "dependency")) {
        String scope = resolve(directText(dependency, "scope"), properties);
        if ("test".equals(scope) || "provided".equals(scope)) {
          continue;
        }
        String optional = resolve(directText(dependency, "optional"), properties);
        if ("true".equalsIgnoreCase(optional)) {
          continue;
        }
        String groupId = resolve(directText(dependency, "groupId"), properties);
        String artifactId = resolve(directText(dependency, "artifactId"), properties);
        String version = resolve(directText(dependency, "version"), properties);
        if (isBlank(groupId) || isBlank(artifactId) || isBlank(version)) {
          unresolved.add(coordinate(groupId, artifactId, version));
          continue;
        }
        Path jar = localRepository.resolve(groupId.replace('.', '/'))
            .resolve(artifactId).resolve(version).resolve(artifactId + "-" + version + ".jar");
        if (Files.isRegularFile(jar)) {
          classpath.add(jar.toAbsolutePath().toString());
        } else {
          unresolved.add(coordinate(groupId, artifactId, version));
        }
      }
      inheritedProperties.putAll(properties);
    }

    JavaAnalysisConfiguration configuration = new JavaAnalysisConfiguration(
        sourceLevel,
        BindingMode.RECOVERY,
        List.copyOf(classpath),
        List.of(),
        List.of(),
        List.of(),
        true,
        JavaAnalysisConfiguration.DEFAULT_ANALYZER_VERSION);
    return new Resolution(configuration, List.copyOf(sourceRoots), unresolved, List.copyOf(modules));
  }

  private static Element parse(String xml) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setNamespaceAware(false);
      return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid Maven POM", e);
    }
  }

  private static Map<String, String> readProperties(Element project) {
    Map<String, String> properties = new HashMap<>();
    Element element = directChild(project, "properties");
    if (element == null) {
      return properties;
    }
    NodeList children = element.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node node = children.item(i);
      if (node instanceof Element child) {
        properties.put(child.getTagName(), child.getTextContent().trim());
      }
    }
    return properties;
  }

  private static List<Element> children(Element root, String containerName, String childName) {
    Element container = directChild(root, containerName);
    if (container == null) {
      return List.of();
    }
    List<Element> result = new ArrayList<>();
    NodeList nodes = container.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      if (nodes.item(i) instanceof Element element && childName.equals(element.getTagName())) {
        result.add(element);
      }
    }
    return result;
  }

  private static List<String> childTexts(Element root, String containerName, String childName) {
    return children(root, containerName, childName).stream().map(Element::getTextContent).map(String::trim).toList();
  }

  private static String text(Element root, String containerName, String childName) {
    Element container = directChild(root, containerName);
    return container == null ? null : directText(container, childName);
  }

  private static String directText(Element root, String name) {
    Element child = directChild(root, name);
    return child == null ? null : child.getTextContent().trim();
  }

  private static Element directChild(Element root, String name) {
    NodeList nodes = root.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      if (nodes.item(i) instanceof Element element && name.equals(element.getTagName())) {
        return element;
      }
    }
    return null;
  }

  private static String resolve(String value, Map<String, String> properties) {
    if (value == null) {
      return null;
    }
    String result = value;
    for (int i = 0; i < 10; i++) {
      int start = result.indexOf("${");
      if (start < 0) {
        break;
      }
      int end = result.indexOf('}', start + 2);
      if (end < 0) {
        break;
      }
      String key = result.substring(start + 2, end);
      String replacement = properties.get(key);
      if (replacement == null) {
        break;
      }
      result = result.substring(0, start) + replacement + result.substring(end + 1);
    }
    return result;
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (!isBlank(value)) {
        return value;
      }
    }
    return null;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static String coordinate(String groupId, String artifactId, String version) {
    return String.valueOf(groupId) + ":" + String.valueOf(artifactId) + ":" + String.valueOf(version);
  }

  private static String normalize(String path) {
    String normalized = path.replace('\\', '/');
    while (normalized.startsWith("./")) {
      normalized = normalized.substring(2);
    }
    return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
  }
}
