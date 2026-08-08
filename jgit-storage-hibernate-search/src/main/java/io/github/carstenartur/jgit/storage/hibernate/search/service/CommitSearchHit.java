/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search.service;

import java.time.Instant;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.FieldProjection;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.ProjectionConstructor;

/**
 * Lightweight summary of one matching commit.
 *
 * <p>Full-text searches construct this record directly from projectable Lucene fields, avoiding a
 * relational entity load and, in particular, avoiding materialization of the potentially large
 * changed-path and changed-text columns. Structured relational searches use the same record through
 * an HQL constructor projection.
 *
 * @param objectId Git object ID
 * @param shortMessage commit subject
 * @param authorName original author name
 * @param authorEmail original author email
 * @param committerName committer name
 * @param committerEmail committer email
 * @param authorTime original author timestamp
 * @param committerTime timestamp at which the commit entered the current history
 */
@ProjectionConstructor
public record CommitSearchHit(
    @FieldProjection(path = "objectId") String objectId,
    @FieldProjection(path = "shortMessage") String shortMessage,
    @FieldProjection(path = "authorName") String authorName,
    @FieldProjection(path = "authorEmail") String authorEmail,
    @FieldProjection(path = "committerName") String committerName,
    @FieldProjection(path = "committerEmail") String committerEmail,
    @FieldProjection(path = "authorTime") Instant authorTime,
    @FieldProjection(path = "committerTime") Instant committerTime) {}
