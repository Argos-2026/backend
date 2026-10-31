package org.argos.argos_backend.analysis.domain;

import java.time.*;
import java.util.*;
import java.util.function.Consumer;

import static org.argos.argos_backend.analysis.domain.AnalysisModels.*;

public final class AnalysisContext {
    private final Clock clock;
    private final Instant deadline;
    private final Consumer<Stage> stageListener;
    private final List<Issue> warnings = new ArrayList<>();
    private volatile Stage stage = Stage.FETCHING_ARTICLE;
    public AnalysisContext(Clock clock, Instant deadline, Consumer<Stage> stageListener) {
        this.clock = clock; this.deadline = deadline; this.stageListener = stageListener;
    }
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
