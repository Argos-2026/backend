package org.argos.argos_backend.analysis.service;

import java.net.URI;
import java.util.*;
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
    public AnalysisPipeline(DocumentFetcher fetcher, SearchClient search, StructuredLlm llm, SafeUrlPolicy urls, JsonMapper json) {
        this.fetcher = fetcher; this.search = search; this.llm = llm; this.urls = urls; this.json = json;
    }

    public Result run(UUID id, String url, AnalysisContext context) {
        context.stage(Stage.FETCHING_ARTICLE);
        Document article = fetcher.fetch(url, true, context);
        context.stage(Stage.EXTRACTING_CLAIMS);
        Extraction extracted = generate("extract", Map.of("article", article), Extraction.class,
            x -> validateExtraction(x, article.body()), context);
        var pool = new EvidencePool(article);
        Map<String, List<String>> candidates = new LinkedHashMap<>();
        context.stage(Stage.RETRIEVING_EVIDENCE);
        for (int i = 0; i < extracted.claims().size(); i++) {
            List<SearchClient.Hit> hits = new ArrayList<>();
            int succeeded = 0;
            for (String query : extracted.claims().get(i).queries()) for (var provider : SearchClient.Provider.values()) {
                try { hits.addAll(search.search(provider, query, 5, context)); succeeded++; }
                catch (AnalysisFailure e) { context.check(); context.warn(new Issue("SEARCH_FAILED", context.stage(), "일부 검색 제공자 요청이 실패했습니다.")); }
            }
            if (succeeded == 0) throw new AnalysisFailure("SEARCH_FAILED", "외부 검색을 수행하지 못했습니다.");
            candidates.put(claimId(i), collect(hits, 5, pool, context, false));
        }

        context.stage(Stage.SELECTING_EVIDENCE);
        Selection selection = extracted.claims().isEmpty() ? new Selection(List.of()) : generate("select",
            Map.of("claims", claimInputs(extracted), "candidates", candidateInputs(candidates, pool)), Selection.class,
            s -> validateSelection(s, candidates, pool), context);
        Map<String, List<String>> allowed = new LinkedHashMap<>();
        for (SelectedClaim selected : selection.claims()) {
            allowed.put(selected.claimId(), selected.evidence().stream().map(Quote::evidenceId).toList());
            for (Quote quote : selected.evidence()) pool.quote(quote.evidenceId(), quote.quote());
        }

        context.stage(Stage.VERIFYING_CLAIMS);
        boolean noEvidence = allowed.values().stream().allMatch(List::isEmpty);
        Verification verified;
        if (noEvidence) {
            List<VerifiedClaim> insufficient = new ArrayList<>();
            for (int i = 0; i < extracted.claims().size(); i++) insufficient.add(new VerifiedClaim(claimId(i), Verdict.INSUFFICIENT,
                "검색한 자료에서 판단에 충분한 원문 근거를 확보하지 못했습니다.", List.of(), false, List.of()));
            verified = new Verification(List.copyOf(insufficient));
        } else verified = generate("verify", Map.of("claims", claimInputs(extracted), "evidence", selectedInputs(allowed, pool)),
            Verification.class, v -> validateVerification(v, allowed, pool), context);
        verified = new Verification(verified.claims().stream()
            .map(v -> ClaimVerificationGuard.enforce(extracted.claims().get(index(v.claimId())), v)).toList());
        for (VerifiedClaim claim : verified.claims()) {
            for (Quote quote : claim.attributionQuotes()) pool.quote(quote.evidenceId(), quote.quote());
        }

        context.stage(Stage.ASSESSING_SOURCES);
        List<ArticleSource> sources = null;
        try {
            Map<String, List<String>> sourceEvidence = new LinkedHashMap<>();
            for (int i = 0; i < extracted.sources().size(); i++) {
                DraftSource source = extracted.sources().get(i);
                List<String> sourceDocs = List.of();
                if (source.identifiable()) {
                    try {
                        sourceDocs = collect(search.search(SearchClient.Provider.BRAVE,
                            source.name() + " " + extracted.claims().stream().findFirst().map(DraftClaim::question).orElse("소속 공식 자료"),
                            3, context), 3, pool, context, true);
                    } catch (AnalysisFailure e) {
                        context.check();
                        throw new AnalysisFailure("SOURCE_ASSESSMENT_FAILED", "정보원 외부 확인을 완료하지 못했습니다.");
                    }
                }
                sourceEvidence.put(sourceId(i), sourceDocs);
            }
            Assessment assessment = extracted.sources().isEmpty() ? new Assessment(List.of()) : generate("assess",
                Map.of("article", article, "claims", claimInputs(extracted), "sources", sourceInputs(extracted),
                    "externalDocuments", candidateInputs(sourceEvidence, pool)), Assessment.class,
                a -> validateAssessment(a, extracted, sourceEvidence, pool), context);
            sources = new ArrayList<>();
            for (SourceReview review : assessment.sources()) {
                DraftSource source = extracted.sources().get(index(review.sourceId()));
                for (Quote quote : review.evidence()) pool.quote(quote.evidenceId(), quote.quote());
                sources.add(new ArticleSource(review.sourceId(), source.name(), source.type(), source.citedInArticle(), review.verificationNote(), review.imvain().asMap()));
            }
            sources = List.copyOf(sources);
        } catch (AnalysisFailure e) {
            context.check();
            context.warn(new Issue("SOURCE_ASSESSMENT_FAILED", Stage.ASSESSING_SOURCES, "정보원 평가를 완료하지 못했습니다. 사실 검증 결과는 확인할 수 있습니다."));
        }

        List<Claim> claims = new ArrayList<>();
        for (VerifiedClaim v : verified.claims()) {
            List<String> linkedSources = new ArrayList<>();
            if (sources != null) for (int s = 0; s < extracted.sources().size(); s++)
                if (extracted.sources().get(s).claimIndexes().contains(index(v.claimId()))) linkedSources.add(sourceId(s));
            claims.add(new Claim(v.claimId(), extracted.claims().get(index(v.claimId())).text(), v.verdict(), v.reason(), List.copyOf(linkedSources), v.evidenceLinks()));
        }
        context.stage(Stage.AGGREGATING_RESULT);
        List<String> limitations = new ArrayList<>();
        if (article.truncated()) limitations.add("기사가 길어 정제 본문 앞부분 30,000자만 분석했습니다.");
        if (pool.documents.values().stream().anyMatch(Document::truncated)) limitations.add("일부 외부 자료는 본문 30,000자 또는 PDF 앞 50페이지 범위에서 확인했습니다.");
        if (extracted.sourcesTruncated()) limitations.add("기사 내부 주요 정보원 최대 6개를 선정해 평가했습니다.");
        Summary summary;
        try {
            var input = new LinkedHashMap<String, Object>();
            input.put("claims", claims); input.put("articleSources", sources); input.put("limitations", limitations); input.put("warnings", context.warnings());
            OverallSummary generated = generate("summarize", input, OverallSummary.class, this::validateSummary, context);
            List<String> points = new ArrayList<>(generated.unverifiedPoints());
            for (String limitation : limitations) if (!points.contains(limitation)) points.add(limitation);
            summary = new Summary(generated.overallComment(), generated.factCheckOverview(), sources == null ? null : generated.sourceAssessmentOverview(), List.copyOf(points));
        } catch (AnalysisFailure e) {
            context.check();
            context.warn(new Issue("LLM_FAILED", Stage.AGGREGATING_RESULT, "종합 요약 생성에 실패하여 주장별 결과로 기본 요약을 구성했습니다."));
            summary = new Summary(claims.isEmpty() ? "검증 가능한 핵심 주장을 찾지 못했습니다." : "주장별 검증 결과와 원문 근거를 확인해 주세요.",
                claims.stream().map(c -> c.text() + ": " + c.verdict() + " — " + c.reason()).collect(Collectors.joining("\n")),
                sources == null ? null : "기사 내부 정보원 " + sources.size() + "개를 개별 기준으로 평가했습니다.", List.copyOf(limitations));
        }
        context.check();
        Status status = context.warnings().isEmpty() ? Status.COMPLETED : Status.PARTIAL_COMPLETED;
        return new Result(id, status, claims.isEmpty() ? "NO_VERIFIABLE_CLAIMS" : "ANALYZED", article.article(), summary,
            List.copyOf(claims), pool.usedEvidence(claims, sources), sources, context.warnings());
    }

    private List<String> collect(List<SearchClient.Hit> hits, int limit, EvidencePool pool, AnalysisContext context, boolean source) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        Set<String> visited = new HashSet<>();
        int attempts = 0, failures = 0, successes = 0;
        for (SearchClient.Hit hit : hits) {
            context.check();
            if (attempts >= limit) break;
            String key;
            try { key = canonical(urls.validate(hit.url()).toString()); }
            catch (ApiException e) { continue; }
            if (!visited.add(key) || key.equals(canonical(pool.article.url()))) continue;
            attempts++;
            try {
                Document doc = pool.cache.get(key);
                if (doc == null) { doc = fetcher.fetch(key, false, context); pool.cache.put(key, doc); }
                successes++;
                if (sameContent(doc.body(), pool.article.body())) continue;
                String id = pool.add(doc);
                if (id != null) ids.add(id);
            } catch (AnalysisFailure e) { context.check(); failures++; }
            catch (ApiException e) { /* Unsafe redirect destinations are excluded. */ }
        }
        if (attempts > 0 && failures == attempts) throw new AnalysisFailure(source ? "SOURCE_ASSESSMENT_FAILED" : "SEARCH_FAILED", "검색 결과 원문을 모두 수집하지 못했습니다.");
        if (failures > 0 && successes > 0) context.warn(new Issue(source ? "SOURCE_ASSESSMENT_FAILED" : "SEARCH_FAILED", context.stage(), "일부 외부 자료의 원문을 확보하지 못했습니다."));
        return List.copyOf(ids);
    }

    private <T> T generate(String prompt, Object input, Class<T> type, Consumer<T> validator, AnalysisContext context) {
        return llm.generate(prompt, json.writeValueAsString(input), type, validator, context);
    }
    private List<Map<String, Object>> claimInputs(Extraction extraction) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (int i = 0; i < extraction.claims().size(); i++) values.add(Map.of("claimId", claimId(i), "claim", extraction.claims().get(i)));
        return values;
    }
    private List<Map<String, Object>> sourceInputs(Extraction extraction) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (int i = 0; i < extraction.sources().size(); i++) values.add(Map.of("sourceId", sourceId(i), "source", extraction.sources().get(i)));
        return values;
    }
    private Map<String, Object> candidateInputs(Map<String, List<String>> links, EvidencePool pool) {
        Map<String, Object> values = new LinkedHashMap<>();
        links.forEach((id, evidenceIds) -> values.put(id, evidenceIds.stream().map(e -> Map.of("evidenceId", e, "document", pool.documents.get(e))).toList()));
        return values;
    }
    private Map<String, Object> selectedInputs(Map<String, List<String>> links, EvidencePool pool) {
        Map<String, Object> values = new LinkedHashMap<>();
        links.forEach((id, evidenceIds) -> values.put(id, evidenceIds.stream().map(e -> Map.of("evidenceId", e,
            "document", pool.documents.get(e), "quotes", List.copyOf(pool.quotes.get(e)))).toList()));
        return values;
    }

    private void validateExtraction(Extraction value, String body) {
        require(value != null && value.claims() != null && value.claims().size() <= 3 && value.sources() != null && value.sources().size() <= 6);
        Set<String> texts = new HashSet<>();
        for (DraftClaim c : value.claims()) {
            require(c != null && nonblank(c.text()) && texts.add(c.text()) && nonblank(c.question()) && c.queries() != null && !c.queries().isEmpty() && c.queries().size() <= 2);
            c.queries().forEach(q -> require(nonblank(q) && q.length() <= 300));
            require(c.kind() != null && c.speaker() != null);
            if (c.kind() == ClaimKind.ATTRIBUTION) {
                require(nonblank(c.speaker()) && contains(body, c.speaker()) && contains(c.text(), c.speaker())
                    && contains(c.question(), c.speaker()) && c.queries().stream().anyMatch(q -> contains(q, c.speaker())));
            } else require(c.speaker().isBlank());
        }
        for (DraftSource s : value.sources()) {
            require(s != null && nonblank(s.name()) && s.type() != null && nonblank(s.citedInArticle()) && contains(body, s.citedInArticle()) && s.claimIndexes() != null);
            for (Integer i : s.claimIndexes()) require(i != null && i >= 0 && i < value.claims().size());
        }
    }
    private void validateSelection(Selection value, Map<String, List<String>> candidates, EvidencePool pool) {
        require(value != null && value.claims() != null && value.claims().size() == candidates.size());
        Set<String> seen = new HashSet<>();
        for (SelectedClaim claim : value.claims()) {
            require(claim != null && candidates.containsKey(claim.claimId()) && seen.add(claim.claimId()) && claim.evidence() != null && claim.evidence().size() <= 3);
            Set<String> ids = new HashSet<>();
            for (Quote q : claim.evidence()) require(q != null && candidates.get(claim.claimId()).contains(q.evidenceId()) && ids.add(q.evidenceId())
                && nonblank(q.quote()) && q.quote().length() <= 1000 && contains(pool.documents.get(q.evidenceId()).body(), q.quote()));
        }
    }
    private void validateVerification(Verification value, Map<String, List<String>> allowed, EvidencePool pool) {
        require(value != null && value.claims() != null && value.claims().size() == allowed.size());
        Set<String> seen = new HashSet<>();
        for (VerifiedClaim claim : value.claims()) {
            require(claim != null && allowed.containsKey(claim.claimId()) && seen.add(claim.claimId()) && claim.verdict() != null && nonblank(claim.reason()) && claim.evidenceLinks() != null);
            require(claim.attributionQuotes() != null);
            Set<String> used = new HashSet<>(); Set<Stance> stances = new HashSet<>();
            for (EvidenceLink link : claim.evidenceLinks()) {
                require(link != null && allowed.get(claim.claimId()).contains(link.evidenceId()) && used.add(link.evidenceId()) && link.stance() != null);
                stances.add(link.stance());
            }
            for (Quote quote : claim.attributionQuotes()) require(quote != null && used.contains(quote.evidenceId())
                && nonblank(quote.quote()) && quote.quote().length() <= 1000 && contains(pool.documents.get(quote.evidenceId()).body(), quote.quote()));
            switch (claim.verdict()) {
                case SUPPORT -> require(stances.contains(Stance.SUPPORTS));
                case REFUTE -> require(stances.contains(Stance.REFUTES));
                case CONFLICT -> require(stances.contains(Stance.SUPPORTS) && stances.contains(Stance.REFUTES));
                case INSUFFICIENT -> { }
            }
        }
    }
    private void validateAssessment(Assessment value, Extraction extracted, Map<String, List<String>> allowed, EvidencePool pool) {
        require(value != null && value.sources() != null && value.sources().size() == allowed.size());
        Set<String> seen = new HashSet<>();
        for (SourceReview review : value.sources()) {
            require(review != null && allowed.containsKey(review.sourceId()) && seen.add(review.sourceId()) && nonblank(review.verificationNote()) && review.imvain() != null);
            require(review.evidence() != null);
            Set<String> quoted = new HashSet<>();
            for (Quote quote : review.evidence()) {
                require(quote != null && allowed.get(review.sourceId()).contains(quote.evidenceId()) && nonblank(quote.quote())
                    && quote.quote().length() <= 1000 && contains(pool.documents.get(quote.evidenceId()).body(), quote.quote()));
                quoted.add(quote.evidenceId());
            }
            for (String key : CRITERIA) {
                Criterion c = review.imvain().asMap().get(key);
                require(c != null && c.status() != null && nonblank(c.reason()) && c.evidenceIds() != null && allowed.get(review.sourceId()).containsAll(c.evidenceIds()) && quoted.containsAll(c.evidenceIds()));
            }
            if (!extracted.sources().get(index(review.sourceId())).identifiable()) require(review.imvain().named().status() == CriterionStatus.NOT_MET);
        }
    }
    private void validateSummary(OverallSummary s) {
        require(s != null && nonblank(s.overallComment()) && nonblank(s.factCheckOverview()) && s.sourceAssessmentOverview() != null && s.unverifiedPoints() != null);
        s.unverifiedPoints().forEach(p -> require(nonblank(p)));
    }
    static void require(boolean condition) { if (!condition) throw new IllegalArgumentException("Invalid grounded model output"); }
    private static boolean nonblank(String s) { return s != null && !s.isBlank(); }
    static boolean contains(String body, String quote) { return DocumentFetcher.clean(body).contains(DocumentFetcher.clean(quote)); }
    private static String claimId(int i) { return "claim-" + (i + 1); }
    private static String sourceId(int i) { return "source-" + (i + 1); }
    private static int index(String id) { return Integer.parseInt(id.substring(id.lastIndexOf('-') + 1)) - 1; }
    static String canonical(String url) {
        URI uri = URI.create(url);
        String query = uri.getRawQuery();
        if (query != null) query = Arrays.stream(query.split("&")).filter(p -> !p.toLowerCase(Locale.ROOT).matches("(utm_[^=]*|fbclid|gclid)=.*")).collect(Collectors.joining("&"));
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
        return shared / (double)Math.max(sx.size(), sy.size()) > 0.85;
    }
    private static Set<String> shingles(String text) {
        Set<String> values = new HashSet<>();
        for (int i = 0; i + 12 <= text.length(); i += 6) values.add(text.substring(i, i + 12));
        return values;
    }

    private static final class EvidencePool {
        final Document article;
        final Map<String, Document> cache = new HashMap<>();
        final Map<String, Document> documents = new LinkedHashMap<>();
        final Map<String, LinkedHashSet<String>> quotes = new LinkedHashMap<>();
        EvidencePool(Document article) { this.article = article; }
        String add(Document doc) {
            for (var entry : documents.entrySet()) if (canonical(entry.getValue().url()).equals(canonical(doc.url())) || sameContent(entry.getValue().body(), doc.body())) return entry.getKey();
            String id = "evidence-" + (documents.size() + 1);
            documents.put(id, doc); return id;
        }
        void quote(String id, String text) { quotes.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(DocumentFetcher.clean(text)); }
        List<Evidence> usedEvidence(List<Claim> claims, List<ArticleSource> sources) {
            Set<String> used = new LinkedHashSet<>();
            claims.forEach(c -> c.evidenceLinks().forEach(l -> used.add(l.evidenceId())));
            if (sources != null) sources.forEach(s -> s.imvain().values().forEach(c -> used.addAll(c.evidenceIds())));
            return used.stream().map(id -> {
                Document d = documents.get(id);
                return new Evidence(id, d.title(), d.publisher(), d.url(), d.publishedAt(), String.join(" … ", quotes.get(id)));
            }).toList();
        }
    }
}
