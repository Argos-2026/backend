package org.argos.argos_backend.analysis.service;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.argos.argos_backend.analysis.dto.AnalysisStatusResponse;
import org.argos.argos_backend.analysis.repository.AnalysisStore;
import org.argos.argos_backend.global.config.ArgosProperties;
import org.argos.argos_backend.global.exception.ApiException;
import org.junit.jupiter.api.Test;

import static org.argos.argos_backend.analysis.domain.AnalysisModels.*;
import static org.argos.argos_backend.support.AnalysisFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisQueryServiceTests {
    @Test void restartedStoreCannotFindPreviouslyIssuedId() {
        Clock clock = Clock.systemUTC();
        ArgosProperties props = properties(100, Duration.ofMinutes(10), false);
        var oldStore = new AnalysisStore(clock, props);
        var job = oldStore.create("https://example.com/article");
        var restartedQueries = new AnalysisQueryService(new AnalysisStore(clock, props));
        ApiException e = assertThrows(ApiException.class, () -> restartedQueries.status(job.id().toString()));
        assertEquals(404, e.status());
        assertEquals("ANALYSIS_NOT_FOUND", e.code());
        ApiException resultError = assertThrows(ApiException.class, () -> restartedQueries.result(job.id().toString()));
        assertEquals(404, resultError.status());
        assertEquals("ANALYSIS_NOT_FOUND", resultError.code());
        // Reading existing state does not require external service credentials.
        assertEquals(Status.QUEUED, new AnalysisQueryService(oldStore).status(job.id().toString()).status());
    }

    @Test void concurrentStageUpdatesAndCompletionRemainConsistent() throws Exception {
        var store = new AnalysisStore(Clock.systemUTC(), properties(100, Duration.ofMinutes(10), false));
        var queries = new AnalysisQueryService(store);
        var job = store.create("https://example.com/article");
        var started = new CountDownLatch(1);
        var readReady = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> writer = executor.submit(() -> {
                job.start(); started.countDown();
                try { readReady.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                for (int i = 0; i < 2000; i++) job.stage(i % 2 == 0 ? Stage.VERIFYING_CLAIMS : Stage.AGGREGATING_RESULT);
                job.complete(emptyResult(job.id(), Status.PARTIAL_COMPLETED));
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            readReady.countDown();
            for (int i = 0; i < 2000; i++) {
                AnalysisStatusResponse response = queries.status(job.id().toString());
                if (response.status() == Status.RUNNING) {
                    assertEquals(1, response.activeStages().size());
                    assertTrue(response.warnings().isEmpty());
                } else {
                    assertEquals(Status.PARTIAL_COMPLETED, response.status());
                    assertTrue(response.activeStages().isEmpty());
                    assertEquals(1, response.warnings().size());
                }
                assertNull(response.error());
            }
            writer.get(2, TimeUnit.SECONDS);
        }
        assertEquals(Status.PARTIAL_COMPLETED, queries.status(job.id().toString()).status());
    }
}
