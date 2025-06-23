package secondbrain.domain.handler;

import secondbrain.domain.tooldefs.IntermediateResult;
import secondbrain.domain.tooldefs.MetaObjectResults;

import java.util.List;

public class PromptResponseSimple implements PromptHandlerResponse {
    private final String responseText;
    private final List<MetaObjectResults> metaObjectResults;
    private final List<IntermediateResult> intermediateResults;

    public PromptResponseSimple(final String responseText, final List<MetaObjectResults> metaObjectResults, final List<IntermediateResult> intermediateResults) {
        this.responseText = responseText;
        this.metaObjectResults = metaObjectResults;
        this.intermediateResults = intermediateResults;
    }

    public PromptResponseSimple(final String responseText, final List<MetaObjectResults> metaObjectResults) {
        this.responseText = responseText;
        this.metaObjectResults = metaObjectResults;
        this.intermediateResults = List.of();
    }

    public PromptResponseSimple(final String responseText) {
        this.responseText = responseText;
        this.metaObjectResults = List.of();
        this.intermediateResults = List.of();
    }

    @Override
    public List<MetaObjectResults> getMetaObjectResults() {
        return metaObjectResults;
    }

    @Override
    public List<IntermediateResult> getIntermediateResults() {
        return intermediateResults;
    }

    @Override
    public String getResponseText() {
        return responseText;
    }

    @Override
    public PromptHandlerResponse updateResponseText(final String responseText) {
        return new PromptResponseSimple(responseText, this.metaObjectResults);
    }
}
