package org.argos.argos_backend.support;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.argos.argos_backend.global.config.ArgosProperties;

import static org.argos.argos_backend.analysis.domain.AnalysisModels.*;
import static org.argos.argos_backend.analysis.domain.ReportingModels.*;

public final class AnalysisFixtures {
    private AnalysisFixtures() {}

    public static ConflictCheck confirmedConflict(String originalQuote, String externalQuote) {
        return new ConflictCheck(ScopeMatch.SAME, ScopeMatch.SAME, ScopeMatch.SAME, ScopeMatch.SAME,
            ScopeMatch.SAME, ScopeMatch.SAME, ScopeMatch.SAME, ScopeMatch.SAME, ScopeMatch.SAME,
            true, originalQuote, externalQuote, "동일 대상·기간·단위·분모·집계 기준에서 수치가 양립 불가능합니다.");
    }

    public static ArgosProperties properties(int maxJobs, Duration timeout, boolean keys) {
        return new ArgosProperties(keys ? "test" : "", "gpt-5.4-mini", "test", "test", "test", 1, 1, maxJobs, timeout, Duration.ofHours(24));
    }

    public static Result emptyResult(UUID id) { return emptyResult(id, Status.COMPLETED); }

    public static Result emptyResult(UUID id, Status status) {
        return new Result(id, status, new Article("private-result", "제목", null, null, null),
            new Summary("검증할 주장 없음", "없음", "정보원 없음", List.of()), List.of(), List.of(), List.of(),
            status == Status.PARTIAL_COMPLETED ? List.of(new Issue("SOURCE_ASSESSMENT_FAILED", Stage.ASSESSING_SOURCES, "정보원 평가 실패")) : List.of());
    }
}
