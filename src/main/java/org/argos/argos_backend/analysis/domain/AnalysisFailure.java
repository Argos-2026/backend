package org.argos.argos_backend.analysis.domain;

import static org.argos.argos_backend.analysis.domain.AnalysisModels.*;

public class AnalysisFailure extends RuntimeException {
    private final String code;
    private final String safeMessage;
    public AnalysisFailure(String code, String safeMessage) {
        super(code);
        this.code = code;
        this.safeMessage = safeMessage;
    }
    public Issue issue(Stage stage) { return new Issue(code, stage, safeMessage); }
    public String code() { return code; }
}
