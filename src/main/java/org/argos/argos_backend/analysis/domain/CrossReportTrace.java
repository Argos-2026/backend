package org.argos.argos_backend.analysis.domain;

import java.util.*;
import org.argos.argos_backend.analysis.dto.CrossReportTraceResponse.*;
import org.argos.argos_backend.external.search.SearchClient;
import static org.argos.argos_backend.analysis.domain.AnalysisModels.*;

/** Called only through AnalysisContext's best-effort, synchronized trace boundary. */
public final class CrossReportTrace {
    private final Map<String, Claim> claims = new LinkedHashMap<>();

    public void claim(String id, String text, String exact, String expanded) {
        claims.putIfAbsent(id, new Claim(id, text, exact, expanded));
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
        claims.get(claimId).candidates.put(id, new Candidate(id, requestId, provider, hit.url(), hit.title()));
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
            c.selected, c.verdict, c.reason, c.failure)).toList();
    }
    private static final class Claim {
        final String id, text, exact, expanded;
        final Map<String, SearchTrace> searches = new TreeMap<>();
        final Map<String, Candidate> candidates = new TreeMap<>();
        List<String> selected = List.of();
        ReportVerdict verdict;
        String reason;
        Issue failure;
        Claim(String id, String text, String exact, String expanded) {
            this.id = id; this.text = text; this.exact = exact; this.expanded = expanded;
        }
    }
    private static final class Candidate {
        final String id, requestId, url, title;
        final SearchClient.Provider provider;
        final List<CandidateEvent> events = new ArrayList<>();
        String evidenceId;
        Boolean selected;
        ComparisonTrace comparison;
        Candidate(String id, String requestId, SearchClient.Provider provider, String url, String title) {
            this.id = id; this.requestId = requestId; this.provider = provider; this.url = url; this.title = title;
        }
        CandidateTrace snapshot() {
            return new CandidateTrace(id, requestId, provider, url, title, List.copyOf(events), evidenceId, selected, comparison);
        }
    }
}
