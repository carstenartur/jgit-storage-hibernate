/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateStorageSettings;
import io.github.carstenartur.jgit.storage.hibernate.transaction.PackPublicationPolicySnapshot;
import io.github.carstenartur.jgit.storage.hibernate.transaction.PackPublicationPolicySnapshot.Path;
import io.github.carstenartur.jgit.storage.hibernate.transaction.PackPublicationPolicySnapshot.Reason;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.JdbcSettings;
import org.hibernate.cfg.MappingSettings;
import org.hibernate.engine.spi.SessionFactoryImplementor;

/** Chooses a transaction shape for an already staged logical pack. */
final class PackPublicationSelector {

  static final long MIN_PREPERSIST_PAYLOAD_BYTES =
      HibernateObjDatabase.INLINE_PAYLOAD_THRESHOLD + 1L;
  private static final int HIGH_SAMPLES = 3;
  private static final int QUIET_SAMPLES = 8;
  private static final long HIGH_LOCK_NANOS = TimeUnit.MILLISECONDS.toNanos(2);
  private static final long IDLE_NANOS = TimeUnit.MINUTES.toNanos(1);
  private static final List<String> JDBC_URL_SETTINGS =
      List.of(
          JdbcSettings.JAKARTA_JDBC_URL,
          "javax.persistence.jdbc.url",
          "hibernate.connection.url",
          "hibernate.hikari.jdbcUrl");
  private static final List<String> JDBC_IDENTITY_SETTINGS =
      List.of(
          JdbcSettings.JAKARTA_JDBC_DRIVER,
          "javax.persistence.jdbc.driver",
          "hibernate.connection.driver_class",
          "hibernate.hikari.driverClassName",
          JdbcSettings.JAKARTA_JDBC_USER,
          "javax.persistence.jdbc.user",
          "hibernate.connection.username",
          "hibernate.hikari.username",
          MappingSettings.DEFAULT_CATALOG,
          MappingSettings.DEFAULT_SCHEMA,
          "hibernate.hikari.catalog",
          "hibernate.hikari.schema");
  private static final Set<String> JDBC_NON_IDENTITY_SETTINGS =
      Set.of(
          "hibernate.connection.password",
          "hibernate.connection.autocommit",
          "hibernate.connection.isolation",
          "hibernate.connection.pool_size",
          "hibernate.connection.initial_pool_size",
          "hibernate.connection.min_pool_size",
          "hibernate.connection.pool_validation_interval",
          "hibernate.connection.provider_disables_autocommit",
          "hibernate.connection.handling_mode",
          "hibernate.connection.login_timeout");
  private static final List<String> INDIRECT_CONNECTION_SETTINGS =
      List.of(
          "hibernate.connection.datasource",
          JdbcSettings.JAKARTA_JTA_DATASOURCE,
          JdbcSettings.JAKARTA_NON_JTA_DATASOURCE,
          "javax.persistence.jtaDataSource",
          "javax.persistence.nonJtaDataSource",
          "hibernate.hikari.dataSource",
          "hibernate.hikari.dataSourceClassName",
          "hibernate.hikari.dataSourceJNDI",
          "hibernate.hikari.connectionInitSql",
          JdbcSettings.CONNECTION_PROVIDER);

  private final String mode;
  private final boolean metricsEnabled;
  private final long sizeThreshold;
  private final Key key;
  private final Stripe stripe;
  private volatile Decision lastDecision;

  static PackPublicationSelector configured(
      SessionFactory factory, String repositoryName, boolean metricsEnabled) {
    Map<String, Object> settings = factory.getProperties();
    String mode = HibernateStorageSettings.resolvePackPublicationSelector(settings);
    Key key =
        metricsEnabled
                && HibernateStorageSettings.CONTENTION_AWARE_PUBLICATION_SELECTOR.equals(mode)
            ? key(
                settings,
                repositoryName,
                () -> factory.unwrap(SessionFactoryImplementor.class).getUuid())
            : null;
    return new PackPublicationSelector(
        mode,
        metricsEnabled,
        HibernateStorageSettings.resolvePackPrepersistMinPayloadBytes(settings),
        key);
  }

