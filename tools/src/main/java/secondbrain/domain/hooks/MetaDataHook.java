package secondbrain.domain.hooks;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.tooldefs.MetaObjectResult;
import secondbrain.domain.tooldefs.MetaObjectResults;
import secondbrain.domain.tools.rating.RatingTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A hooked used to generate metadata based on the combined content of each source document that
 * makes up the context. Use this to generate scores or other numeric metadata based on
 * the overall content.
 */
@ApplicationScoped
public class MetaDataHook implements PostInferenceHook {
    @Inject
    private Logger logger;

    @Inject
    private RatingTool ratingTool;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.metareport")
    private Optional<String> metaReport;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaField1")
    private Optional<String> contextMetaField1;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaPrompt1")
    private Optional<String> contextMetaPrompt1;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaField2")
    private Optional<String> contextMetaField2;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaPrompt2")
    private Optional<String> contextMetaPrompt2;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaField3")
    private Optional<String> contextMetaField3;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaPrompt3")
    private Optional<String> contextMetaPrompt3;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaField4")
    private Optional<String> contextMetaField4;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaPrompt4")
    private Optional<String> contextMetaPrompt4;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaField5")
    private Optional<String> contextMetaField5;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaPrompt5")
    private Optional<String> contextMetaPrompt5;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaField6")
    private Optional<String> contextMetaField6;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaPrompt6")
    private Optional<String> contextMetaPrompt6;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaField7")
    private Optional<String> contextMetaField7;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaPrompt7")
    private Optional<String> contextMetaPrompt7;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaField8")
    private Optional<String> contextMetaField8;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaPrompt8")
    private Optional<String> contextMetaPrompt8;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaField9")
    private Optional<String> contextMetaField9;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaPrompt9")
    private Optional<String> contextMetaPrompt9;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaField10")
    private Optional<String> contextMetaField10;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaPrompt10")
    private Optional<String> contextMetaPrompt10;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaField11")
    private Optional<String> contextMetaField11;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaPrompt11")
    private Optional<String> contextMetaPrompt11;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaField12")
    private Optional<String> contextMetaField12;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaPrompt12")
    private Optional<String> contextMetaPrompt12;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaField13")
    private Optional<String> contextMetaField13;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaPrompt13")
    private Optional<String> contextMetaPrompt13;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaField14")
    private Optional<String> contextMetaField14;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaPrompt14")
    private Optional<String> contextMetaPrompt14;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaField15")
    private Optional<String> contextMetaField15;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaPrompt15")
    private Optional<String> contextMetaPrompt15;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaField16")
    private Optional<String> contextMetaField16;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaPrompt16")
    private Optional<String> contextMetaPrompt16;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaField17")
    private Optional<String> contextMetaField17;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaPrompt17")
    private Optional<String> contextMetaPrompt17;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaField18")
    private Optional<String> contextMetaField18;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaPrompt18")
    private Optional<String> contextMetaPrompt18;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaField19")
    private Optional<String> contextMetaField19;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaPrompt19")
    private Optional<String> contextMetaPrompt19;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaField20")
    private Optional<String> contextMetaField20;

    @Inject
    @ConfigProperty(name = "sb.metadatahook.contextMetaPrompt20")
    private Optional<String> contextMetaPrompt20;

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public <T> RagMultiDocumentContext<T> process(final String toolName, final RagMultiDocumentContext<T> ragMultiDocumentContext) {
        logger.fine("Executing MetaDataHook for tool: " + toolName);

        final List<MetaObjectResult> results = new ArrayList<>();

        final List<Pair<String, String>> metaFields = Stream.of(
                        Pair.of(contextMetaField1.orElse(""), contextMetaPrompt1.orElse("")),
                        Pair.of(contextMetaField2.orElse(""), contextMetaPrompt2.orElse("")),
                        Pair.of(contextMetaField3.orElse(""), contextMetaPrompt3.orElse("")),
                        Pair.of(contextMetaField4.orElse(""), contextMetaPrompt4.orElse("")),
                        Pair.of(contextMetaField5.orElse(""), contextMetaPrompt5.orElse("")),
                        Pair.of(contextMetaField6.orElse(""), contextMetaPrompt6.orElse("")),
                        Pair.of(contextMetaField7.orElse(""), contextMetaPrompt7.orElse("")),
                        Pair.of(contextMetaField8.orElse(""), contextMetaPrompt8.orElse("")),
                        Pair.of(contextMetaField9.orElse(""), contextMetaPrompt9.orElse("")),
                        Pair.of(contextMetaField10.orElse(""), contextMetaPrompt10.orElse("")),
                        Pair.of(contextMetaField11.orElse(""), contextMetaPrompt11.orElse("")),
                        Pair.of(contextMetaField12.orElse(""), contextMetaPrompt12.orElse("")),
                        Pair.of(contextMetaField13.orElse(""), contextMetaPrompt13.orElse("")),
                        Pair.of(contextMetaField14.orElse(""), contextMetaPrompt14.orElse("")),
                        Pair.of(contextMetaField15.orElse(""), contextMetaPrompt15.orElse("")),
                        Pair.of(contextMetaField16.orElse(""), contextMetaPrompt16.orElse("")),
                        Pair.of(contextMetaField17.orElse(""), contextMetaPrompt17.orElse("")),
                        Pair.of(contextMetaField18.orElse(""), contextMetaPrompt18.orElse("")),
                        Pair.of(contextMetaField19.orElse(""), contextMetaPrompt19.orElse("")),
                        Pair.of(contextMetaField20.orElse(""), contextMetaPrompt20.orElse(""))
                )
                .filter(p -> StringUtils.isNotBlank(p.getLeft()) && StringUtils.isNotBlank(p.getRight()))
                .toList();

        if (metaFields.isEmpty() || metaReport.isEmpty()) {
            return ragMultiDocumentContext;
        }

        for (final Pair<String, String> metaField : metaFields) {
            final String content = ragMultiDocumentContext.getIndividualContexts().stream()
                    .map(RagDocumentContext::document)
                    .collect(Collectors.joining("\n"));

            final int value = Try.of(() -> ratingTool.call(
                            Map.of(RatingTool.RATING_DOCUMENT_CONTEXT_ARG, content),
                            metaField.getRight(),
                            List.of()).getResponse())
                    .map(rating -> Integer.parseInt(rating.trim()))
                    .onFailure(ex -> logger.warning("Rating tool failed for " + metaField.getLeft() + ": " + ex.getMessage()))
                    .recover(ex -> 0)
                    .get();

            results.add(new MetaObjectResult(metaField.getLeft(), value, null, getName()));

        }

        return ragMultiDocumentContext.updateMetadata(
                new MetaObjectResults(results, metaReport.get(), ""));
    }
}
