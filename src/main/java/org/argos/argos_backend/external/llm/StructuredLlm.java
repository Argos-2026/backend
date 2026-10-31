package org.argos.argos_backend.external.llm;

import java.util.function.Consumer;
import org.argos.argos_backend.analysis.domain.AnalysisContext;

public interface StructuredLlm {
    <T> T generate(String prompt, String input, Class<T> output, Consumer<T> validate, AnalysisContext context);
}
