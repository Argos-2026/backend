package org.argos.argos_backend.analysis.domain;

import java.util.*;
import org.argos.argos_backend.analysis.dto.CrossReportTraceResponse.*;
import org.argos.argos_backend.external.search.SearchClient;
import static org.argos.argos_backend.analysis.domain.AnalysisModels.*;
import static org.argos.argos_backend.analysis.domain.ReportingModels.*;
import org.argos.argos_backend.external.document.DocumentFetcher.Document;

/** Called only through AnalysisContext's best-effort, synchronized trace boundary. */
public final class CrossReportTrace {
    private final Map<String, Claim> claims = new LinkedHashMap<>();

    public void claim(String id, String text, String exact, String expanded) {
        claims.putIfAbsent(id, new Claim(id, text, exact, expanded));
    }
    public void issue(String id, IssueFrame issue, String neutral, String challenge) {
        Claim claim = claims.get(id); claim.issue = issue; claim.neutral = neutral; claim.challenge = challenge;
    }
    public void enrichmentFailure(String id, Issue issue) { claims.get(id).enrichmentIssues.add(issue); }
    public void temporal(String id, TemporalContext temporal) { claims.get(id).temporal = temporal; }
    public void prefetch(String claimId, String candidateId, PrefetchPriority priority) {
        claims.get(claimId).candidates.get(candidateId).priority = priority;
    }
    public void matchResolution(String claimId, String evidenceId, MatchResolution resolution) {
        linked(claimId, evidenceId).forEach(c -> c.matchResolution = resolution);
    }
    public void document(String claimId, String candidateId, Document doc) {
        claims.get(claimId).candidates.get(candidateId).document =
            new DocumentMetadata(doc.url(), doc.title(), doc.publisher(), doc.publishedAt(), doc.truncated());
    }
    public void perspective(String claimId, CandidateClaim analysis) {
        linked(claimId, analysis.evidenceId()).forEach(c -> c.analysis = analysis);
    }
    public void resolution(String claimId, String evidenceId, ConflictResolution resolution) {
        linked(claimId, evidenceId).forEach(c -> c.resolution = resolution);
    }
    public void usedQuote(String claimId, String evidenceId, String quote) {
        linked(claimId, evidenceId).forEach(c -> c.usedQuotes.add(quote));
    }
    private java.util.stream.Stream<Candidate> linked(String claimId, String evidenceId) {
        return claims.get(claimId).candidates.values().stream().filter(c -> evidenceId.equals(c.evidenceId));
    }
    public void searchStarted(String claimId, String requestId, SearchClient.Provider provider, QueryType type, String query) {
        claims.get(claimId).searches.put(requestId,
            new SearchTrace(requestId, provider, type, query, RequestStatus.STARTED, null, null));
    }
    public void searchFinished(String claimId, String requestId, Integer count, Issue failure) {
        Claim claim = claims.get(claimId);
        SearchTrace before = claim.searches.get(requestId);
        claim.searches.put(requestId, new SearchTrace(requestId, before.provider(), before.queryType(), before.query(),
            failure == null ? RequestStatus.SUCCEEDED : RequestStatus.FAILED, count, failure));
    }
    public void candidate(String claimId, String id, String requestId, SearchClient.Provider provider, SearchClient.Hit hit) {
        claims.get(claimId).candidates.put(id, new Candidate(id, requestId, provider, hit));
    }
    public void event(String claimId, String candidateId, String code, String message) {
        claims.get(claimId).candidates.get(candidateId).events.add(new CandidateEvent(Stage.RETRIEVING_EVIDENCE, code, message));
    }
    public void evidence(String claimId, String candidateId, String evidenceId) {
        claims.get(claimId).candidates.get(candidateId).evidenceId = evidenceId;
    }
    public void selection(String claimId, List<String> candidates, List<String> selected) {
        Claim claim = claims.get(claimId);
        claim.selected = List.copyOf(selected);
        claim.candidates.values().stream().filter(c -> c.evidenceId != null && candidates.contains(c.evidenceId)).forEach(c -> {
            c.selected = selected.contains(c.evidenceId);
            c.events.add(new CandidateEvent(Stage.SELECTING_EVIDENCE,
                c.selected ? "LLM_SELECTED" : "LLM_NOT_SELECTED", null));
        });
    }
    public void perspectiveSelection(String claimId, List<String> candidates, List<String> selected) {
        Claim claim = claims.get(claimId);
        claim.selected = List.copyOf(selected);
        claim.candidates.values().stream().filter(c -> c.evidenceId != null && candidates.contains(c.evidenceId)).forEach(c -> {
            boolean included = selected.contains(c.evidenceId);
            String reason = included ? "본문에 근거한 지지·충돌 후보를 비교 대상으로 선정했습니다."
                : c.analysis.perspective() != Perspective.SUPPORT_CANDIDATE && c.analysis.perspective() != Perspective.CONFLICT_CANDIDATE
                ? "유형 " + c.analysis.perspective() + ": 직접 사실 비교 대상에서 제외했습니다."
                : !c.analysis.duplicateOfEvidenceId().isBlank() ? "동일 원자료의 중복 후보로 대표 보도를 사용합니다."
                : "비교 문서 최대 5개 한도로 미선정했습니다.";
            c.verificationSelection = new VerificationSelection(included, reason);
            c.events.add(new CandidateEvent(Stage.SELECTING_EVIDENCE,
                included ? "PERSPECTIVE_SELECTED" : "PERSPECTIVE_NOT_SELECTED", reason));
        });
    }
    public void comparison(String claimId, String evidenceId, String stance, String reason) {
        claims.get(claimId).candidates.values().stream().filter(c -> evidenceId.equals(c.evidenceId)).forEach(c -> {
            c.comparison = new ComparisonTrace(evidenceId, stance, reason);
            c.events.add(new CandidateEvent(Stage.VERIFYING_CLAIMS, "COMPARED", null));
        });
    }
    public void result(String claimId, CrossReport result) {
        Claim claim = claims.get(claimId); claim.verdict = result.verdict(); claim.reason = result.reason();
    }
    public void failure(String claimId, Issue issue) { claims.get(claimId).failure = issue; }
    public List<ClaimTrace> snapshot() {
        return claims.values().stream().map(c -> new ClaimTrace(c.id, c.text, c.exact, c.expanded,
            List.copyOf(c.searches.values()), c.candidates.values().stream().map(Candidate::snapshot).toList(),
            c.selected, c.verdict, c.reason, c.failure, c.issue, c.neutral, c.challenge,
            List.copyOf(c.enrichmentIssues), c.temporal)).toList();
    }
    private static final class Claim {
        final String id, text, exact, expanded;
        final Map<String, SearchTrace> searches = new TreeMap<>();
        final Map<String, Candidate> candidates = new TreeMap<>();
        List<String> selected = List.of();
        ReportVerdict verdict;
        String reason;
        Issue failure;
        IssueFrame issue;
        TemporalContext temporal;
        String neutral, challenge;
        final List<Issue> enrichmentIssues = new ArrayList<>();
        Claim(String id, String text, String exact, String expanded) {
            this.id = id; this.text = text; this.exact = exact; this.expanded = expanded;
        }
    }
    private static final class Candidate {
        final String id, requestId, url, title;
        final String description, dateHint;
        final SearchClient.Provider provider;
        final List<CandidateEvent> events = new ArrayList<>();
        String evidenceId;
        Boolean selected;
        ComparisonTrace comparison;
        DocumentMetadata document;
        CandidateClaim analysis;
        ConflictResolution resolution;
        VerificationSelection verificationSelection;
        PrefetchPriority priority;
        MatchResolution matchResolution;
        final Set<String> usedQuotes = new LinkedHashSet<>();
        Candidate(String id, String requestId, SearchClient.Provider provider, SearchClient.Hit hit) {
            this.id = id; this.requestId = requestId; this.provider = provider; this.url = hit.url(); this.title = hit.title();
            this.description = hit.description(); this.dateHint = hit.dateHint();
        }
        CandidateTrace snapshot() {
            return new CandidateTrace(id, requestId, provider, url, title, List.copyOf(events), evidenceId, selected, comparison,
                document, analysis, resolution, List.copyOf(usedQuotes), description, dateHint, verificationSelection, priority, matchResolution);
        }
    }
}
