package com.metertrail;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class IngestAdmissionTest {
  @Test
  void fullGateFailsImmediatelyAndDuplicateCloseDoesNotInventCapacity() {
    var metrics = new SimpleMeterRegistry();
    var gate = new IngestAdmission(mock(DataSource.class), metrics, 1);
    var first = gate.enter();
    ApiError failure = assertThrows(ApiError.class, gate::enter);
    assertEquals(503, failure.status);
    assertEquals("INGEST_BUSY", failure.code);
    assertEquals(
        1,
        metrics.get("metertrail_ingest_unavailable").tag("code", "INGEST_BUSY").counter().count());
    first.close();
    first.close();
    try (var next = gate.enter()) {
      assertThrows(ApiError.class, gate::enter);
    }
    assertEquals(1, gate.permits.availablePermits());
  }

  @Test
  void smallPoolKeepsHeadroomAndInvalidBudgetsFailConfiguration() {
    try (var source = new HikariDataSource()) {
      source.setMaximumPoolSize(3);
      assertEquals(1, new IngestAdmission(source, new SimpleMeterRegistry(), 4).limit);
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new IngestAdmission(mock(DataSource.class), new SimpleMeterRegistry(), 0));
  }
}
