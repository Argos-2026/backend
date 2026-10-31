package org.argos.argos_backend.analysis.service;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.argos.argos_backend.analysis.domain.AnalysisContext;
import org.argos.argos_backend.analysis.domain.AnalysisFailure;
import org.argos.argos_backend.external.document.DocumentFetcher;
import org.argos.argos_backend.external.http.SafeUrlPolicy;
import org.argos.argos_backend.external.llm.StructuredLlm;
import org.argos.argos_backend.external.search.SearchClient;
import org.argos.argos_backend.global.exception.ApiException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import static org.argos.argos_backend.analysis.domain.AnalysisModels.*;
import static org.argos.argos_backend.external.document.DocumentFetcher.Document;
import static org.argos.argos_backend.external.llm.LlmModels.*;

@Component
public class AnalysisPipeline {
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
        EvidencePool pool = new EvidencePool(article);
        List<CrossReport> crossReports = new ArrayList<>();
        for (int i = 0; i < extraction.crossReportClaims().size(); i++) {
            context.check();
            DraftCrossClaim draft = extraction.crossReportClaims().get(i);
            if (nearDeadline(context) && crossReports.stream().anyMatch(r -> !r.analysisFailed())) {
                context.warn(new Issue("ANALYSIS_TIMEOUT", Stage.RETRIEVING_EVIDENCE,
                    "남은 실행시간이 부족해 일부 교차보도 분석을 생략했습니다."));
                crossReports.add(new CrossReport(reportId(i), draft.text(), ReportVerdict.UNCONFIRMED,
                    "실행시간 제한으로 비교를 완료하지 못했습니다.", 0, 0, List.of(), true));
                continue;
            }
            try {
                crossReports.add(analyzeReport(reportId(i), draft, pool, context));
            } catch (AnalysisFailure e) {
                context.check();
                context.warn(new Issue(e.code().equals("ANALYSIS_TIMEOUT") ? "ANALYSIS_TIMEOUT" : "CROSS_REPORT_FAILED",
                    context.stage(), "일부 교차보도 분석을 완료하지 못했습니다."));
                crossReports.add(new CrossReport(reportId(i), draft.text(), ReportVerdict.UNCONFIRMED,
                    "외부 검색 또는 비교 처리에 실패하여 확인하지 못했습니다.", 0, 0, List.of(), true));
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
            input.put("crossReports", crossReports);
            input.put("sourceAssessments", sourceAssessments);
            input.put("limitations", limitations);
            input.put("warnings", context.warnings());
            OverallSummary generated = generate("summarize", input, OverallSummary.class, this::validateSummary, context);
            List<String> points = new ArrayList<>(generated.unverifiedPoints());
            for (String limitation : limitations) if (!points.contains(limitation)) points.add(limitation);
            summary = new Summary(generated.overallComment(), generated.crossReportOverview(),
                generated.sourceAssessmentOverview(), List.copyOf(points));
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

    private CrossReport analyzeReport(String id, DraftCrossClaim claim, EvidencePool pool, AnalysisContext context) {
        context.stage(Stage.RETRIEVING_EVIDENCE);
        List<SearchRequest> requests = List.of(
            new SearchRequest(SearchClient.Provider.NAVER, claim.exactQuery()),
            new SearchRequest(SearchClient.Provider.BRAVE, claim.exactQuery()),
            new SearchRequest(SearchClient.Provider.NAVER, claim.expandedQuery()),
            new SearchRequest(SearchClient.Provider.BRAVE, claim.expandedQuery()));
        List<SearchClient.Hit> hits = searchAll(requests, context);
        List<String> candidates = collect(hits, 10, 10, pool, context, true);
        if (candidates.isEmpty()) return unconfirmed(id, claim.text(), "다른 언론사의 관련 기사 원문을 확보하지 못했습니다.");
        context.stage(Stage.SELECTING_EVIDENCE);
        ReportSelection selected = generate("select", Map.of("claim", claim, "candidates", documents(candidates, pool)),
            ReportSelection.class, s -> validateSelection(s == null ? null : s.evidence(), candidates, 5, pool), context);
        List<String> allowed = selected.evidence().stream().map(Quote::evidenceId).toList();
        selected.evidence().forEach(q -> pool.quote(q.evidenceId(), q.quote()));
        if (allowed.isEmpty()) return unconfirmed(id, claim.text(), "주체·시기·지역·수치가 일치하는 비교 기사를 찾지 못했습니다.");
        context.stage(Stage.VERIFYING_CLAIMS);
        ReportReview review = generate("verify", Map.of("claim", claim, "articles", selectedDocuments(allowed, pool)),
            ReportReview.class, r -> validateReportReview(r, allowed), context);
        List<ReportComparison> comparisons = review.comparisons().stream()
            .filter(r -> r.stance() != DecisionStance.UNRELATED)
            .map(r -> new ReportComparison(r.evidenceId(), ReportStance.valueOf(r.stance().name()), r.reason())).toList();
        if (comparisons.isEmpty())
            return unconfirmed(id, claim.text(), "선정된 후보가 동일한 사건의 비교 기사로 확인되지 않았습니다.");
        int matching = (int) comparisons.stream().filter(c -> c.stance() == ReportStance.MATCHES).count();
        int contradicting = comparisons.size() - matching;
        ReportVerdict verdict = matching > 0 && contradicting > 0 ? ReportVerdict.MIXED
            : matching > 0 ? ReportVerdict.MATCHED : ReportVerdict.CONTRADICTED;
        String reason = "다른 언론사의 일치 보도 " + matching + "건, 상반 보도 " + contradicting
            + "건을 확인했습니다. 보도 일치만으로 실제 사실이 확정되지는 않습니다.";
        return new CrossReport(id, claim.text(), verdict, reason, matching, contradicting, comparisons, false);
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
        List<SearchClient.Hit> hits = searchAll(List.of(
            new SearchRequest(SearchClient.Provider.BRAVE, source.identityQuery()),
            new SearchRequest(SearchClient.Provider.BRAVE, source.quoteQuery())), context);
        List<String> candidates = collect(hits, 10, 10, pool, context, false);
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

    private List<SearchClient.Hit> searchAll(List<SearchRequest> requests, AnalysisContext context) {
        ensureBudget(context);
        List<CompletableFuture<List<SearchClient.Hit>>> tasks = requests.stream().map(request ->
            CompletableFuture.supplyAsync(() -> search.search(request.provider(), request.query(), 5, context), searches)).toList();
        List<SearchClient.Hit> hits = new ArrayList<>();
        int succeeded = 0;
        for (var task : tasks) {
            try {
                hits.addAll(task.join());
                succeeded++;
            } catch (CompletionException e) {
                context.check();
                context.warn(new Issue("SEARCH_FAILED", context.stage(), "일부 검색 제공자 요청이 실패했습니다."));
            }
        }
        if (succeeded == 0) throw new AnalysisFailure("SEARCH_FAILED", "외부 검색을 수행하지 못했습니다.");
        return hits;
    }

    private List<String> collect(List<SearchClient.Hit> hits, int maxAttempts, int maxDocuments,
                                 EvidencePool pool, AnalysisContext context, boolean newsroomOnly) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        Set<String> visited = new HashSet<>();
        int attempts = 0, failures = 0;
        for (SearchClient.Hit hit : hits) {
            ensureBudget(context);
            if (attempts >= maxAttempts || ids.size() >= maxDocuments) break;
            String key;
            try { key = canonical(urls.validate(hit.url()).toString()); }
            catch (ApiException e) { continue; }
            if (!visited.add(key) || key.equals(canonical(pool.article.url()))) continue;
            attempts++;
            try {
                Document doc = pool.cache.get(key);
                if (doc == null) {
                    doc = fetcher.fetch(key, false, context);
                    pool.cache.put(key, doc);
                }
                if (sameContent(doc.body(), pool.article.body())) continue;
                if (newsroomOnly) {
                    if (sameHost(doc.url(), pool.article.url())) continue;
                    String originalPublisher = nonblank(pool.article.publisher())
                        ? pool.article.publisher() : URI.create(pool.article.url()).getHost();
                    String candidatePublisher = nonblank(doc.publisher()) ? doc.publisher() : URI.create(doc.url()).getHost();
                    if (samePublisher(candidatePublisher, originalPublisher)) continue;
                    if (!nonblank(doc.publisher()))
                        doc = new Document(doc.url(), doc.title(), candidatePublisher, doc.author(),
                            doc.publishedAt(), doc.body(), doc.truncated());
                }
                String id = pool.add(doc, newsroomOnly);
                if (id != null) ids.add(id);
            } catch (AnalysisFailure e) {
                context.check();
                failures++;
            } catch (ApiException e) {
                // Unsafe redirect destinations are excluded.
            }
        }
        if (attempts > 0 && failures == attempts)
            throw new AnalysisFailure("SEARCH_FAILED", "검색 후보 원문을 모두 수집하지 못했습니다.");
        if (failures > 0) context.warn(new Issue("ARTICLE_FETCH_FAILED", context.stage(), "일부 외부 자료의 원문을 확보하지 못했습니다."));
        return List.copyOf(ids);
    }

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
    private static Set<String> shingles(String text) {
        Set<String> values = new HashSet<>();
        for (int i = 0; i + 12 <= text.length(); i += 6) values.add(text.substring(i, i + 12));
        return values;
    }
    private record SearchRequest(SearchClient.Provider provider, String query) {}
    private static final class EvidencePool {
        final Document article;
        final Map<String, Document> cache = new ConcurrentHashMap<>();
        final Map<String, Document> documents = new LinkedHashMap<>();
        final Map<String, LinkedHashSet<String>> quotes = new LinkedHashMap<>();
        EvidencePool(Document article) { this.article = article; }
        String add(Document doc, boolean dedupeContent) {
            for (var entry : documents.entrySet())
                if (canonical(entry.getValue().url()).equals(canonical(doc.url()))
                    || (dedupeContent && sameContent(entry.getValue().body(), doc.body()))) return entry.getKey();
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
