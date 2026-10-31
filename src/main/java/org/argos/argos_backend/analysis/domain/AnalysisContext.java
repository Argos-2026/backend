package org.argos.argos_backend.analysis.domain;

import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import org.argos.argos_backend.analysis.dto.CrossReportTraceResponse.ClaimTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.argos.argos_backend.analysis.domain.AnalysisModels.*;

public final class AnalysisContext {
    private static final Logger log = LoggerFactory.getLogger(AnalysisContext.class);
    private final Clock clock;
    private final Instant deadline;
    private final Consumer<Stage> stageListener;
    private final List<Issue> warnings = new ArrayList<>();
    private volatile Stage stage = Stage.FETCHING_ARTICLE;
    private final CrossReportTrace trace = new CrossReportTrace();
    private final Consumer<List<ClaimTrace>> traceListener;
    public AnalysisContext(Clock clock, Instant deadline, Consumer<Stage> stageListener) {
        this(clock, deadline, stageListener, ignored -> {});
    }
    public AnalysisContext(Clock clock, Instant deadline, Consumer<Stage> stageListener,
                           Consumer<List<ClaimTrace>> traceListener) {
        this.clock = clock; this.deadline = deadline; this.stageListener = stageListener;
        this.traceListener = traceListener;
    }
    public synchronized void trace(Consumer<CrossReportTrace> update) {
        try {
            update.accept(trace);
            traceListener.accept(trace.snapshot());
        } catch (RuntimeException e) {
            // Observability must never alter the analysis result, warnings, or control flow.
            // Do not log exception messages, causes, stack traces, or trace payloads.
            log.warn("code=TRACE_RECORDING_FAILED stage={} exceptionType={}", stage, e.getClass().getSimpleName());
        }
    }
    public synchronized List<ClaimTrace> traceSnapshot() { return trace.snapshot(); }
    public void check() {
        if (Thread.currentThread().isInterrupted() || !clock.instant().isBefore(deadline))
            throw new AnalysisFailure("ANALYSIS_TIMEOUT", "분석 실행 제한시간을 초과했습니다.");
    }
    public Duration timeout(Duration max) {
        check();
        Duration remaining = Duration.between(clock.instant(), deadline);
        return remaining.compareTo(max) < 0 ? remaining : max;
    }
    public Duration remaining() { return Duration.between(clock.instant(), deadline); }
    public void stage(Stage value) { check(); stage = value; stageListener.accept(value); }
    public Stage stage() { return stage; }
    public synchronized void warn(Issue warning) { if (!warnings.contains(warning)) warnings.add(warning); }
    public synchronized List<Issue> warnings() { return List.copyOf(warnings); }
}
