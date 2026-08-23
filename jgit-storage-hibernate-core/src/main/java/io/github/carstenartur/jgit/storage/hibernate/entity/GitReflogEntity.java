/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.Nationalized;

/** Entity representing a queryable Git reflog entry stored in the database. */
@Entity
@Table(
    name = "git_reflog",
    indexes = {
      @Index(
          name = "idx_reflog_repo_ref_key_id",
          columnList = "repository_name, ref_name_key, id"),
      @Index(
          name = "idx_reflog_repo_delivery",
          columnList = "repository_name, delivery_id")
    })
public class GitReflogEntity {

  /**
   * Portable maximum for a nationalized variable-length column.
   *
   * <p>Oracle maps {@link Nationalized} strings to {@code NVARCHAR2}. With the commonly used
   * AL16UTF16 national character set, the 4,000-byte SQL limit allows at most 2,000 characters.
   */
  public static final int MAX_MESSAGE_LENGTH = 2000;

  /** Maximum persisted idempotency identifier used by durable append batches. */
  public static final int MAX_DELIVERY_ID_LENGTH = 128;

  /**
   * Indexed prefix length for portable reverse-reflog lookup.
   *
   * <p>SQL Server cannot place the complete nationalized 1,024-character ref name in a portable
   * nonclustered index key. A 128-character prefix keeps repository + prefix + identity below even
   * the older 900-byte key limit. Queries still compare the complete ref name after this selective
   * prefix predicate, so two unusually long refs sharing the prefix cannot be confused.
   */
  public static final int REF_NAME_KEY_LENGTH = 128;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Version
  @Column(name = "version")
  private Long version;

  @Nationalized
  @Column(name = "repository_name", nullable = false, length = 255)
  private String repositoryName;

  @Nationalized
  @Column(name = "delivery_id", length = MAX_DELIVERY_ID_LENGTH)
  private String deliveryId;

  @Nationalized
  @Column(name = "ref_name", nullable = false, length = 1024)
  private String refName;

  @Nationalized
  @Column(name = "ref_name_key", nullable = false, length = REF_NAME_KEY_LENGTH)
  private String refNameKey;

  @Column(name = "old_id", length = 40)
  private String oldId;

  @Column(name = "new_id", length = 40)
  private String newId;

  @Nationalized
  @Column(name = "who_name")
  private String whoName;

  @Nationalized
  @Column(name = "who_email")
  private String whoEmail;

  @Column(name = "who_when", nullable = false)
  private Instant when;

  @Nationalized
  @Column(name = "message", length = MAX_MESSAGE_LENGTH)
  private String message;

  /**
   * Produce the portable indexed prefix while retaining a full-name residual comparison.
   *
   * @param refName complete non-null Git ref name
   * @return complete name when short enough, otherwise its first 128 UTF-16 code units
   */
  public static String refNameKey(String refName) {
    Objects.requireNonNull(refName, "refName");
    return refName.length() <= REF_NAME_KEY_LENGTH
        ? refName
        : refName.substring(0, REF_NAME_KEY_LENGTH);
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getVersion() {
    return version;
  }

  public void setVersion(Long version) {
    this.version = version;
  }

  public String getRepositoryName() {
    return repositoryName;
  }

  public void setRepositoryName(String repositoryName) {
    this.repositoryName = repositoryName;
  }

  public String getDeliveryId() {
    return deliveryId;
  }

  public void setDeliveryId(String deliveryId) {
    this.deliveryId = deliveryId;
  }

  public String getRefName() {
    return refName;
  }

  public void setRefName(String refName) {
    this.refName = Objects.requireNonNull(refName, "refName");
    refNameKey = refNameKey(refName);
  }

  public String getRefNameKey() {
    return refNameKey;
  }

  /**
   * Set the persisted prefix explicitly for schema-adoption and focused tests.
   *
   * @param refNameKey non-null prefix matching {@link #refNameKey(String)}
   */
  public void setRefNameKey(String refNameKey) {
    this.refNameKey = Objects.requireNonNull(refNameKey, "refNameKey");
  }

  public String getOldId() {
    return oldId;
  }

  public void setOldId(String oldId) {
    this.oldId = oldId;
  }

  public String getNewId() {
    return newId;
  }

  public void setNewId(String newId) {
    this.newId = newId;
  }

  public String getWhoName() {
    return whoName;
  }

  public void setWhoName(String whoName) {
    this.whoName = whoName;
  }

  public String getWhoEmail() {
    return whoEmail;
  }

  public void setWhoEmail(String whoEmail) {
    this.whoEmail = whoEmail;
  }

  public Instant getWhen() {
    return when;
  }

  public void setWhen(Instant when) {
    this.when = when;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }
}
