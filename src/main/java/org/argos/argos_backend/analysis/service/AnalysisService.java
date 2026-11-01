package org.argos.argos_backend.analysis.service;

import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import org.argos.argos_backend.analysis.domain.AnalysisContext;
import org.argos.argos_backend.analysis.domain.AnalysisFailure;
import org.argos.argos_backend.analysis.domain.AnalysisJob;
import org.argos.argos_backend.analysis.dto.AnalysisAcceptedResponse;
import org.argos.argos_backend.analysis.repository.AnalysisStore;
import org.argos.argos_backend.external.http.SafeUrlPolicy;
import org.argos.argos_backend.global.config.ArgosProperties;
import org.argos.argos_backend.global.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static org.argos.argos_backend.analysis.domain.AnalysisModels.*;

@Service
public class AnalysisService {
    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);
    private final ArgosProperties properties;
    private final SafeUrlPolicy urls;
    private final AnalysisStore store;
    private final AnalysisPipeline pipeline;
    private final Clock clock;
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor timer;

    public AnalysisService(ArgosProperties properties, SafeUrlPolicy urls, AnalysisStore store, AnalysisPipeline pipeline, Clock clock) {
        this.properties = properties; this.urls = urls; this.store = store; this.pipeline = pipeline; this.clock = clock;
        workers = new ThreadPoolExecutor(properties.workers(), properties.workers(), 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(properties.queueCapacity()), Thread.ofPlatform().name("argos-analysis-", 0).daemon().factory(), new ThreadPoolExecutor.AbortPolicy());
        timer = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().name("argos-maintenance-", 0).daemon().factory());
        timer.setRemoveOnCancelPolicy(true);
        timer.scheduleWithFixedDelay(store::evictExpired, 60, 60, TimeUnit.SECONDS);
    }

    public AnalysisAcceptedResponse submit(String url) {
        // Syntactic validation is deterministic even if credentials are not set yet.
        var parsed = urls.validate(url);
        if (!properties.credentialsPresent()) throw new ApiException(503, "SERVICE_UNAVAILABLE", "분석 서비스의 API 인증정보가 설정되지 않았습니다.");
        if (workers.isShutdown()) throw unavailable();
        String checked = urls.validateInitial(parsed.toString()).toString();
        AnalysisJob job = store.create(checked);
        try { workers.execute(() -> execute(job)); }
        catch (RejectedExecutionException e) {
            store.remove(job.id());
            if (workers.isShutdown()) throw unavailable();
            throw new ApiException(429, "RATE_LIMIT_EXCEEDED", "동시에 처리할 수 있는 분석 요청 수를 초과했습니다.");
        }
        return new AnalysisAcceptedResponse(job.id(), Status.QUEUED, job.createdAt(), job.expiresAt());
    }

    private void execute(AnalysisJob job) {
        if (!clock.instant().isBefore(job.expiresAt()) || !job.start()) return;
        Instant started = clock.instant();
        Instant deadline = started.plus(properties.jobTimeout());
        if (job.expiresAt().isBefore(deadline)) deadline = job.expiresAt();
        AnalysisContext context = new AnalysisContext(clock, deadline, stage -> {
            job.stage(stage); log.info("Analysis id={} stage={}", job.id(), stage);
        }, job::trace);
        ScheduledFuture<?> timeout = null;
        try {
            timeout = timer.schedule(() -> {
                if (job.fail(new Issue("ANALYSIS_TIMEOUT", job.currentStage(), "분석 실행 제한시간을 초과했습니다."), List.of(), true))
                    log.warn("Analysis id={} code=ANALYSIS_TIMEOUT", job.id());
            }, Math.max(1, Duration.between(clock.instant(), deadline).toMillis()), TimeUnit.MILLISECONDS);
            Result result = pipeline.run(job.id(), job.url(), context);
            context.check();
            if (job.complete(result)) log.info("Analysis id={} status={} durationMs={}", job.id(), result.status(), Duration.between(started, clock.instant()).toMillis());
        } catch (AnalysisFailure e) {
            if (job.fail(e.issue(context.stage()), context.warnings(), false)) log.warn("Analysis id={} stage={} code={}", job.id(), context.stage(), e.code());
        } catch (Exception e) {
            if (job.fail(new Issue("INTERNAL_SERVER_ERROR", context.stage(), "분석 처리 중 오류가 발생했습니다."), context.warnings(), false))
                log.error("Analysis id={} stage={} code=INTERNAL_SERVER_ERROR exceptionType={}", job.id(), context.stage(), e.getClass().getSimpleName());
        } finally {
            if (timeout != null) timeout.cancel(false);
            Thread.interrupted(); // A timeout must not poison the next pooled task.
        }
    }
    private ApiException unavailable() { return new ApiException(503, "SERVICE_UNAVAILABLE", "현재 분석 요청을 접수할 수 없습니다."); }
    @PreDestroy public void close() {
        workers.shutdownNow(); store.stopAll(); timer.shutdownNow();
    }
}
