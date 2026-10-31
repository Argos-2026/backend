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
}
