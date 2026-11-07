/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** One fixed pack-chunk window read on a disposable database, outside benchmark timing. */
final class DatabaseTelemetryFocusedRead {

  static final String FIXTURE_TABLE = "focused_pack_chunk_fixture";

  private static final int STATEMENT_TIMEOUT_SECONDS = 4;
  private static final int POSTGRESQL_STATEMENT_TIMEOUT_MILLIS = 3_000;
  private static final int LOCK_TIMEOUT_MILLIS = 2_000;
  private static final int MAX_PLAN_CHARACTERS = 64 * 1024;
  private static final Pattern NON_NEGATIVE_NUMBER =
      Pattern.compile("[0-9]{1,16}(?:\\.[0-9]{1,6})?");
  private static final Pattern SERVER_VERSION = Pattern.compile("[0-9]{1,4}(?:\\.[0-9]{1,8}){1,3}");
  private static final String WINDOW_READ =
      "SELECT chunk_index, chunk_data, chunk_size FROM "
          + FIXTURE_TABLE
          + " WHERE pack_id = ? AND chunk_index BETWEEN ? AND ? ORDER BY chunk_index";

  private DatabaseTelemetryFocusedRead() {}

  enum Backend {
    POSTGRESQL("postgresql", "http://www.postgresql.org/2009/explain", "explain"),
    SQLSERVER(
        "sqlserver", "http://schemas.microsoft.com/sqlserver/2004/07/showplan", "ShowPlanXML");

    private final String id;
    private final String namespace;
    private final String root;

    Backend(String id, String namespace, String root) {
      this.id = id;
      this.namespace = namespace;
      this.root = root;
    }

