package org.argos.argos_backend.analysis.service;

import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.argos.argos_backend.analysis.repository.AnalysisStore;
import org.argos.argos_backend.external.http.SafeUrlPolicy;
import org.argos.argos_backend.global.exception.ApiException;
import org.junit.jupiter.api.Test;

import static org.argos.argos_backend.analysis.domain.AnalysisModels.*;
import static org.argos.argos_backend.support.AnalysisFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalysisServiceTests {
    private SafeUrlPolicy policy() {
        SafeUrlPolicy policy = mock(SafeUrlPolicy.class);
        when(policy.validate(anyString())).thenAnswer(i -> URI.create(i.getArgument(0)));
        when(policy.validateInitial(anyString())).thenAnswer(i -> URI.create(i.getArgument(0)));
        return policy;
    }
    @Test void absentKeysDoNotCreateAJobOrMakeExternalCalls() {
        var props = properties(100, Duration.ofMinutes(10), false);
        var store = new AnalysisStore(Clock.systemUTC(), props);
        var pipeline = mock(AnalysisPipeline.class);
        var service = new AnalysisService(props, policy(), store, pipeline, Clock.systemUTC());
        try {
            ApiException failure = assertThrows(ApiException.class, () -> service.submit("https://example.com/a"));
            assertEquals(503, failure.status());
            verifyNoInteractions(pipeline);
        } finally { service.close(); }
    }
    @Test void fullQueueRejectsAndRemovesRejectedJob() throws Exception {
        var props = properties(3, Duration.ofMinutes(10), true);
        var store = new AnalysisStore(Clock.systemUTC(), props);
        var pipeline = mock(AnalysisPipeline.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(pipeline.run(any(), anyString(), any())).thenAnswer(i -> {
            entered.countDown(); release.await(); return emptyResult(i.getArgument(0));
        });
        var service = new AnalysisService(props, policy(), store, pipeline, Clock.systemUTC());
        try {
            service.submit("https://example.com/a");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            service.submit("https://example.com/b");
            var failure = assertThrows(ApiException.class, () -> service.submit("https://example.com/c"));
            assertEquals(429, failure.status());
            // Rejected third job was rolled back; creating another stored job still fits the capacity.
            assertNotNull(store.create("https://example.com/d"));
        } finally { release.countDown(); service.close(); }
    }
    @Test void timeoutCannotBeOverwrittenByLateSuccess() throws Exception {
        var props = properties(10, Duration.ofMillis(50), true);
        var store = new AnalysisStore(Clock.systemUTC(), props);
        var pipeline = mock(AnalysisPipeline.class);
        var finished = new CountDownLatch(1);
        when(pipeline.run(any(), anyString(), any())).thenAnswer(i -> {
            try { Thread.sleep(2000); } catch (InterruptedException ignored) { /* Deliberately misbehaving dependency. */ }
            finished.countDown(); return emptyResult(i.getArgument(0));
        });
        var service = new AnalysisService(props, policy(), store, pipeline, Clock.systemUTC());
        try {
            var accepted = service.submit("https://example.com/a");
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            Snapshot snapshot = store.find(accepted.analysisId()).orElseThrow();
            assertEquals(Status.FAILED, snapshot.status());
            assertEquals("ANALYSIS_TIMEOUT", snapshot.error().code());
            assertNull(snapshot.result());
        } finally { service.close(); }
    }
    @Test void expiredJobsAreRemovedAndTerminalSnapshotsRemainImmutable() {
        var clock = new MutableClock();
        var props = properties(1, Duration.ofMinutes(10), true);
        var store = new AnalysisStore(clock, props);
        var job = store.create("https://example.com/a");
        assertTrue(job.start());
        assertTrue(job.complete(emptyResult(job.id())));
        assertFalse(job.fail(new Issue("LLM_FAILED", Stage.VERIFYING_CLAIMS, "failure"), List.of(), false));
        job.stage(Stage.EXTRACTING_CLAIMS);
        assertEquals(Status.COMPLETED, job.snapshot().status());
        assertTrue(job.snapshot().activeStages().isEmpty());
        clock.now = clock.now.plus(Duration.ofHours(24));
        assertTrue(store.find(job.id()).isEmpty());
        assertNotNull(store.create("https://example.com/b"));
        assertTrue(new AnalysisStore(clock, props).find(job.id()).isEmpty());
    }
    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-31T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
