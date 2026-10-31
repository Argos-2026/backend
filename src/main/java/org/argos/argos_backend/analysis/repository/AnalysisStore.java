package org.argos.argos_backend.analysis.repository;

import java.time.Clock;
import java.util.*;
import org.argos.argos_backend.analysis.domain.AnalysisJob;
import org.argos.argos_backend.global.config.ArgosProperties;
import org.argos.argos_backend.global.exception.ApiException;
import org.springframework.stereotype.Component;

import static org.argos.argos_backend.analysis.domain.AnalysisModels.*;

@Component
public class AnalysisStore {
    private final Map<UUID, AnalysisJob> jobs = new HashMap<>();
    private final Clock clock;
    private final ArgosProperties properties;
    public AnalysisStore(Clock clock, ArgosProperties properties) { this.clock = clock; this.properties = properties; }
    public synchronized AnalysisJob create(String url) {
        evictExpired();
        if (jobs.size() >= properties.maxJobs()) throw new ApiException(429, "RATE_LIMIT_EXCEEDED", "분석 보관 한도를 초과했습니다. 잠시 후 다시 시도해 주세요.");
        var now = clock.instant();
        var job = new AnalysisJob(UUID.randomUUID(), url, now, now.plus(properties.retention()));
        jobs.put(job.id(), job); return job;
    }
    public synchronized void remove(UUID id) { jobs.remove(id); }
    public synchronized Optional<Snapshot> find(UUID id) {
        evictExpired();
        return Optional.ofNullable(jobs.get(id)).map(AnalysisJob::snapshot);
    }
    public synchronized void evictExpired() {
        var iterator = jobs.values().iterator();
        while (iterator.hasNext()) {
            AnalysisJob job = iterator.next();
            if (!clock.instant().isBefore(job.expiresAt())) {
                job.fail(new Issue("ANALYSIS_TIMEOUT", job.currentStage(), "분석 보관 기한이 만료되었습니다."), List.of(), true);
                iterator.remove();
            }
        }
    }
    public synchronized void stopAll() {
        jobs.values().forEach(j -> j.fail(new Issue("INTERNAL_SERVER_ERROR", j.currentStage(), "서버 종료로 분석이 중단되었습니다."), List.of(), true));
    }
}