    Connection connect(String url, String user, String password) throws SQLException {
      Properties properties = new Properties();
      properties.setProperty("user", user);
      properties.setProperty("password", password);
      if (this == POSTGRESQL) {
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "7");
        properties.setProperty("cancelSignalTimeout", "2");
      } else {
        properties.setProperty("loginTimeout", "5");
        properties.setProperty("socketTimeout", "7000");
        properties.setProperty("cancelQueryTimeout", "2");
      }
      return DriverManager.getConnection(url, properties);
    }
  }

  static Evidence capture(Backend backend, String url, String user, String password) {
    // Use a dedicated physical connection so timeouts and STATISTICS XML cannot leak into JMH.
    String version = null;
    try (Connection connection = backend.connect(url, user, password);
        Statement session = connection.createStatement()) {
      version = connection.getMetaData().getDatabaseProductVersion();
      session.setQueryTimeout(STATEMENT_TIMEOUT_SECONDS);
      if (backend == Backend.POSTGRESQL) {
        connection.setAutoCommit(false);
        session.execute("SET TRANSACTION READ ONLY");
        session.execute("SET LOCAL statement_timeout = " + POSTGRESQL_STATEMENT_TIMEOUT_MILLIS);
        session.execute("SET LOCAL lock_timeout = " + LOCK_TIMEOUT_MILLIS);
      } else {
        session.execute("SET LOCK_TIMEOUT " + LOCK_TIMEOUT_MILLIS);
        session.execute("SET STATISTICS XML ON");
      }

      String sql =
          backend == Backend.POSTGRESQL
              ? "EXPLAIN (ANALYZE, BUFFERS, WAL, FORMAT XML) " + WINDOW_READ
              : WINDOW_READ;
      try (var query = connection.prepareStatement(sql)) {
        query.setQueryTimeout(STATEMENT_TIMEOUT_SECONDS);
        query.setLong(1, 17L);
        query.setInt(2, 8);
        query.setInt(3, 11);
        boolean hasResultSet = query.execute();
        while (hasResultSet || query.getUpdateCount() != -1) {
          if (hasResultSet) {
            try (ResultSet result = query.getResultSet()) {
              // SQL Server returns the three-column SELECT result before its one-column plan.
              if (result.getMetaData().getColumnCount() == 1 && result.next()) {
                try (Reader xml = result.getCharacterStream(1)) {
                  return (xml == null
                          ? unsupported(backend, "missing-plan")
                          : summarize(backend, readBounded(xml)))
                      .withServerVersion(version);
                }
              }
            }
          }
          hasResultSet = query.getMoreResults();
        }
      }
      return unsupported(backend, "missing-plan").withServerVersion(version);
    } catch (SQLException failure) {
      return failed(backend, failure).withServerVersion(version);
    } catch (IOException failure) {
      return unsupported(backend, "unreadable-plan").withServerVersion(version);
    }
  }

  private static String readBounded(Reader xml) throws IOException {
    StringBuilder content = new StringBuilder();
    char[] chunk = new char[4096];
    int count;
    while ((count = xml.read(chunk)) != -1) {
      if (content.length() + count > MAX_PLAN_CHARACTERS) {
        throw new IOException("plan exceeds size limit");
      }
      content.append(chunk, 0, count);
    }
    return content.toString();
  }

  static Evidence failed(Backend backend, SQLException failure) {
    String state = failure.getSQLState();
    int code = failure.getErrorCode();
    if ("42501".equals(state) || (backend == Backend.SQLSERVER && (code == 229 || code == 262))) {
      return unsupported(backend, "permission-denied");
    }
    if (failure instanceof SQLTimeoutException
        || "57014".equals(state)
        || "55P03".equals(state)
        || "HYT00".equals(state)
        || (backend == Backend.SQLSERVER && code == 1222)) {
      return unsupported(backend, "timeout");
    }
    return unsupported(backend, "database-error");
  }

  static Evidence summarize(Backend backend, String xml) {
    if (xml.length() > MAX_PLAN_CHARACTERS) {
      return unsupported(backend, "invalid-plan");
    }
    try {
      var factory = DocumentBuilderFactory.newDefaultInstance();
      factory.setNamespaceAware(true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      var builder = factory.newDocumentBuilder();
      builder.setErrorHandler(new DefaultHandler());
      Element root = builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
      if (!backend.root.equals(root.getLocalName())
          || !backend.namespace.equals(root.getNamespaceURI())) {
        return unsupported(backend, "invalid-plan");
      }
      return backend == Backend.POSTGRESQL ? postgres(backend, root) : sqlServer(backend, root);
    } catch (IOException | SAXException | ParserConfigurationException failure) {
      return unsupported(backend, "invalid-plan");
    }
  }

  private static Evidence postgres(Backend backend, Element root) {
    Element query = child(root, "Query");
    Element plan = child(query, "Plan");
    if (plan == null) {
      return unsupported(backend, "missing-plan");
    }
    Map<String, BigDecimal> metrics = new TreeMap<>();
    put(metrics, "actualRows", value(plan, "Actual-Rows"));
    put(metrics, "actualLoops", value(plan, "Actual-Loops"));
    put(metrics, "sharedHitBlocks", value(plan, "Shared-Hit-Blocks"));
    put(metrics, "sharedReadBlocks", value(plan, "Shared-Read-Blocks"));
    put(metrics, "walRecords", value(plan, "WAL-Records"));
    put(metrics, "walFullPageImages", value(plan, "WAL-FPI"));
    put(metrics, "walBytes", value(plan, "WAL-Bytes"));
    put(metrics, "executionMillis", value(query, "Execution-Time"));
    return measured(backend, value(plan, "Node-Type"), metrics);
  }

  private static Evidence sqlServer(Backend backend, Element root) {
    Element query = child(root, "BatchSequence", "Batch", "Statements", "StmtSimple", "QueryPlan");
    Element plan = child(query, "RelOp");
    if (plan == null) {
      return unsupported(backend, "missing-plan");
    }
    Map<String, BigDecimal> metrics = new TreeMap<>();
    Element timing = child(query, "QueryTimeStats");
    if (timing != null) {
      put(metrics, "cpuMillis", timing.getAttribute("CpuTime"));
      put(metrics, "executionMillis", timing.getAttribute("ElapsedTime"));
    }
    int spillWarnings =
        query.getElementsByTagNameNS(backend.namespace, "SpillToTempDb").getLength();
    metrics.put("spillWarnings", BigDecimal.valueOf(spillWarnings));
    Element runtime = child(plan, "RunTimeInformation");
    Map.of(
            "actualRows", "ActualRows",
            "actualExecutions", "ActualExecutions",
            "logicalReads", "ActualLogicalReads",
            "physicalReads", "ActualPhysicalReads")
        .forEach(
            (metric, attribute) -> {
              BigDecimal sum = sumThreads(runtime, attribute);
              if (sum != null) {
                metrics.put(metric, sum);
              }
            });
    return measured(backend, plan.getAttribute("PhysicalOp"), metrics);
  }

  private static BigDecimal sumThreads(Element runtime, String attribute) {
    if (runtime == null) {
      return null;
    }
    BigDecimal sum = null;
    for (var node = runtime.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element thread
          && "RunTimeCountersPerThread".equals(thread.getLocalName())
          && runtime.getNamespaceURI().equals(thread.getNamespaceURI())) {
        String value = thread.getAttribute(attribute);
        if (!NON_NEGATIVE_NUMBER.matcher(value).matches()) {
          return null;
        }
        BigDecimal count = new BigDecimal(value);
        sum = sum == null ? count : sum.add(count);
      }
    }
    return sum;
  }

  private static Element child(Element parent, String... path) {
    for (String name : path) {
      if (parent == null) {
        return null;
      }
      Element found = null;
      for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
        if (node instanceof Element candidate
            && name.equals(candidate.getLocalName())
            && parent.getNamespaceURI().equals(candidate.getNamespaceURI())) {
          found = candidate;
          break;
        }
      }
      parent = found;
    }
    return parent;
  }

  private static String value(Element element, String child) {
    Element field = child(element, child);
    return field == null ? "" : field.getTextContent().strip();
  }

  private static void put(Map<String, BigDecimal> metrics, String key, String value) {
    if (NON_NEGATIVE_NUMBER.matcher(value).matches()) {
      metrics.put(key, new BigDecimal(value));
    }
  }

  private static Evidence measured(
      Backend backend, String nativeOperator, Map<String, BigDecimal> metrics) {
    if (!metrics.containsKey("actualRows")) {
      return unsupported(backend, "missing-runtime-metrics");
    }
    String operator =
        switch (nativeOperator) {
          case "Index Scan", "Index Only Scan", "Clustered Index Scan" -> "index-scan";
          case "Index Seek", "Clustered Index Seek" -> "index-seek";
          case "Seq Scan", "Table Scan" -> "table-scan";
          case "Bitmap Heap Scan" -> "bitmap-scan";
          case "Sort" -> "sort";
          default -> "other";
        };
    return new Evidence(backend, operator, Collections.unmodifiableMap(metrics), null, "unknown");
  }

  private static Evidence unsupported(Backend backend, String reason) {
    return new Evidence(backend, null, Map.of(), reason, "unknown");
  }

  record Evidence(
      Backend backend,
      String operator,
      Map<String, BigDecimal> metrics,
      String unsupported,
      String serverVersion) {

    Evidence withServerVersion(String productVersion) {
      var version = SERVER_VERSION.matcher(productVersion == null ? "" : productVersion);
      return new Evidence(
          backend,
          operator,
          metrics,
          unsupported,
          version.lookingAt() ? version.group() : "unknown");
    }

    String json() {
      // All serialized strings are fixed vocabulary. Never interpolate raw XML or JDBC text.
      StringJoiner values = new StringJoiner(",", "{", "}");
      metrics.forEach((key, value) -> values.add("\"" + key + "\":" + value.toPlainString()));
      return "{\"schemaVersion\":1,\"backend\":\""
          + backend.id
          + "\",\"serverVersion\":\""
          + serverVersion
          + "\",\"probe\":\"pack-chunk-window\",\"profile\":\"indexed-fixture-v1\","
          + "\"settings\":{\"queryTimeoutSeconds\":"
          + STATEMENT_TIMEOUT_SECONDS
          + ",\"lockTimeoutMillis\":"
          + LOCK_TIMEOUT_MILLIS
          + (backend == Backend.POSTGRESQL
              ? ",\"readOnlyTransaction\":true,\"serverStatementTimeoutMillis\":"
                  + POSTGRESQL_STATEMENT_TIMEOUT_MILLIS
              : ",\"statisticsXml\":true")
          + "},\"requiredExtensions\":[],"
          + (unsupported == null
              ? "\"operator\":\"" + operator + "\",\"metrics\":" + values
              : "\"unsupported\":\"" + unsupported + "\"")
          + "}\n";
    }
  }
}
