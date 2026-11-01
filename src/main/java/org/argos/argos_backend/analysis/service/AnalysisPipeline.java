package org.argos.argos_backend.analysis.service;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.argos.argos_backend.analysis.domain.AnalysisContext;
import org.argos.argos_backend.analysis.domain.AnalysisFailure;
import org.argos.argos_backend.analysis.dto.CrossReportTraceResponse.QueryType;
import org.argos.argos_backend.external.document.DocumentFetcher;
import org.argos.argos_backend.external.http.SafeUrlPolicy;
import org.argos.argos_backend.external.llm.StructuredLlm;
import org.argos.argos_backend.external.search.SearchClient;
import org.argos.argos_backend.global.exception.ApiException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import static org.argos.argos_backend.analysis.domain.AnalysisModels.*;
import static org.argos.argos_backend.analysis.domain.ReportingModels.*;
import static org.argos.argos_backend.external.document.DocumentFetcher.Document;
import static org.argos.argos_backend.external.llm.LlmModels.*;

@Component
public class AnalysisPipeline {
    private static final Pattern INTERNAL_SUMMARY_REFERENCE = Pattern.compile(
        "(?i)(?<![\\p{L}\\p{N}_-])(?:source-claim-\\d+|source-\\d+-\\d+|report-\\d+|evidence-\\d+|candidate-\\d+|search-\\d+(?:-candidate-\\d+)?)(?![\\p{L}\\p{N}_-])(?:\\s*\\([^()\\r\\n]*\\))?");

    private final DocumentFetcher fetcher;
    private final SearchClient search;
    private final StructuredLlm llm;
    private final SafeUrlPolicy urls;
    private final JsonMapper json;
    private final ExecutorService searches = Executors.newFixedThreadPool(8,
        Thread.ofPlatform().name("argos-search-", 0).daemon().factory());

    public AnalysisPipeline(DocumentFetcher fetcher, SearchClient search, StructuredLlm llm, SafeUrlPolicy urls, JsonMapper json) {
        this.fetcher = fetcher; this.search = search; this.llm = llm; this.urls = urls; this.json = json;
    }

