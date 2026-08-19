/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityAccessTokenEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityGroupEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityGroupMembershipEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityRefRuleEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityRepositoryGrantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.Test;

class SecurityPersistenceMetadataContractTest {

  private static final String INVENTORY_RESOURCE = "/security-persistence-metadata.tsv";
  private static final String HEADER =
      "entity_class\ttable_name\towner_module\tcategory\tmutability\tactor_policy"
          + "\ttimestamp_policy\tretention\tcompliance\tnote";
  private static final Pattern SECURITY_TABLE_REFERENCE =
      Pattern.compile("\\bgit_[a-z0-9_]+\\b", Pattern.CASE_INSENSITIVE);

  @Test
  void inventoryClassifiesEverySecurityEntityExactlyOnceAndMatchesJpaTables() throws Exception {
    List<Entry> entries = inventory();
    List<Class<?>> classifiedTypes = entries.stream().map(Entry::entityType).toList();
    assertEquals(SecurityEntities.annotatedClasses(), classifiedTypes);

    Map<Class<?>, Entry> byType = new LinkedHashMap<>();
    for (Entry entry : entries) {
      assertNull(byType.put(entry.entityType(), entry), () -> "duplicate inventory entry " + entry);
      Table table = entry.entityType().getAnnotation(Table.class);
      assertNotNull(table, () -> entry.entityType().getName() + " must declare @Table");
      assertEquals(table.name(), entry.tableName());
      assertEquals("security", entry.ownerModule());
      assertTrue(entry.tableName().startsWith("git_security_"));
      assertFalse(entry.note().isBlank());
    }
  }

  @Test
  void appendOnlyAuditEntitiesAreImmutableAndHaveNoOptimisticLockField() throws Exception {
    for (Entry entry : inventory()) {
      if (!"append_only_audit".equals(entry.category())) {
        continue;
      }
      assertNotNull(
          entry.entityType().getAnnotation(Immutable.class),
          () -> entry.entityType().getName() + " must remain Hibernate @Immutable");
      assertTrue(
          Arrays.stream(entry.entityType().getDeclaredFields())
              .noneMatch(field -> field.isAnnotationPresent(Version.class)),
          () -> entry.entityType().getName() + " must not expose mutable event versioning");
      assertEquals("append_only", entry.mutability());
      assertEquals("event_actor_fields", entry.actorPolicy());
      assertEquals("occurred_at", entry.timestampPolicy());
      assertEquals("security_audit", entry.retention());
      assertEquals("compliant", entry.compliance());
    }
  }

  @Test
  void ambiguousLegacyActorColumnsRemainExplicitMigrationGaps() throws Exception {
    for (Entry entry : inventory()) {
      for (Field field : entry.entityType().getDeclaredFields()) {
        Column column = field.getAnnotation(Column.class);
        if (column == null || column.name().isBlank()) {
          continue;
        }
        String name = column.name().toLowerCase(Locale.ROOT);
        if (!actorLike(name)) {
          continue;
        }
        assertFalse(name.contains("username"), () -> "actor column must not use username: " + name);
        assertFalse(name.contains("login"), () -> "actor column must not use login name: " + name);
        assertFalse(name.contains("email"), () -> "actor column must not use email: " + name);
        if (!name.endsWith("_principal_id") && !"principal_id".equals(name)) {
          assertEquals(
              "migration_required",
              entry.compliance(),
              () ->
                  entry.entityType().getSimpleName()
                      + "."
                      + field.getName()
                      + " uses ambiguous actor column "
                      + name);
        }
      }
    }
  }

  @Test
  void currentMigrationGapSetIsExplicitAndMustShrinkOnlyWithSchemaAndApiChanges()
      throws Exception {
    Set<Class<?>> migrationRequired = new LinkedHashSet<>();
    for (Entry entry : inventory()) {
      if ("migration_required".equals(entry.compliance())) {
        migrationRequired.add(entry.entityType());
      }
    }
    assertEquals(
        Set.of(
            SecurityPrincipalEntity.class,
            SecurityGroupEntity.class,
            SecurityGroupMembershipEntity.class,
            SecurityRepositoryGrantEntity.class,
            SecurityRefRuleEntity.class,
            SecurityAccessTokenEntity.class),
        migrationRequired);
  }

  @Test
  void securityMigrationsNeverReferenceCoreOwnedGitTables() throws IOException {
    Path migrationRoot =
        Path.of("src/main/resources/db/migration/jgit-storage-hibernate/security");
    assertTrue(Files.isDirectory(migrationRoot), "Security migration root must exist");

    int sqlFiles = 0;
    try (Stream<Path> paths = Files.walk(migrationRoot)) {
      for (Path path :
          paths.filter(Files::isRegularFile)
              .filter(candidate -> candidate.getFileName().toString().endsWith(".sql"))
              .sorted()
              .toList()) {
        sqlFiles++;
        Matcher matcher = SECURITY_TABLE_REFERENCE.matcher(Files.readString(path));
        while (matcher.find()) {
          String table = matcher.group().toLowerCase(Locale.ROOT);
          assertTrue(
              table.startsWith("git_security_"),
              () -> path + " references non-Security table or object " + table);
        }
      }
    }
    assertTrue(sqlFiles >= 16, "all four supported dialect migration streams must be scanned");
  }

  private static boolean actorLike(String columnName) {
    return columnName.contains("actor")
        || columnName.startsWith("created_by")
        || columnName.startsWith("updated_by")
        || columnName.startsWith("issued_by")
        || columnName.startsWith("revoked_by");
  }

  private static List<Entry> inventory() throws Exception {
    InputStream input =
        SecurityPersistenceMetadataContractTest.class.getResourceAsStream(INVENTORY_RESOURCE);
    assertNotNull(input, "Security persistence metadata inventory must be packaged");
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
      List<String> lines = reader.lines().toList();
      assertFalse(lines.isEmpty());
      assertEquals(HEADER, lines.getFirst());
      return lines.subList(1, lines.size()).stream()
          .filter(line -> !line.isBlank())
          .map(SecurityPersistenceMetadataContractTest::entry)
          .toList();
    }
  }

  private static Entry entry(String line) {
    String[] values = line.split("\\t", -1);
    assertEquals(10, values.length, () -> "invalid inventory row: " + line);
    try {
      return new Entry(
          Class.forName(values[0]),
          values[1],
          values[2],
          values[3],
          values[4],
          values[5],
          values[6],
          values[7],
          values[8],
          values[9]);
    } catch (ClassNotFoundException failure) {
      throw new AssertionError("inventory references missing entity " + values[0], failure);
    }
  }

  private record Entry(
      Class<?> entityType,
      String tableName,
      String ownerModule,
      String category,
      String mutability,
      String actorPolicy,
      String timestampPolicy,
      String retention,
      String compliance,
      String note) {}
}
