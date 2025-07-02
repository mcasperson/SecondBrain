package secondbrain.domain.handler;

import secondbrain.domain.converter.StringConverter;
import secondbrain.domain.tooldefs.IntermediateResult;
import secondbrain.domain.tooldefs.MetaObjectResults;

import java.util.List;

public class PromptResponseSimple implements PromptHandlerResponse {
    private final String responseText;
    private final String annotations;
    private final String debug;
    private final String links;
    private final List<MetaObjectResults> metaObjectResults;
    private final List<IntermediateResult> intermediateResults;

    public PromptResponseSimple(final String responseText, final String annotations, final String links, final String debug, final List<MetaObjectResults> metaObjectResults, final List<IntermediateResult> intermediateResults) {
        this.responseText = responseText;
        this.metaObjectResults = metaObjectResults;
        this.intermediateResults = intermediateResults;
        this.annotations = annotations;
        this.debug = debug;
        this.links = links;
    }

    public PromptResponseSimple(final String responseText) {
        this.responseText = responseText;
        this.metaObjectResults = List.of();
        this.intermediateResults = List.of();
        this.annotations = "";
        this.debug = "";
        this.links = "";
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
    public String getAnnotations() {
        return annotations;
    }

    @Override
    public String getLinks() {
        return links;
    }

    @Override
    public String getDebugInfo() {
        return debug;
    }

    @Override
    public String getResponseText() {
        return responseText;
    }

    @Override
    public PromptHandlerResponse updateResponseText(final StringConverter converter) {
        return new PromptResponseSimple(
                converter.convert(responseText),
                converter.convert(annotations),
                converter.convert(links),
                converter.convert(debug),
                this.metaObjectResults,
                this.intermediateResults);
    }
}
