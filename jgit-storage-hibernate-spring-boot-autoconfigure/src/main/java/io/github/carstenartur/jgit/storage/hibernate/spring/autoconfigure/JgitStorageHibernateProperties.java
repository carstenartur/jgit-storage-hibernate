/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.spring.autoconfigure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** External configuration for the dedicated jgit-storage-hibernate persistence context. */
@ConfigurationProperties("jgit.storage.hibernate")
public class JgitStorageHibernateProperties {

  /** Schema action performed before the dedicated Hibernate SessionFactory is created. */
  public enum SchemaAction {
    /** Apply every pending module-owned Flyway migration. */
    MIGRATE,
    /** Validate migration history without applying changes. */
    VALIDATE,
    /** Leave schema lifecycle entirely to the application. */
    NONE
  }

  private boolean enabled = true;
  private SchemaAction schemaAction = SchemaAction.MIGRATE;
  private boolean baselineOnMigrate;
  private final Search search = new Search();
  private final Map<String, String> hibernateProperties = new LinkedHashMap<>();
  private final List<String> repositories = new ArrayList<>();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public SchemaAction getSchemaAction() {
    return schemaAction;
  }

  public void setSchemaAction(SchemaAction schemaAction) {
    this.schemaAction = schemaAction;
  }

  public boolean isBaselineOnMigrate() {
    return baselineOnMigrate;
  }

  public void setBaselineOnMigrate(boolean baselineOnMigrate) {
    this.baselineOnMigrate = baselineOnMigrate;
  }

  public Search getSearch() {
    return search;
  }

  public Map<String, String> getHibernateProperties() {
    return hibernateProperties;
  }

  public List<String> getRepositories() {
    return repositories;
  }

  /** Optional generic Git-history projection configuration. */
  public static class Search {

    private boolean enabled;
    private String directory = "./target/jgit-storage-search";

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getDirectory() {
      return directory;
    }

    public void setDirectory(String directory) {
      this.directory = directory;
    }
  }
}