    public Result run(UUID id, String url, AnalysisContext context) {
        context.stage(Stage.FETCHING_ARTICLE);
        Document article = fetcher.fetch(url, true, context);
        context.stage(Stage.EXTRACTING_CLAIMS);
        Extraction extraction = generate("extract", Map.of("article", article), Extraction.class,
            value -> validateExtraction(value, article), context);
        for (int i = 0; i < extraction.crossReportClaims().size(); i++) {
            String claimId = reportId(i);
            DraftCrossClaim claim = extraction.crossReportClaims().get(i);
            context.trace(t -> t.claim(claimId, claim.text(), claim.exactQuery(), claim.expandedQuery()));
        }
        EvidencePool pool = new EvidencePool(article);
        List<CrossReport> crossReports = new ArrayList<>();
        for (int i = 0; i < extraction.crossReportClaims().size(); i++) {
            context.check();
            DraftCrossClaim draft = extraction.crossReportClaims().get(i);
            String claimId = reportId(i);
            if (nearDeadline(context) && crossReports.stream().anyMatch(r -> !r.analysisFailed())) {
                context.warn(new Issue("ANALYSIS_TIMEOUT", Stage.RETRIEVING_EVIDENCE,
                    "남은 실행시간이 부족해 일부 교차보도 분석을 생략했습니다."));
                context.trace(t -> t.failure(claimId, new Issue("ANALYSIS_TIMEOUT", Stage.RETRIEVING_EVIDENCE,
                    "남은 실행시간이 부족해 교차보도 분석을 시작하지 못했습니다.")));
                CrossReport report = new CrossReport(claimId, draft.text(), ReportVerdict.UNCONFIRMED,
                    "실행시간 제한으로 비교를 완료하지 못했습니다.", 0, 0, List.of(), true);
                context.trace(t -> t.result(claimId, report));
                crossReports.add(report);
                continue;
            }
            try {
                CrossReport report = analyzeReport(claimId, draft, pool, context);
                context.trace(t -> t.result(claimId, report));
                crossReports.add(report);
            } catch (AnalysisFailure e) {
                context.trace(t -> t.failure(claimId, e.issue(context.stage())));
                context.check();
                context.warn(new Issue(e.code().equals("ANALYSIS_TIMEOUT") ? "ANALYSIS_TIMEOUT" : "CROSS_REPORT_FAILED",
                    context.stage(), "일부 교차보도 분석을 완료하지 못했습니다."));
                CrossReport report = new CrossReport(claimId, draft.text(), ReportVerdict.UNCONFIRMED,
                    "외부 검색 또는 비교 처리에 실패하여 확인하지 못했습니다.", 0, 0, List.of(), true);
                context.trace(t -> t.result(claimId, report));
                crossReports.add(report);
            } catch (RuntimeException e) {
                context.trace(t -> t.failure(claimId, safeFailure(e, context.stage())));
                throw e;
            }
        }

        List<SourceAssessment> sourceAssessments = new ArrayList<>();
        for (int i = 0; i < extraction.sourceClaims().size(); i++) {
            context.check();
            DraftSourceClaim claim = extraction.sourceClaims().get(i);
            List<ReviewedSource> reviewed = new ArrayList<>();
            for (int j = 0; j < claim.sources().size(); j++) {
                context.check();
                DraftSource source = claim.sources().get(j);
                if (nearDeadline(context) && (crossReports.stream().anyMatch(r -> !r.analysisFailed())
                    || sourceAssessments.stream().flatMap(a -> a.sources().stream()).anyMatch(s -> !s.assessmentFailed())
                    || reviewed.stream().anyMatch(s -> !s.assessmentFailed()))) {
                    context.warn(new Issue("ANALYSIS_TIMEOUT", Stage.ASSESSING_SOURCES,
                        "남은 실행시간이 부족해 일부 정보원 평가를 생략했습니다."));
                    reviewed.add(failedSource(sourceId(i, j), source));
                    continue;
                }
                try {
                    reviewed.add(assessSource(sourceId(i, j), claim, source, pool, context));
                } catch (AnalysisFailure e) {
                    context.check();
                    context.warn(new Issue(e.code().equals("ANALYSIS_TIMEOUT") ? "ANALYSIS_TIMEOUT" : "SOURCE_ASSESSMENT_FAILED",
                        Stage.ASSESSING_SOURCES,
                        "일부 정보원 평가를 완료하지 못했습니다."));
                    reviewed.add(failedSource(sourceId(i, j), source));
                }
            }
            sourceAssessments.add(new SourceAssessment(sourceClaimId(i), claim.text(), List.copyOf(reviewed)));
        }

        boolean anyUseful = crossReports.stream().anyMatch(r -> !r.analysisFailed())
            || sourceAssessments.stream().flatMap(a -> a.sources().stream()).anyMatch(s -> !s.assessmentFailed());
        if (!anyUseful && (!crossReports.isEmpty() || !sourceAssessments.isEmpty()))
            throw new AnalysisFailure("ANALYSIS_FAILED", "사용 가능한 교차보도 또는 정보원 평가 결과를 생성하지 못했습니다.");

        context.stage(Stage.AGGREGATING_RESULT);
        List<String> limitations = new ArrayList<>();
        if (article.truncated()) limitations.add("기사 본문 앞부분 30,000자만 분석했습니다.");
        if (pool.documents.values().stream().anyMatch(Document::truncated))
            limitations.add("일부 외부 자료는 본문 30,000자 또는 PDF 앞 50페이지 범위에서 확인했습니다.");
        Summary summary;
        try {
            if (nearDeadline(context)) throw new AnalysisFailure("LLM_FAILED", "종합 요약을 생성할 시간이 부족합니다.");
            var input = new LinkedHashMap<String, Object>();
            input.put("crossReports", summaryCrossReports(crossReports));
            input.put("sourceAssessments", summarySourceAssessments(sourceAssessments));
            input.put("limitations", limitations);
            input.put("warnings", context.warnings());
            OverallSummary generated = generate("summarize", input, OverallSummary.class, this::validateSummary, context);
            List<String> points = new ArrayList<>(generated.unverifiedPoints());
            for (String limitation : limitations) if (!points.contains(limitation)) points.add(limitation);
            summary = new Summary(generated.overallComment(), hideInternalSummaryReferences(generated.crossReportOverview()),
                hideInternalSummaryReferences(generated.sourceAssessmentOverview()), List.copyOf(points));
        } catch (AnalysisFailure e) {
            context.check();
            context.warn(new Issue("LLM_FAILED", Stage.AGGREGATING_RESULT, "종합 요약 생성에 실패해 기본 요약을 사용했습니다."));
            summary = new Summary("각 분석 결과와 근거를 확인해 주세요.",
                crossReports.stream().map(r -> r.text() + ": " + r.verdict()).collect(Collectors.joining("\n")),
                "정보원 포함 주장 " + sourceAssessments.size() + "개를 평가했습니다.", List.copyOf(limitations));
        }
        context.check();
        Status status = context.warnings().isEmpty() ? Status.COMPLETED : Status.PARTIAL_COMPLETED;
        return new Result(id, status, article.article(), summary, List.copyOf(crossReports),
            List.copyOf(sourceAssessments), pool.usedEvidence(crossReports, sourceAssessments), context.warnings());
    }