  PackPublicationSelector(String mode, boolean metricsEnabled, long sizeThreshold, Key key) {
    if (sizeThreshold < MIN_PREPERSIST_PAYLOAD_BYTES) {
      throw new IllegalArgumentException(
          HibernateStorageSettings.PACK_PREPERSIST_MIN_PAYLOAD_BYTES
              + " must be at least "
              + MIN_PREPERSIST_PAYLOAD_BYTES);
    }
    this.mode = mode;
    this.metricsEnabled = metricsEnabled;
    this.sizeThreshold = sizeThreshold;
    this.key = key;
    this.stripe = key == null ? null : Stripes.forKey(key);
    this.lastDecision =
        new Decision(
            Path.NOT_SELECTED, metricsEnabled ? Reason.NO_SELECTION : Reason.METRICS_DISABLED);
  }

  static Key key(Map<String, ?> settings, String repositoryName, Supplier<String> factoryUuid) {
    Map<String, String> identity = new HashMap<>();
    copyIdentitySettings(settings, identity, JDBC_URL_SETTINGS);
    // Providers, driver properties and initialization SQL can change the endpoint or schema
    // without changing the URL. Scope these observations to a factory without retaining it.
    if (identity.isEmpty()
        || INDIRECT_CONNECTION_SETTINGS.stream().anyMatch(settings::containsKey)
        || settings.keySet().stream()
            .anyMatch(PackPublicationSelector::unresolvedConnectionSetting)) {
      return new Key(Map.of("factory", factoryUuid.get()), repositoryName);
    }
    // Compare all configured values. A pool may override the JDBC URL or connection schema,
    // independently of Hibernate's configured schema for qualified table names.
    copyIdentitySettings(settings, identity, JDBC_IDENTITY_SETTINGS);
    return new Key(identity, repositoryName);
  }

  private static boolean unresolvedConnectionSetting(String name) {
    return name.startsWith("hibernate.hikari.dataSource.")
        || (name.startsWith("hibernate.connection.")
            && !JDBC_URL_SETTINGS.contains(name)
            && !JDBC_IDENTITY_SETTINGS.contains(name)
            && !JDBC_NON_IDENTITY_SETTINGS.contains(name));
  }

  private static void copyIdentitySettings(
      Map<String, ?> settings, Map<String, String> identity, List<String> names) {
    for (String name : names) {
      Object value = settings.get(name);
      if (value instanceof String text && !text.isBlank()) {
        identity.put(name, text);
      }
    }
  }

  boolean adaptive() {
    return stripe != null;
  }

  long sizeThreshold() {
    return sizeThreshold;
  }

  Observation begin(long payloadBytes, long now) {
    return stripe.begin(key, payloadBytes, now);
  }

  boolean prePersist(Reason exclusion, long payloadBytes, boolean nested, Observation observation) {
    boolean hot = observation != null && observation.hot;
    boolean prePersisted = exclusion == null && payloadBytes >= threshold(hot);
    if (metricsEnabled) {
      lastDecision =
          new Decision(
              prePersisted ? Path.PRE_PERSISTED : Path.DIRECT,
              reason(exclusion, nested, observation));
    }
    return prePersisted;
  }

  private Reason reason(Reason exclusion, boolean nested, Observation observation) {
    if (exclusion != null) {
      return exclusion;
    }
    if (!adaptive()) {
      return Reason.SIZE_THRESHOLD;
    }
    if (nested) {
      return Reason.NESTED_TRANSACTION;
    }
    if (observation == null) {
      return Reason.STRIPE_BUSY;
    }
    if (observation.hot) {
      return Reason.SUSTAINED_CONTENTION;
    }
    return observation.samples < HIGH_SAMPLES ? Reason.INSUFFICIENT_SAMPLES : Reason.SIZE_THRESHOLD;
  }

  private long threshold(boolean hot) {
    return hot ? MIN_PREPERSIST_PAYLOAD_BYTES : sizeThreshold;
  }

  PackPublicationPolicySnapshot snapshot(long now) {
    Decision decision = lastDecision;
    Signal signal = adaptive() ? stripe.snapshot(key, now) : Signal.EMPTY;
    return new PackPublicationPolicySnapshot(
        mode,
        decision.path(),
        decision.reason(),
        sizeThreshold,
        threshold(signal.hot()),
        signal.samples(),
        signal.lockNanos(),
        signal.durationNanos(),
        signal.queued(),
        signal.payloadBytes());
  }

  record Key(Map<String, String> connectionIdentity, String repository) {
    Key {
      connectionIdentity = Map.copyOf(connectionIdentity);
    }
  }

  private record Decision(Path path, Reason reason) {}

  private record Signal(
      boolean hot,
      int samples,
      long lockNanos,
      long durationNanos,
      double queued,
      long payloadBytes) {
    private static final Signal EMPTY = new Signal(false, 0, 0, 0, 0, 0);
  }

