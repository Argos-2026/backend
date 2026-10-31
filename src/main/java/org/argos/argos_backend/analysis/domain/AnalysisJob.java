package org.argos.argos_backend.analysis.domain;

import java.time.Instant;
import java.util.*;
import org.argos.argos_backend.analysis.dto.CrossReportTraceResponse.ClaimTrace;

import static org.argos.argos_backend.analysis.domain.AnalysisModels.*;

/** All transitions and snapshots share the same lock; terminal states are immutable. */
public final class AnalysisJob {
    private final UUID id;
    private final String url;
    private final Instant createdAt;
    private final Instant expiresAt;
    private Status status = Status.QUEUED;
    private List<Stage> stages = List.of();
    private List<Issue> warnings = List.of();
    private Issue error;
    private Result result;
    private Thread worker;
    private List<ClaimTrace> traceClaims = List.of();
    public AnalysisJob(UUID id, String url, Instant createdAt, Instant expiresAt) {
        this.id = id; this.url = url; this.createdAt = createdAt; this.expiresAt = expiresAt;
    }
    public synchronized boolean start() {
        if (status != Status.QUEUED) return false;
        worker = Thread.currentThread(); status = Status.RUNNING; stages = List.of(Stage.FETCHING_ARTICLE); return true;
    }
    public synchronized void stage(Stage stage) { if (status == Status.RUNNING) stages = List.of(stage); }
    public synchronized boolean complete(Result value) {
        if (status != Status.RUNNING) return false;
        result = value; warnings = List.copyOf(value.warnings()); status = value.status(); stages = List.of(); worker = null; return true;
    }
    public synchronized boolean fail(Issue issue, List<Issue> issues, boolean interrupt) {
        if (terminal()) return false;
        status = Status.FAILED; stages = List.of(); error = issue; warnings = List.copyOf(issues);
        if (interrupt && worker != null) worker.interrupt();
        worker = null; return true;
    }
    public synchronized Stage currentStage() { return stages.isEmpty() ? Stage.FETCHING_ARTICLE : stages.getFirst(); }
    private synchronized boolean terminal() { return status == Status.COMPLETED || status == Status.PARTIAL_COMPLETED || status == Status.FAILED; }
    public synchronized void trace(List<ClaimTrace> claims) {
        if (status == Status.RUNNING) traceClaims = List.copyOf(claims);
    }
    public synchronized Snapshot snapshot() { return new Snapshot(id, url, status, stages, createdAt, expiresAt, warnings, error, result, traceClaims); }
    public UUID id() { return id; }
    public String url() { return url; }
    public Instant createdAt() { return createdAt; }
    public Instant expiresAt() { return expiresAt; }
}
