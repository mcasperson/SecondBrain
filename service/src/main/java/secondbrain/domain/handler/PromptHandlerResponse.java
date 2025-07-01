package secondbrain.domain.handler;

import secondbrain.domain.converter.StringConverter;
import secondbrain.domain.tooldefs.IntermediateResult;
import secondbrain.domain.tooldefs.MetaObjectResults;

import java.util.List;

/**
 * Represents the result of a prompt execution.
 * <p>
 * It includes the response text and any metadata results that were generated.
 */
public interface PromptHandlerResponse {
    List<MetaObjectResults> getMetaObjectResults();

    List<IntermediateResult> getIntermediateResults();

    String getAnnotations();

    String getLinks();

    String getDebugInfo();

    String getResponseText();

    PromptHandlerResponse updateResponseText(StringConverter converter);
}
