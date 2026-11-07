/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static io.github.carstenartur.jgit.storage.hibernate.benchmark.DatabaseTelemetryFocusedRead.Backend.POSTGRESQL;
import static io.github.carstenartur.jgit.storage.hibernate.benchmark.DatabaseTelemetryFocusedRead.Backend.SQLSERVER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DatabaseTelemetryFocusedReadTest {

  @Test
  void postgresqlKeepsOnlyRootReadMetricsAndAnAllowedOperator() {
    String plan =
        """
        <explain xmlns="http://www.postgresql.org/2009/explain">
          <Query>
            <Plan>
              <Node-Type>Index Scan</Node-Type>
              <Relation-Name>private-table</Relation-Name>
              <Index-Name>private-index</Index-Name>
              <Index-Cond>(pack_id = 95231)</Index-Cond>
              <Actual-Rows>4</Actual-Rows>
              <Actual-Loops>1</Actual-Loops>
              <Shared-Hit-Blocks>6</Shared-Hit-Blocks>
              <Shared-Read-Blocks>0</Shared-Read-Blocks>
              <WAL-Records>2</WAL-Records><WAL-FPI>1</WAL-FPI><WAL-Bytes>8192</WAL-Bytes>
              <Plans><Plan><Actual-Rows>95231</Actual-Rows></Plan></Plans>
            </Plan>
            <Execution-Time>0.128</Execution-Time>
          </Query>
        </explain>
        """;
    var evidence =
        DatabaseTelemetryFocusedRead.summarize(POSTGRESQL, plan)
            .withServerVersion("17.10 (private-build)");

    assertEquals("17.10", evidence.serverVersion());

    assertEquals("index-scan", evidence.operator());
    assertEquals(
        Map.of(
            "actualRows",
            number("4"),
            "actualLoops",
            number("1"),
            "sharedHitBlocks",
            number("6"),
            "sharedReadBlocks",
            number("0"),
            "executionMillis",
            number("0.128"),
            "walRecords",
            number("2"),
            "walFullPageImages",
            number("1"),
            "walBytes",
            number("8192")),
        evidence.metrics());
    assertFalse(evidence.json().contains("private"));
    assertFalse(evidence.json().contains("95231"));
  }

  @Test
  void sqlServerNeverRetainsStatementTextParametersOrIdentifiers() {
    String plan =
        """
        <ShowPlanXML xmlns="http://schemas.microsoft.com/sqlserver/2004/07/showplan">
          <BatchSequence><Batch><Statements>
            <StmtSimple StatementText="SELECT * FROM [private-table] WHERE pack_id = @P1">
              <QueryPlan>
                <QueryTimeStats CpuTime="1" ElapsedTime="2"/>
                <RelOp PhysicalOp="Clustered Index Seek">
                  <RunTimeInformation>
                    <RunTimeCountersPerThread Thread="0" ActualRows="2" ActualExecutions="1"
                      ActualLogicalReads="3" ActualPhysicalReads="0"/>
                    <RunTimeCountersPerThread Thread="1" ActualRows="2" ActualExecutions="1"
                      ActualLogicalReads="4" ActualPhysicalReads="1"/>
                  </RunTimeInformation>
                  <IndexScan><Object Database="[private-db]" Index="[private-index]"/></IndexScan>
                </RelOp>
                <ParameterList><ColumnReference ParameterRuntimeValue="95231"/></ParameterList>
              </QueryPlan>
            </StmtSimple>
          </Statements></Batch></BatchSequence>
        </ShowPlanXML>
        """;
    var evidence = DatabaseTelemetryFocusedRead.summarize(SQLSERVER, plan);

    assertEquals("index-seek", evidence.operator());
    assertEquals(
        Map.of(
            "actualRows",
            number("4"),
            "actualExecutions",
            number("2"),
            "logicalReads",
            number("7"),
            "physicalReads",
            number("1"),
            "cpuMillis",
            number("1"),
            "executionMillis",
            number("2"),
            "spillWarnings",
            number("0")),
        evidence.metrics());
    for (String forbidden : new String[] {"SELECT", "@P1", "private", "95231"}) {
      assertFalse(evidence.json().contains(forbidden));
    }

    var spilled =
        DatabaseTelemetryFocusedRead.summarize(
            SQLSERVER,
            plan.replace(
                "<RunTimeInformation>",
                "<Warnings><SpillToTempDb SpillLevel='1' SpilledThreadCount='1'/></Warnings>"
                    + "<RunTimeInformation>"));
    assertEquals(number("1"), spilled.metrics().get("spillWarnings"));
    assertFalse(spilled.json().contains("SpilledThreadCount"));
  }

  @Test
  void unsupportedInputNeverSerializesRawDiagnostics() {
    String plan =
        """
        <explain xmlns="http://www.postgresql.org/2009/explain"><Query><Plan>
          <Node-Type>private-operator</Node-Type><Actual-Rows>4</Actual-Rows>
          <Shared-Hit-Blocks>4, "private-metric": 95231</Shared-Hit-Blocks>
        </Plan></Query></explain>
        """;
    var unrecognized = DatabaseTelemetryFocusedRead.summarize(POSTGRESQL, plan);
    assertEquals("other", unrecognized.operator());
    assertEquals(Map.of("actualRows", number("4")), unrecognized.metrics());
    assertFalse(unrecognized.json().contains("private"));

    var malformed =
        DatabaseTelemetryFocusedRead.summarize(
            POSTGRESQL, "<!DOCTYPE foo [<!ENTITY e SYSTEM 'file:///private'>]><foo>&e;</foo>");
    assertEquals("invalid-plan", malformed.unsupported());
    assertFalse(malformed.json().contains("private"));

    var denied =
        DatabaseTelemetryFocusedRead.failed(
            SQLSERVER,
            new SQLException("jdbc:sqlserver://private-db and private-table", "S0001", 262));
    assertEquals("permission-denied", denied.unsupported());
    assertEquals(Map.of(), denied.metrics());
    assertFalse(denied.json().contains("private"));
    assertFalse(denied.json().contains("jdbc"));
  }

  private static BigDecimal number(String value) {
    return new BigDecimal(value);
  }
}
