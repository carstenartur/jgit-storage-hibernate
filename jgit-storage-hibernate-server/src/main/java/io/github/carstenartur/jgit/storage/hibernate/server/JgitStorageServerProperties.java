/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.server;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Standalone server authentication, indexing and inspection settings. */
@Validated
@ConfigurationProperties("jgit.storage.server")
public class JgitStorageServerProperties {

  @Valid private final Authentication authentication = new Authentication();

  @Min(1)
  @Max(16)
  private int indexingThreads = 2;

  private boolean inspectionViewsEnabled = true;

  public Authentication getAuthentication() {
    return authentication;
  }

  public int getIndexingThreads() {
    return indexingThreads;
  }

  public void setIndexingThreads(int indexingThreads) {
    this.indexingThreads = indexingThreads;
  }

  public boolean isInspectionViewsEnabled() {
    return inspectionViewsEnabled;
  }

  public void setInspectionViewsEnabled(boolean inspectionViewsEnabled) {
    this.inspectionViewsEnabled = inspectionViewsEnabled;
  }

  /** Basic authentication used by the initial standalone admin deployment. */
  public static class Authentication {

    @NotBlank private String username = "admin";
    @NotBlank private String password;
    private boolean requireSecureTransport;

    public String getUsername() {
      return username;
    }

    public void setUsername(String username) {
      this.username = username;
    }

    public String getPassword() {
      return password;
    }

    public void setPassword(String password) {
      this.password = password;
    }

    public boolean isRequireSecureTransport() {
      return requireSecureTransport;
    }

    public void setRequireSecureTransport(boolean requireSecureTransport) {
      this.requireSecureTransport = requireSecureTransport;
    }
  }
}