  private static final class Stripes {
    private static final Stripe[] SLOTS = new Stripe[256];

    static {
      Arrays.setAll(SLOTS, index -> new Stripe());
    }

    private static Stripe forKey(Key key) {
      int hash = key.hashCode();
      return SLOTS[(hash ^ (hash >>> 16)) & (SLOTS.length - 1)];
    }
  }

  /** One in-flight eligible publication pins its stripe's owner until it finishes. */
  static final class Observation implements AutoCloseable {
    private final Stripe stripe;
    private final long payloadBytes;
    private final long started;
    private final boolean hot;
    private final int samples;
    private int queued;
    private long lockNanos = -1;

    private Observation(Stripe stripe, long payloadBytes, long started, boolean hot, int samples) {
      this.stripe = stripe;
      this.payloadBytes = payloadBytes;
      this.started = started;
      this.hot = hot;
      this.samples = samples;
    }

    void beforeLock() {
      queued = stripe.enterLockAttempt();
    }

    void lockAcquired(long nanos) {
      lockNanos = nanos;
      queued = Math.max(queued, stripe.otherLockAttempts());
    }

    void afterLock() {
      stripe.leaveLockAttempt();
    }

    void committed(long now) {
      if (lockNanos >= 0) {
        stripe.record(lockNanos, now - started, queued, payloadBytes, now);
      }
    }

    @Override
    public void close() {
      stripe.end();
    }
  }

  /** Contains at most one repository's recent statistics; no monitor is held during I/O. */
  private static final class Stripe {
    private Key key;
    private int inFlight;
    private int lockAttempts;
    private int samples;
    private int highStreak;
    private int quietStreak;
    private boolean hot;
    private long lastCompleted;
    private double waitNanos;
    private double durationNanos;
    private double queuedWriters;
    private double payloadBytes;

    synchronized Observation begin(Key requested, long bytes, long now) {
      if (!requested.equals(key)) {
        if (inFlight != 0) {
          return null;
        }
        reset(requested);
      } else if (stale(now)) {
        reset(requested);
      }
      inFlight++;
      return new Observation(this, bytes, now, hot, samples);
    }

    synchronized void end() {
      inFlight--;
    }

    synchronized int enterLockAttempt() {
      return lockAttempts++;
    }

    synchronized int otherLockAttempts() {
      return lockAttempts - 1;
    }

    synchronized void leaveLockAttempt() {
      lockAttempts--;
    }

    synchronized void record(long wait, long duration, int queued, long payload, long now) {
      waitNanos = smooth(waitNanos, wait);
      durationNanos = smooth(durationNanos, duration);
      queuedWriters = smooth(queuedWriters, queued);
      payloadBytes = smooth(payloadBytes, payload);
      if (samples == 0 || now - lastCompleted > 0) {
        lastCompleted = now;
      }
      if (samples < Integer.MAX_VALUE) {
        samples++;
      }

      if (hot) {
        // Once the shorter lock interval works, keep the hot decision while local writers overlap.
        quietStreak = queued == 0 && queuedWriters < 0.5 ? quietStreak + 1 : 0;
        if (quietStreak == QUIET_SAMPLES) {
          hot = false;
          highStreak = 0;
        }
      } else {
        // A slow DB round trip without a competing local writer is not evidence of contention.
        boolean high =
            queued > 0
                && queuedWriters >= 0.5
                && wait >= Math.max(HIGH_LOCK_NANOS, duration / 8.0)
                && waitNanos >= Math.max(HIGH_LOCK_NANOS, durationNanos / 8.0);
        highStreak = high ? highStreak + 1 : 0;
        if (highStreak == HIGH_SAMPLES) {
          hot = true;
          quietStreak = 0;
        }
      }
    }

    synchronized Signal snapshot(Key requested, long now) {
      return !requested.equals(key) || stale(now)
          ? Signal.EMPTY
          : new Signal(
              hot,
              samples,
              Math.round(waitNanos),
              Math.round(durationNanos),
              queuedWriters,
              Math.round(payloadBytes));
    }

    private double smooth(double average, long sample) {
      return samples == 0 ? sample : average + (sample - average) / 4.0;
    }

    private boolean stale(long now) {
      return inFlight == 0 && samples > 0 && now - lastCompleted >= IDLE_NANOS;
    }

    private void reset(Key requested) {
      key = requested;
      samples = 0;
      highStreak = 0;
      quietStreak = 0;
      hot = false;
      waitNanos = 0;
      durationNanos = 0;
      queuedWriters = 0;
      payloadBytes = 0;
    }
  }
}
