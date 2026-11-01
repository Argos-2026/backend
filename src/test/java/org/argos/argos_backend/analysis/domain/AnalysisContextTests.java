package org.argos.argos_backend.analysis.domain;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import static org.argos.argos_backend.analysis.domain.AnalysisModels.*;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisContextTests {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void traceUpdateAndStorageFailuresAreIsolatedAndLoggedWithoutSensitiveContent(boolean storageFailure) {
        Logger logger = (Logger) LoggerFactory.getLogger(AnalysisContext.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.setContext(logger.getLoggerContext());
        appender.start(); logger.addAppender(appender);
        String sensitive = "api-key-secret article-full-body 홍길동 private@example.com";
        RuntimeException failure = new IllegalStateException(sensitive, new RuntimeException(sensitive));
        AnalysisContext context = new AnalysisContext(Clock.systemUTC(), Instant.now().plusSeconds(600), ignored -> {},
            ignored -> { if (storageFailure) throw failure; });
        context.stage(Stage.SELECTING_EVIDENCE);
        try {
            assertDoesNotThrow(() -> context.trace(t -> {
                if (!storageFailure) throw failure;
                t.claim("report-1", sensitive, "exact", "expanded");
            }));
            assertDoesNotThrow(context::check);
            assertTrue(context.warnings().isEmpty());
            assertEquals(storageFailure ? 1 : 0, context.traceSnapshot().size());
            assertEquals(1, appender.list.size());
            ILoggingEvent event = appender.list.getFirst();
            assertEquals(Level.WARN, event.getLevel());
            assertEquals("code=TRACE_RECORDING_FAILED stage=SELECTING_EVIDENCE exceptionType=IllegalStateException", event.getFormattedMessage());
            assertNull(event.getThrowableProxy());
            assertFalse(event.getFormattedMessage().contains(sensitive));
            assertArrayEquals(new Object[]{Stage.SELECTING_EVIDENCE, "IllegalStateException"}, event.getArgumentArray());
        } finally {
            logger.detachAppender(appender); appender.stop();
        }
    }
}