    private List<Map<String, Object>> summaryCrossReports(List<CrossReport> reports) {
        return reports.stream().map(report -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("text", report.text());
            value.put("verdict", report.verdict());
            value.put("reason", report.reason());
            value.put("matchingCount", report.matchingCount());
            value.put("contradictingCount", report.contradictingCount());
            value.put("comparisons", report.comparisons().stream().map(comparison -> {
                Map<String, Object> comparisonValue = new LinkedHashMap<>();
                comparisonValue.put("stance", comparison.stance());
                comparisonValue.put("reason", comparison.reason());
                return comparisonValue;
            }).toList());
            value.put("analysisFailed", report.analysisFailed());
            return value;
        }).toList();
    }

    private List<Map<String, Object>> summarySourceAssessments(List<SourceAssessment> assessments) {
        return assessments.stream().map(assessment -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("text", assessment.text());
            value.put("sources", assessment.sources().stream().map(source -> {
                Map<String, Object> sourceValue = new LinkedHashMap<>();
                sourceValue.put("name", source.name());
                sourceValue.put("affiliation", source.affiliation());
                sourceValue.put("type", source.type());
                sourceValue.put("citedInArticle", source.citedInArticle());
                sourceValue.put("verificationNote", source.verificationNote());
                sourceValue.put("imvain", source.imvain().entrySet().stream().collect(Collectors.toMap(
                    Map.Entry::getKey,
                    entry -> Map.of("status", entry.getValue().status(), "reason", entry.getValue().reason()),
                    (first, ignored) -> first,
                    LinkedHashMap::new)));
                sourceValue.put("assessmentFailed", source.assessmentFailed());
                return sourceValue;
            }).toList());
            return value;
        }).toList();
    }

    static String hideInternalSummaryReferences(String value) {
        if (value == null) return null;
        Matcher matcher = INTERNAL_SUMMARY_REFERENCE.matcher(value);
        StringBuffer sanitized = new StringBuffer();
        while (matcher.find()) {
            String match = matcher.group();
            int opening = match.indexOf('(');
            int closing = match.lastIndexOf(')');
            String replacement = opening >= 0 && closing > opening
                ? match.substring(opening + 1, closing) : "";
            matcher.appendReplacement(sanitized, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sanitized);
        return sanitized.toString().replaceAll("[ \\t]{2,}", " ").trim();
    }

    private CrossReport analyzeReport(String id, DraftCrossClaim claim, EvidencePool pool, AnalysisContext context) {
        context.stage(Stage.RETRIEVING_EVIDENCE);
        boolean issueAvailable = false;
        if (claim.issue() != null) {
            try {
                ReportPerspectivePolicy.validateIssue(claim.issue(), pool.article);
                issueAvailable = true;
            } catch (RuntimeException invalid) {
                enrichmentFailure(id, "ISSUE_FRAME_INVALID", "쟁점의 원문 근거를 확인하지 못해 기존 검색·선정을 사용합니다.", context);
            }
        }
        TemporalContext temporal = ReportRetrievalPolicy.context(claim, pool.article, issueAvailable ? claim.issue() : null);
        context.trace(t -> t.temporal(id, temporal));
        List<SearchRequest> requests = new ArrayList<>(List.of(
            new SearchRequest(SearchClient.Provider.NAVER, ReportRetrievalPolicy.normalizeYears(claim.exactQuery(), pool.article.publishedAt()), QueryType.EXACT),
            new SearchRequest(SearchClient.Provider.BRAVE, ReportRetrievalPolicy.normalizeYears(claim.exactQuery(), pool.article.publishedAt()), QueryType.EXACT),
            new SearchRequest(SearchClient.Provider.NAVER, ReportRetrievalPolicy.normalizeYears(claim.expandedQuery(), pool.article.publishedAt()), QueryType.EXPANDED),
            new SearchRequest(SearchClient.Provider.BRAVE, ReportRetrievalPolicy.normalizeYears(claim.expandedQuery(), pool.article.publishedAt()), QueryType.EXPANDED)));
        if (issueAvailable) {
            String neutral = ReportRetrievalPolicy.query(ReportPerspectivePolicy.neutralQuery(claim.issue()), temporal);
            String challenge = neutral + " 정정";
            context.trace(t -> t.issue(id, claim.issue(), neutral, challenge));
            requests.add(new SearchRequest(SearchClient.Provider.NAVER, neutral, QueryType.NEUTRAL));
            requests.add(new SearchRequest(SearchClient.Provider.BRAVE, challenge, QueryType.CHALLENGE));
        }
        List<RetrievedHit> hits = searchAll(requests, context, id);
        hits = prioritize(hits, temporal, issueAvailable ? claim.issue() : null, context, id);
        List<String> candidates = collect(hits, 10, 10, pool, context, true, id);
        if (candidates.isEmpty()) return unconfirmed(id, claim.text(), "다른 언론사의 관련 기사 원문을 확보하지 못했습니다.");
        context.stage(Stage.SELECTING_EVIDENCE);
        ReportSelection selected = null;
        boolean perspectiveSelection = false;
        List<CandidateClaim> candidateClaims = List.of();
        if (issueAvailable) {
            try {
                PerspectiveSelection perspectives = generate("perspectives",
                    Map.of("claim", claim.text(), "issue", claim.issue(), "original", pool.article, "temporalContext", temporal,
                        "candidates", documents(candidates, pool)),
                    PerspectiveSelection.class, s -> ReportPerspectivePolicy.validate(s, candidates, pool.documents), context);
                perspectives.candidates().forEach(c -> context.trace(t -> t.perspective(id, c)));
                candidateClaims = perspectives.candidates();
                selected = new ReportSelection(ReportPerspectivePolicy.select(perspectives, candidates, 5).stream()
                    .map(c -> new Quote(c.evidenceId(), c.quote())).toList());
                perspectiveSelection = true;
            } catch (AnalysisFailure failure) {
                context.check();
                enrichmentFailure(id, "PERSPECTIVE_ANALYSIS_FAILED", "관점 분석에 실패해 기존 근거 선정을 사용합니다.", context);
            }
        }
        if (selected == null) selected = generate("select",
            Map.of("claim", claim.text(), "temporalContext", temporal, "candidates", documents(candidates, pool)),
            ReportSelection.class, s -> validateSelection(s == null ? null : s.evidence(), candidates, 5, pool), context);
        List<String> allowed = selected.evidence().stream().map(Quote::evidenceId).toList();
        if (perspectiveSelection) context.trace(t -> t.perspectiveSelection(id, candidates, allowed));
        else context.trace(t -> t.selection(id, candidates, allowed));
        selected.evidence().forEach(q -> pool.quote(q.evidenceId(), q.quote()));
        Map<String, LinkedHashSet<String>> reportQuotes = new LinkedHashMap<>();
        selected.evidence().forEach(q -> reportQuotes.computeIfAbsent(q.evidenceId(), ignored -> new LinkedHashSet<>())
            .add(DocumentFetcher.clean(q.quote())));
        if (allowed.isEmpty()) return unconfirmed(id, claim.text(), "동일 사건·대상·기간·지표에서 직접 비교할 본문 근거를 찾지 못했습니다.");
        context.stage(Stage.VERIFYING_CLAIMS);
        ReportReview review = generate("verify", Map.of("claim", claim.text(), "original", pool.article, "temporalContext", temporal,
                "articles", selectedDocuments(allowed, pool), "candidateClaims",
                candidateClaims.stream().filter(c -> allowed.contains(c.evidenceId())).toList()),
            ReportReview.class, r -> validateReportReview(r, allowed), context);
        context.trace(t -> review.comparisons().forEach(r ->
            t.comparison(id, r.evidenceId(), r.stance().name(), r.reason())));
        List<ReportComparison> comparisons = new ArrayList<>();
        boolean rejectedConflict = false;
        boolean rejectedMatch = false;
        for (ReportDecision decision : review.comparisons()) {
            if (decision.stance() == DecisionStance.UNRELATED) continue;
            if (decision.stance() == DecisionStance.MATCHES && !temporal.action().equals("NONE")) {
                MatchResolution resolution = ReportPerspectivePolicy.resolveMatch(decision.matchCheck(), claim.text(), pool.article,
                    pool.documents.get(decision.evidenceId()), issueAvailable ? claim.issue() : null);
                context.trace(t -> t.matchResolution(id, decision.evidenceId(), resolution));
                if (!resolution.accepted()) { rejectedMatch = true; continue; }
                pool.quote(decision.evidenceId(), resolution.check().externalQuote());
                reportQuotes.get(decision.evidenceId()).add(DocumentFetcher.clean(resolution.check().externalQuote()));
            }
            if (decision.stance() == DecisionStance.CONTRADICTS) {
                var resolution = ReportPerspectivePolicy.resolve(decision.conflictCheck(), pool.article,
                    pool.documents.get(decision.evidenceId()), issueAvailable ? claim.issue() : null);
                context.trace(t -> t.resolution(id, decision.evidenceId(), resolution));
                if (!resolution.accepted()) { rejectedConflict = true; continue; }
                pool.quote(decision.evidenceId(), resolution.check().externalQuote());
                reportQuotes.get(decision.evidenceId()).add(DocumentFetcher.clean(resolution.check().externalQuote()));
            }
            comparisons.add(new ReportComparison(decision.evidenceId(), ReportStance.valueOf(decision.stance().name()), decision.reason()));
            for (String quote : reportQuotes.get(decision.evidenceId()))
                context.trace(t -> t.usedQuote(id, decision.evidenceId(), quote));
        }
        if (comparisons.isEmpty())
            return unconfirmed(id, claim.text(), rejectedMatch
                ? "수치·변경 행위·비교 기준이 모두 확인되지 않아 일치를 인정하지 않았습니다."
                : rejectedConflict
                ? "동일 비교 조건과 양립 불가능한 본문 근거를 확인하지 못해 상반을 인정하지 않았습니다."
                : "선정된 후보가 동일한 사건의 비교 기사로 확인되지 않았습니다.");
        int matching = (int) comparisons.stream().filter(c -> c.stance() == ReportStance.MATCHES).count();
        int contradicting = comparisons.size() - matching;
        ReportVerdict verdict = matching > 0 && contradicting > 0 ? ReportVerdict.MIXED
            : matching > 0 ? ReportVerdict.MATCHED : ReportVerdict.CONTRADICTED;
        String reason = "다른 언론사의 일치 보도 " + matching + "건, 상반 보도 " + contradicting
            + "건을 확인했습니다. 보도 일치만으로 실제 사실이 확정되지는 않습니다.";
        return new CrossReport(id, claim.text(), verdict, reason, matching, contradicting, List.copyOf(comparisons), false);
    }

    private void enrichmentFailure(String id, String code, String message, AnalysisContext context) {
        Issue issue = new Issue(code, context.stage(), message);
        context.warn(issue);
        context.trace(t -> t.enrichmentFailure(id, issue));
    }

    private CrossReport unconfirmed(String id, String text, String reason) {
        return new CrossReport(id, text, ReportVerdict.UNCONFIRMED, reason, 0, 0, List.of(), false);
    }
    private boolean nearDeadline(AnalysisContext context) {
        return context.remaining().compareTo(Duration.ofSeconds(125)) < 0;
    }
    private void ensureBudget(AnalysisContext context) {
        context.check();
        if (nearDeadline(context))
            throw new AnalysisFailure("ANALYSIS_TIMEOUT", "남은 실행시간이 부족해 이 분석 항목을 완료하지 못했습니다.");
    }

    private ReviewedSource assessSource(String id, DraftSourceClaim claim, DraftSource source,
                                        EvidencePool pool, AnalysisContext context) {
        context.stage(Stage.ASSESSING_SOURCES);
        List<RetrievedHit> hits = searchAll(List.of(
            new SearchRequest(SearchClient.Provider.BRAVE, source.identityQuery()),
            new SearchRequest(SearchClient.Provider.BRAVE, source.quoteQuery())), context, null);
        List<String> candidates = collect(hits, 10, 10, pool, context, false, null);
        List<Quote> selected = List.of();
        if (!candidates.isEmpty()) {
            SourceSelection selection = generate("source-select",
                Map.of("claim", claim.text(), "source", source, "candidates", documents(candidates, pool)),
                SourceSelection.class, s -> validateSelection(s == null ? null : s.evidence(), candidates, 3, pool), context);
            selected = selection.evidence();
            selected.forEach(q -> pool.quote(q.evidenceId(), q.quote()));
        }
        List<String> allowed = selected.stream().map(Quote::evidenceId).toList();
        SourceReview review = generate("assess",
            Map.of("article", pool.article, "claim", claim.text(), "allClaimSources", claim.sources(),
                "source", source, "externalDocuments", selectedDocuments(allowed, pool)),
            SourceReview.class, r -> validateSourceReview(r, source, allowed, pool), context);
        review.evidence().forEach(q -> pool.quote(q.evidenceId(), q.quote()));
        return new ReviewedSource(id, source.name(), source.affiliation(), source.type(),
            source.citedInArticle(), review.verificationNote(), review.imvain().asMap(), false);
    }

    private ReviewedSource failedSource(String id, DraftSource source) {
        Map<String, Criterion> unknown = new LinkedHashMap<>();
        for (String key : CRITERIA) unknown.put(key, new Criterion(CriterionStatus.UNKNOWN,
            "평가를 완료하지 못했습니다.", List.of()));
        return new ReviewedSource(id, source.name(), source.affiliation(), source.type(),
            source.citedInArticle(), "외부 검색 또는 평가 처리에 실패했습니다.", Map.copyOf(unknown), true);
    }

    private List<RetrievedHit> searchAll(List<SearchRequest> requests, AnalysisContext context, String claimId) {
        ensureBudget(context);
        Map<SearchRequestKey, SearchRequest> unique = new LinkedHashMap<>();
        requests.forEach(r -> unique.putIfAbsent(new SearchRequestKey(r.provider(), r.query()), r));
        requests = List.copyOf(unique.values());
        List<CompletableFuture<List<RetrievedHit>>> tasks = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            SearchRequest request = requests.get(i);
            String requestId = "search-" + (i + 1);
            QueryType type = request.type();
            tasks.add(CompletableFuture.supplyAsync(() -> {
                if (claimId != null) context.trace(t -> t.searchStarted(claimId, requestId, request.provider(), type, request.query()));
                try {
                    List<SearchClient.Hit> found = search.search(request.provider(), request.query(), 5, context);
                    List<RetrievedHit> retrieved = new ArrayList<>();
                    for (int j = 0; j < found.size(); j++)
                        retrieved.add(new RetrievedHit(found.get(j), requestId + "-candidate-" + (j + 1), type));
                    if (claimId != null) context.trace(t -> {
                        t.searchFinished(claimId, requestId, found.size(), null);
                        retrieved.forEach(hit -> t.candidate(claimId, hit.candidateId(), requestId, request.provider(), hit.hit()));
                    });
                    return retrieved;
                } catch (RuntimeException e) {
                    if (claimId != null) context.trace(t -> t.searchFinished(claimId, requestId, null,
                        safeFailure(e, Stage.RETRIEVING_EVIDENCE)));
                    throw e;
                }
            }, searches));
        }
        List<List<RetrievedHit>> batches = new ArrayList<>();
        int succeeded = 0;
        for (var task : tasks) {
            try {
                batches.add(task.join());
                succeeded++;
            } catch (CompletionException e) {
                context.check();
                context.warn(new Issue("SEARCH_FAILED", context.stage(), "일부 검색 제공자 요청이 실패했습니다."));
            }
        }
        if (succeeded == 0) throw new AnalysisFailure("SEARCH_FAILED", "외부 검색을 수행하지 못했습니다.");
        if (claimId == null) return batches.stream().flatMap(List::stream).toList();
        List<RetrievedHit> hits = new ArrayList<>();
        // Interleave provider/query ranks so exact queries cannot consume the entire fetch budget.
        int ranks = batches.stream().mapToInt(List::size).max().orElse(0);
        for (int rank = 0; rank < ranks; rank++)
            for (List<RetrievedHit> batch : batches) if (rank < batch.size()) hits.add(batch.get(rank));
        return hits;
    }

    private List<String> collect(List<RetrievedHit> hits, int maxAttempts, int maxDocuments,
                                 EvidencePool pool, AnalysisContext context, boolean newsroomOnly, String claimId) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        Set<String> visited = new HashSet<>();
        int attempts = 0, failures = 0;
        for (int index = 0; index < hits.size(); index++) {
            RetrievedHit retrieved = hits.get(index);
            SearchClient.Hit hit = retrieved.hit();
            ensureBudget(context);
            if (attempts >= maxAttempts || ids.size() >= maxDocuments) {
                for (RetrievedHit skipped : hits.subList(index, hits.size()))
                    traceEvent(context, claimId, skipped, "COLLECTION_LIMIT_REACHED", "기존 본문 수집 시도 또는 문서 수 한도로 처리하지 않았습니다.");
                break;
            }
            String key;
            try { key = canonical(urls.validate(hit.url()).toString()); }
            catch (ApiException e) {
                traceEvent(context, claimId, retrieved, "URL_REJECTED", e.getMessage());
                continue;
            }
            if (!visited.add(key)) {
                traceEvent(context, claimId, retrieved, "DUPLICATE_URL", "이미 방문한 후보 URL입니다.");
                continue;
            }
            if (key.equals(canonical(pool.article.url()))) {
                traceEvent(context, claimId, retrieved, "ORIGINAL_ARTICLE_URL", "분석 대상 기사와 같은 URL입니다.");
                continue;
            }
            attempts++;
            try {
                Document doc = pool.cache.get(key);
                if (doc == null) {
                    traceEvent(context, claimId, retrieved, "FETCH_STARTED", null);
                    doc = fetcher.fetch(key, false, context);
                    pool.cache.put(key, doc);
                    traceEvent(context, claimId, retrieved, "FETCH_SUCCEEDED", null);
                } else traceEvent(context, claimId, retrieved, "DOCUMENT_CACHE_HIT", "이 작업에서 이미 수집한 본문을 재사용했습니다.");
                Document collected = doc;
                if (claimId != null) context.trace(t -> t.document(claimId, retrieved.candidateId(), collected));
                if (newsroomOnly ? exactContent(doc.body(), pool.article.body()) : sameContent(doc.body(), pool.article.body())) {
                    traceEvent(context, claimId, retrieved, "SAME_CONTENT_AS_ORIGINAL", "분석 대상 기사와 본문이 같습니다.");
                    continue;
                }
                if (newsroomOnly && sameContent(doc.body(), pool.article.body()))
                    traceEvent(context, claimId, retrieved, "SIMILAR_CONTENT_RETAINED", "본문이 유사해도 수치·부정·정정 차이를 분석하도록 보존했습니다.");
                if (newsroomOnly) {
                    if (sameHost(doc.url(), pool.article.url())) {
                        traceEvent(context, claimId, retrieved, "SAME_HOST_AS_ORIGINAL", "분석 대상 기사와 호스트가 같습니다.");
                        continue;
                    }
                    String originalPublisher = nonblank(pool.article.publisher())
                        ? pool.article.publisher() : URI.create(pool.article.url()).getHost();
                    String candidatePublisher = nonblank(doc.publisher()) ? doc.publisher() : URI.create(doc.url()).getHost();
                    if (samePublisher(candidatePublisher, originalPublisher)) {
                        traceEvent(context, claimId, retrieved, "SAME_PUBLISHER_AS_ORIGINAL", "분석 대상 기사와 발행 언론사가 같습니다.");
                        continue;
                    }
                    if (!nonblank(doc.publisher()))
                        doc = new Document(doc.url(), doc.title(), candidatePublisher, doc.author(),
                            doc.publishedAt(), doc.body(), doc.truncated());
                }
                Document accepted = doc;
                if (claimId != null) context.trace(t -> t.document(claimId, retrieved.candidateId(), accepted));
                int before = pool.documents.size();
                String id = pool.add(doc, newsroomOnly);
                if (id != null) {
                    ids.add(id);
                    if (claimId != null) context.trace(t -> t.evidence(claimId, retrieved.candidateId(), id));
                    traceEvent(context, claimId, retrieved, pool.documents.size() == before ? "EVIDENCE_REUSED" : "CANDIDATE_ACCEPTED",
                        pool.documents.size() == before ? "기존 URL 또는 본문과 중복되어 같은 evidenceId로 연결했습니다." : null);
                }
            } catch (AnalysisFailure e) {
                traceEvent(context, claimId, retrieved, e.code(), e.issue(context.stage()).message());
                context.check();
                failures++;
            } catch (ApiException e) {
                traceEvent(context, claimId, retrieved, "FETCH_URL_REJECTED", e.getMessage());
                // Unsafe redirect destinations are excluded.
            } catch (RuntimeException e) {
                traceEvent(context, claimId, retrieved, "CANDIDATE_PROCESSING_FAILED", safeFailure(e, context.stage()).message());
                throw e;
            }
        }
        if (attempts > 0 && failures == attempts)
            throw new AnalysisFailure("SEARCH_FAILED", "검색 후보 원문을 모두 수집하지 못했습니다.");
        if (failures > 0) context.warn(new Issue("ARTICLE_FETCH_FAILED", context.stage(), "일부 외부 자료의 원문을 확보하지 못했습니다."));
        return List.copyOf(ids);
    }

    private static void traceEvent(AnalysisContext context, String claimId, RetrievedHit hit, String code, String message) {
        if (claimId != null) context.trace(t -> t.event(claimId, hit.candidateId(), code, message));
    }
    private static Issue safeFailure(RuntimeException failure, Stage stage) {
        if (failure instanceof AnalysisFailure known) return known.issue(stage);
        if (failure instanceof ApiException known) return new Issue(known.code(), stage, known.getMessage());
        // Never persist arbitrary exception messages, remote bodies, headers, or credentials.
        return new Issue("INTERNAL_SERVER_ERROR", stage, "처리 중 예기치 않은 오류가 발생했습니다. (" + failure.getClass().getSimpleName() + ")");
    }
    private List<RetrievedHit> prioritize(List<RetrievedHit> hits, TemporalContext temporal, IssueFrame issue,
                                          AnalysisContext context, String claimId) {
        List<ReportRetrievalPolicy.Ranked> ranked = ReportRetrievalPolicy.rank(hits.stream()
            .map(h -> new ReportRetrievalPolicy.Candidate(h.candidateId(), h.hit(), h.type())).toList(), temporal, issue);
        List<RetrievedHit> eligible = new ArrayList<>();
        for (var entry : ranked) {
            var candidate = entry.candidate();
            context.trace(t -> t.prefetch(claimId, candidate.id(), entry.priority()));
            RetrievedHit hit = new RetrievedHit(candidate.hit(), candidate.id(), candidate.type());
            if (entry.priority().eligible()) eligible.add(hit);
            else traceEvent(context, claimId, hit, entry.priority().reasons().contains("DUPLICATE_URL")
                ? "DUPLICATE_URL" : "PREFETCH_REJECTED", String.join(", ", entry.priority().reasons()));
        }
        return List.copyOf(eligible);
    }
    private record RetrievedHit(SearchClient.Hit hit, String candidateId, QueryType type) {}

    private List<Map<String, Object>> documents(List<String> ids, EvidencePool pool) {
        return ids.stream().map(id -> Map.<String, Object>of("evidenceId", id, "document", pool.documents.get(id))).toList();
    }
    private List<Map<String, Object>> selectedDocuments(List<String> ids, EvidencePool pool) {
        return ids.stream().map(id -> Map.<String, Object>of("evidenceId", id, "document", pool.documents.get(id),
            "quotes", List.copyOf(pool.quotes.get(id)))).toList();
    }
    private <T> T generate(String prompt, Object input, Class<T> type, Consumer<T> validator, AnalysisContext context) {
        ensureBudget(context);
        return llm.generate(prompt, json.writeValueAsString(input), type, validator, context);
    }

    private void validateExtraction(Extraction value, Document article) {
        require(value != null && value.crossReportClaims() != null && value.crossReportClaims().size() <= 3
            && value.sourceClaims() != null && value.sourceClaims().size() <= 3);
        Set<String> crossTexts = new HashSet<>();
        for (DraftCrossClaim claim : value.crossReportClaims()) {
            require(claim != null && nonblank(claim.text()) && crossTexts.add(claim.text())
                && validQuery(claim.exactQuery()) && validQuery(claim.expandedQuery()));
        }
        Set<String> sourceTexts = new HashSet<>();
        for (DraftSourceClaim claim : value.sourceClaims()) {
            require(claim != null && nonblank(claim.text()) && sourceTexts.add(claim.text())
                && claim.sources() != null && !claim.sources().isEmpty());
            for (DraftSource source : claim.sources()) {
                require(source != null && nonblank(source.name()) && source.affiliation() != null && source.type() != null
                    && contains(article.title() + " " + article.body(), source.name())
                    && (source.affiliation().isBlank() || contains(article.title() + " " + article.body(), source.affiliation()))
                    && nonblank(source.citedInArticle()) && contains(article.body(), source.citedInArticle())
                    && validQuery(source.identityQuery()) && validQuery(source.quoteQuery()));
            }
        }
    }
    private void validateSelection(List<Quote> selected, List<String> candidates, int max, EvidencePool pool) {
        require(selected != null && selected.size() <= max);
        Set<String> seen = new HashSet<>();
        for (Quote quote : selected) {
            require(quote != null && candidates.contains(quote.evidenceId()) && seen.add(quote.evidenceId())
                && nonblank(quote.quote()) && quote.quote().length() <= 1000
                && contains(pool.documents.get(quote.evidenceId()).body(), quote.quote()));
        }
    }
    private void validateReportReview(ReportReview review, List<String> allowed) {
        require(review != null && review.comparisons() != null && review.comparisons().size() == allowed.size());
        Set<String> seen = new HashSet<>();
        for (ReportDecision decision : review.comparisons())
            require(decision != null && allowed.contains(decision.evidenceId()) && seen.add(decision.evidenceId())
                && decision.stance() != null && nonblank(decision.reason()));
    }
    private void validateSourceReview(SourceReview review, DraftSource source, List<String> allowed, EvidencePool pool) {
        require(review != null && nonblank(review.verificationNote()) && review.imvain() != null && review.evidence() != null);
        Set<String> quoted = new HashSet<>();
        for (Quote quote : review.evidence()) {
            require(quote != null && allowed.contains(quote.evidenceId()) && nonblank(quote.quote())
                && quote.quote().length() <= 1000 && contains(pool.documents.get(quote.evidenceId()).body(), quote.quote()));
            quoted.add(quote.evidenceId());
        }
        for (String key : CRITERIA) {
            Criterion criterion = review.imvain().asMap().get(key);
            require(criterion != null && criterion.status() != null && nonblank(criterion.reason())
                && criterion.evidenceIds() != null && allowed.containsAll(criterion.evidenceIds())
                && quoted.containsAll(criterion.evidenceIds()));
        }
        if (!source.identifiable()) require(review.imvain().named().status() == CriterionStatus.NOT_MET);
        if (allowed.isEmpty()) for (String key : List.of("independent", "verify", "authoritative", "informed"))
            require(review.imvain().asMap().get(key).status() == CriterionStatus.UNKNOWN);
    }
    private void validateSummary(OverallSummary summary) {
        require(summary != null && nonblank(summary.overallComment()) && nonblank(summary.crossReportOverview())
            && nonblank(summary.sourceAssessmentOverview()) && summary.unverifiedPoints() != null);
        summary.unverifiedPoints().forEach(point -> require(nonblank(point)));
    }
    static void require(boolean condition) { if (!condition) throw new IllegalArgumentException("Invalid grounded model output"); }
    private static boolean nonblank(String value) { return value != null && !value.isBlank(); }
    private static boolean validQuery(String value) { return nonblank(value) && value.length() <= 300; }
    static boolean contains(String body, String quote) { return DocumentFetcher.clean(body).contains(DocumentFetcher.clean(quote)); }
    private static boolean samePublisher(String a, String b) {
        return nonblank(a) && nonblank(b) && DocumentFetcher.clean(a).equalsIgnoreCase(DocumentFetcher.clean(b));
    }
    private static boolean sameHost(String a, String b) {
        return URI.create(a).getHost().equalsIgnoreCase(URI.create(b).getHost());
    }
    private static String reportId(int i) { return "report-" + (i + 1); }
    private static String sourceClaimId(int i) { return "source-claim-" + (i + 1); }
    private static String sourceId(int i, int j) { return "source-" + (i + 1) + "-" + (j + 1); }
    static String canonical(String url) {
        URI uri = URI.create(url);
        String query = uri.getRawQuery();
        if (query != null) query = Arrays.stream(query.split("&"))
            .filter(p -> !p.toLowerCase(Locale.ROOT).matches("(utm_[^=]*|fbclid|gclid)=.*"))
            .collect(Collectors.joining("&"));
        String path = uri.getRawPath();
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority().toLowerCase(Locale.ROOT)
            + (path == null || path.isEmpty() ? "/" : path) + (query == null || query.isEmpty() ? "" : "?" + query);
    }
    static boolean sameContent(String a, String b) {
        String x = DocumentFetcher.clean(a).toLowerCase(Locale.ROOT), y = DocumentFetcher.clean(b).toLowerCase(Locale.ROOT);
        if (x.equals(y)) return true;
        if (Math.min(x.length(), y.length()) < 100) return false;
        Set<String> sx = shingles(x), sy = shingles(y);
        long shared = sx.stream().filter(sy::contains).count();
        return shared / (double) Math.max(sx.size(), sy.size()) > 0.85;
    }
    static boolean exactContent(String a, String b) {
        return DocumentFetcher.clean(a).equalsIgnoreCase(DocumentFetcher.clean(b));
    }
    private static Set<String> shingles(String text) {
        Set<String> values = new HashSet<>();
        for (int i = 0; i + 12 <= text.length(); i += 6) values.add(text.substring(i, i + 12));
        return values;
    }
    private record SearchRequest(SearchClient.Provider provider, String query, QueryType type) {
        SearchRequest(SearchClient.Provider provider, String query) { this(provider, query, QueryType.EXACT); }
    }
    private record SearchRequestKey(SearchClient.Provider provider, String query) {}
    private static final class EvidencePool {
        final Document article;
        final Map<String, Document> cache = new ConcurrentHashMap<>();
        final Map<String, Document> documents = new LinkedHashMap<>();
        final Map<String, LinkedHashSet<String>> quotes = new LinkedHashMap<>();
        EvidencePool(Document article) { this.article = article; }
        String add(Document doc, boolean dedupeContent) {
            for (var entry : documents.entrySet())
                if (canonical(entry.getValue().url()).equals(canonical(doc.url()))
                    || (dedupeContent && exactContent(entry.getValue().body(), doc.body()))) return entry.getKey();
            String id = "evidence-" + (documents.size() + 1);
            documents.put(id, doc);
            return id;
        }
        void quote(String id, String text) {
            quotes.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(DocumentFetcher.clean(text));
        }
        List<Evidence> usedEvidence(List<CrossReport> reports, List<SourceAssessment> assessments) {
            Set<String> used = new LinkedHashSet<>();
            reports.forEach(r -> r.comparisons().forEach(c -> used.add(c.evidenceId())));
            assessments.forEach(a -> a.sources().forEach(s ->
                s.imvain().values().forEach(c -> used.addAll(c.evidenceIds()))));
            return used.stream().map(id -> {
                Document doc = documents.get(id);
                return new Evidence(id, doc.title(), doc.publisher(), doc.url(), doc.publishedAt(),
                    String.join(" … ", quotes.getOrDefault(id, new LinkedHashSet<>())));
            }).toList();
        }
    }
    @PreDestroy public void close() { searches.shutdownNow(); }
}
