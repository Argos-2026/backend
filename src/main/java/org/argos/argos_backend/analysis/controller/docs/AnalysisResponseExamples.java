package org.argos.argos_backend.analysis.controller.docs;

/** Examples are selected by section in Swagger's response example dropdown. */
public final class AnalysisResponseExamples {
    private AnalysisResponseExamples() {}
    public static final String ALL = """
        {"analysisId":"a0f02db8-c8af-48db-b9fd-5d6f62303a11","status":"COMPLETED",
        "article":{"url":"https://news.example.com/articles/12345","title":"기사 제목","publisher":"예시 언론사","author":null,"publishedAt":null},
        "summary":{"overallComment":"분석할 주장을 찾지 못했습니다.","crossReportOverview":"비교 대상 없음","sourceAssessmentOverview":"정보원 평가 대상 없음","unverifiedPoints":[]},
        "crossReports":[],"sourceAssessments":[],"evidence":[],"warnings":[]}
        """;
    public static final String CROSS_REPORT = """
        {"analysisId":"a0f02db8-c8af-48db-b9fd-5d6f62303a11","status":"COMPLETED",
        "article":{"url":"https://news.example.com/articles/12345","title":"기사 제목","publisher":"예시 언론사","author":null,"publishedAt":null},
        "crossReportOverview":"다른 언론사에서 일치하는 보도를 확인했습니다.",
        "crossReports":[{"claimId":"report-1","text":"복지 예산이 조정된다.","verdict":"MATCHED","reason":"일치 보도 1건을 확인했습니다.","matchingCount":1,"contradictingCount":0,
        "comparisons":[{"evidenceId":"evidence-1","stance":"MATCHES","reason":"같은 예산 조정을 보도합니다."}],"analysisFailed":false}],
        "evidence":[{"evidenceId":"evidence-1","title":"예산 조정 보도","publisher":"다른일보","url":"https://other.example.com/news/1","publishedAt":null,"excerpt":"복지 예산을 조정한다."}],"warnings":[]}
        """;
    public static final String SOURCE = """
        {"analysisId":"a0f02db8-c8af-48db-b9fd-5d6f62303a11","status":"COMPLETED",
        "article":{"url":"https://news.example.com/articles/12345","title":"기사 제목","publisher":"예시 언론사","author":null,"publishedAt":null},
        "sourceAssessmentOverview":"기관의 인용 내용을 외부 자료와 비교했습니다.",
        "sourceAssessments":[{"claimId":"source-claim-1","text":"기관은 복지 예산 조정을 발표했다.","sources":[{
        "sourceId":"source-1-1","name":"기관","affiliation":"","type":"ORGANIZATION","citedInArticle":"기관은 예산을 조정한다고 밝혔다.",
        "verificationNote":"기관 발표 자료에서 인용 내용을 확인했습니다.","imvain":{
        "independent":{"status":"UNKNOWN","reason":"독립성을 확인할 자료가 없습니다.","evidenceIds":[]},
        "multiple":{"status":"NOT_MET","reason":"해당 주장에 인용된 정보원은 하나입니다.","evidenceIds":[]},
        "verify":{"status":"MET","reason":"발표 자료에서 확인했습니다.","evidenceIds":["evidence-2"]},
        "authoritative":{"status":"UNKNOWN","reason":"권한을 확인하지 못했습니다.","evidenceIds":[]},
        "informed":{"status":"UNKNOWN","reason":"직접 취재 여부를 확인하지 못했습니다.","evidenceIds":[]},
        "named":{"status":"MET","reason":"기사에 기관명이 명시되어 있습니다.","evidenceIds":[]}},"assessmentFailed":false}]}],
        "evidence":[{"evidenceId":"evidence-2","title":"기관 발표 자료","publisher":"기관","url":"https://docs.example.com/budget","publishedAt":null,"excerpt":"복지 예산을 조정한다."}],"warnings":[]}
        """;
    public static final String TRACE = """
        {"analysisId":"a0f02db8-c8af-48db-b9fd-5d6f62303a11","status":"COMPLETED","claims":[{
        "claimId":"report-1","claimText":"복지 예산이 조정된다.","exactQuery":"복지 예산 조정","expandedQuery":"복지 사업 변경",
        "searches":[
        {"requestId":"search-1","provider":"NAVER","queryType":"EXACT","query":"복지 예산 조정","status":"SUCCEEDED","resultCount":1,"failure":null},
        {"requestId":"search-2","provider":"BRAVE","queryType":"EXACT","query":"복지 예산 조정","status":"SUCCEEDED","resultCount":0,"failure":null},
        {"requestId":"search-3","provider":"NAVER","queryType":"EXPANDED","query":"복지 사업 변경","status":"SUCCEEDED","resultCount":0,"failure":null},
        {"requestId":"search-4","provider":"BRAVE","queryType":"EXPANDED","query":"복지 사업 변경","status":"SUCCEEDED","resultCount":0,"failure":null}],
        "candidates":[{"candidateId":"search-1-candidate-1","requestId":"search-1","provider":"NAVER","url":"https://other.example.com/news/1","title":"예산 조정 보도",
        "events":[{"stage":"RETRIEVING_EVIDENCE","code":"FETCH_STARTED","message":null},
        {"stage":"RETRIEVING_EVIDENCE","code":"FETCH_SUCCEEDED","message":null},
        {"stage":"RETRIEVING_EVIDENCE","code":"CANDIDATE_ACCEPTED","message":null},
        {"stage":"SELECTING_EVIDENCE","code":"LLM_SELECTED","message":null},
        {"stage":"VERIFYING_CLAIMS","code":"COMPARED","message":null}],
        "evidenceId":"evidence-1","llmSelected":true,"comparison":{"evidenceId":"evidence-1","stance":"MATCHES","reason":"같은 예산 조정을 보도합니다."}}],
        "selectedEvidenceIds":["evidence-1"],"verdict":"MATCHED","reason":"일치 보도 1건을 확인했습니다.","failure":null}],"error":null}
        """;
    public static final String TRACE_PERSPECTIVE = """
        {"analysisId":"a0f02db8-c8af-48db-b9fd-5d6f62303a11","status":"COMPLETED","claims":[{
        "claimId":"report-1","claimText":"2027년 복지 예산 감소율은 12%다.",
        "exactQuery":"새빛시 복지 예산 12%","expandedQuery":"새빛시 복지 예산 변화",
        "issue":{"event":"발표","subject":"새빛시","target":"복지 예산","period":"2027년","metric":"복지 예산",
        "originalQuote":"새빛시는 2027년 복지 예산 감소율이 12%라고 발표했다.","announcementDate":""},
        "temporalContext":{"originalPublishedAt":null,"announcementDate":"","announcementDateSource":"UNKNOWN",
        "targetPeriods":["2027년"],"numericAnchors":["12%"],"eventAnchors":["새빛시","복지 예산","발표"],"action":"NONE"},
        "neutralQuery":"2027년 12% 새빛시 복지 예산 발표","challengeQuery":"2027년 12% 새빛시 복지 예산 발표 정정",
        "searches":[
        {"requestId":"search-1","provider":"NAVER","queryType":"EXACT","query":"새빛시 복지 예산 12%","status":"SUCCEEDED","resultCount":0,"failure":null},
        {"requestId":"search-2","provider":"BRAVE","queryType":"EXACT","query":"새빛시 복지 예산 12%","status":"SUCCEEDED","resultCount":0,"failure":null},
        {"requestId":"search-3","provider":"NAVER","queryType":"EXPANDED","query":"새빛시 복지 예산 변화","status":"SUCCEEDED","resultCount":0,"failure":null},
        {"requestId":"search-4","provider":"BRAVE","queryType":"EXPANDED","query":"새빛시 복지 예산 변화","status":"SUCCEEDED","resultCount":0,"failure":null},
        {"requestId":"search-5","provider":"NAVER","queryType":"NEUTRAL","query":"2027년 12% 새빛시 복지 예산 발표","status":"SUCCEEDED","resultCount":0,"failure":null},
        {"requestId":"search-6","provider":"BRAVE","queryType":"CHALLENGE",
        "query":"2027년 12% 새빛시 복지 예산 발표 정정","status":"SUCCEEDED","resultCount":1,"failure":null}],
        "candidates":[{"candidateId":"search-6-candidate-1","requestId":"search-6","provider":"BRAVE",
        "url":"https://other.example.com/a","title":"예산 정정 보도","searchDescription":"예산 관련 검색 요약","searchDateHint":"2026-11-01",
        "events":[{"stage":"SELECTING_EVIDENCE","code":"PERSPECTIVE_SELECTED","message":"본문 근거가 있는 충돌 후보입니다."}],
        "evidenceId":"evidence-1","llmSelected":null,"matchResolution":null,
        "prefetchPriority":{"rank":1,"score":0,"eligible":true,"reasons":["INSUFFICIENT_METADATA_RETAINED"]},
        "document":{"url":"https://other.example.com/a","title":"예산 보도","publisher":"다른일보","publishedAt":null,"truncated":false},
        "claimAnalysis":{"evidenceId":"evidence-1","perspective":"CONFLICT_CANDIDATE",
        "statement":"새빛시의 2027년 복지 예산 감소율은 20%다.","quote":"새빛시의 2027년 복지 예산 감소율은 20%다.",
        "reason":"동일 쟁점에서 감소율이 다른 후보입니다.","duplicateOfEvidenceId":"","sharedOriginQuote":""},
        "verificationSelection":{"selected":true,"reason":"본문 근거를 정밀 비교합니다."},
        "comparison":{"evidenceId":"evidence-1","stance":"CONTRADICTS","reason":"같은 기준에서 감소율이 다릅니다."},
        "conflictResolution":{"check":{"sameEvent":"SAME","sameSubject":"SAME","sameTarget":"SAME","samePeriod":"SAME",
        "sameMetric":"SAME","sameUnit":"SAME","sameDenominator":"SAME","sameAggregation":"SAME","sameConditions":"SAME",
        "mutuallyExclusive":true,"originalQuote":"새빛시는 2027년 복지 예산 감소율이 12%라고 발표했다.",
        "externalQuote":"새빛시의 2027년 복지 예산 감소율은 20%다.","reason":"같은 대상 기간과 집계 기준입니다."},
        "accepted":true,"reason":"본문 근거와 동일 비교 조건을 확인했습니다."},
        "usedQuotes":["새빛시의 2027년 복지 예산 감소율은 20%다."]}],
        "selectedEvidenceIds":["evidence-1"],"verdict":"CONTRADICTED","reason":"상반 보도 1건을 확인했습니다.",
        "failure":null,"enrichmentIssues":[]}],"error":null}
        """;
}
