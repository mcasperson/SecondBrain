package secondbrain.domain.tools.meta;

import com.google.common.collect.ImmutableList;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptionhandling.ExceptionMapping;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.tooldefs.*;
import secondbrain.domain.tools.rating.RatingTool;
import secondbrain.infrastructure.llm.LlmClient;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * This tool combines many sub-tools to allow a single prompt to be answered by multiple different
 * data sources.
 */
@ApplicationScoped
public class Meta implements Tool<Void> {
    public static final String META_TOOL_NAMES_ARG = "toolNames";
    public static final String META_TTL_SECONDS_ARG = "ttlSeconds";
    public static final String META_CONTEXT_META_FIELD_1_ARG = "contextMetaField1";
    public static final String META_CONTEXT_META_PROMPT_1_ARG = "contextMetaPrompt1";
    public static final String META_CONTEXT_META_FIELD_2_ARG = "contextMetaField2";
    public static final String META_CONTEXT_META_PROMPT_2_ARG = "contextMetaPrompt2";
    public static final String META_CONTEXT_META_FIELD_3_ARG = "contextMetaField3";
    public static final String META_CONTEXT_META_PROMPT_3_ARG = "contextMetaPrompt3";
    public static final String META_CONTEXT_META_FIELD_4_ARG = "contextMetaField4";
    public static final String META_CONTEXT_META_PROMPT_4_ARG = "contextMetaPrompt4";
    public static final String META_CONTEXT_META_FIELD_5_ARG = "contextMetaField5";
    public static final String META_CONTEXT_META_PROMPT_5_ARG = "contextMetaPrompt5";
    public static final String META_CONTEXT_META_FIELD_6_ARG = "contextMetaField6";
    public static final String META_CONTEXT_META_PROMPT_6_ARG = "contextMetaPrompt6";
    public static final String META_CONTEXT_META_FIELD_7_ARG = "contextMetaField7";
    public static final String META_CONTEXT_META_PROMPT_7_ARG = "contextMetaPrompt7";
    public static final String META_CONTEXT_META_FIELD_8_ARG = "contextMetaField8";
    public static final String META_CONTEXT_META_PROMPT_8_ARG = "contextMetaPrompt8";
    public static final String META_CONTEXT_META_FIELD_9_ARG = "contextMetaField9";
    public static final String META_CONTEXT_META_PROMPT_9_ARG = "contextMetaPrompt9";
    public static final String META_CONTEXT_META_FIELD_10_ARG = "contextMetaField10";
    public static final String META_CONTEXT_META_PROMPT_10_ARG = "contextMetaPrompt10";
    public static final String META_CONTEXT_META_FIELD_11_ARG = "contextMetaField11";
    public static final String META_CONTEXT_META_PROMPT_11_ARG = "contextMetaPrompt11";
    public static final String META_CONTEXT_META_FIELD_12_ARG = "contextMetaField12";
    public static final String META_CONTEXT_META_PROMPT_12_ARG = "contextMetaPrompt12";
    public static final String META_CONTEXT_META_FIELD_13_ARG = "contextMetaField13";
    public static final String META_CONTEXT_META_PROMPT_13_ARG = "contextMetaPrompt13";
    public static final String META_CONTEXT_META_FIELD_14_ARG = "contextMetaField14";
    public static final String META_CONTEXT_META_PROMPT_14_ARG = "contextMetaPrompt14";
    public static final String META_CONTEXT_META_FIELD_15_ARG = "contextMetaField15";
    public static final String META_CONTEXT_META_PROMPT_15_ARG = "contextMetaPrompt15";
    public static final String META_CONTEXT_META_FIELD_16_ARG = "contextMetaField16";
    public static final String META_CONTEXT_META_PROMPT_16_ARG = "contextMetaPrompt16";
    public static final String META_CONTEXT_META_FIELD_17_ARG = "contextMetaField17";
    public static final String META_CONTEXT_META_PROMPT_17_ARG = "contextMetaPrompt17";
    public static final String META_CONTEXT_META_FIELD_18_ARG = "contextMetaField18";
    public static final String META_CONTEXT_META_PROMPT_18_ARG = "contextMetaPrompt18";
    public static final String META_CONTEXT_META_FIELD_19_ARG = "contextMetaField19";
    public static final String META_CONTEXT_META_PROMPT_19_ARG = "contextMetaPrompt19";
    public static final String META_CONTEXT_META_FIELD_20_ARG = "contextMetaField20";
    public static final String META_CONTEXT_META_PROMPT_20_ARG = "contextMetaPrompt20";
    public static final String META_META_REPORT_ARG = "metaReport";

    @Inject
    private Instance<Tool<?>> tools;

    @Inject
    private MetaConfig config;

    @Inject
    private Logger logger;

    @Inject
    @Preferred
    private LocalStorage localStorage;

    @Inject
    @Preferred
    private LlmClient llmClient;

    @Inject
    private ExceptionMapping exceptionMapping;

    @Inject
    private RatingTool ratingTool;

    @Override
    public String getName() {
        return Meta.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "A tool that combines multiple tools to provide meta-level information and functionality.";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return ImmutableList.of(
                new ToolArguments(META_TOOL_NAMES_ARG, "Comma-separated list of tool names to include", ""),
                new ToolArguments(META_TTL_SECONDS_ARG, "The number of seconds to cache the result", "604800"),
                new ToolArguments(META_CONTEXT_META_FIELD_1_ARG, "The field name for context meta 1", ""),
                new ToolArguments(META_CONTEXT_META_PROMPT_1_ARG, "The prompt for context meta 1", ""),
                new ToolArguments(META_CONTEXT_META_FIELD_2_ARG, "The field name for context meta 2", ""),
                new ToolArguments(META_CONTEXT_META_PROMPT_2_ARG, "The prompt for context meta 2", ""),
                new ToolArguments(META_CONTEXT_META_FIELD_3_ARG, "The field name for context meta 3", ""),
                new ToolArguments(META_CONTEXT_META_PROMPT_3_ARG, "The prompt for context meta 3", ""),
                new ToolArguments(META_CONTEXT_META_FIELD_4_ARG, "The field name for context meta 4", ""),
                new ToolArguments(META_CONTEXT_META_PROMPT_4_ARG, "The prompt for context meta 4", ""),
                new ToolArguments(META_CONTEXT_META_FIELD_5_ARG, "The field name for context meta 5", ""),
                new ToolArguments(META_CONTEXT_META_PROMPT_5_ARG, "The prompt for context meta 5", ""),
                new ToolArguments(META_CONTEXT_META_FIELD_6_ARG, "The field name for context meta 6", ""),
                new ToolArguments(META_CONTEXT_META_PROMPT_6_ARG, "The prompt for context meta 6", ""),
                new ToolArguments(META_CONTEXT_META_FIELD_7_ARG, "The field name for context meta 7", ""),
                new ToolArguments(META_CONTEXT_META_PROMPT_7_ARG, "The prompt for context meta 7", ""),
                new ToolArguments(META_CONTEXT_META_FIELD_8_ARG, "The field name for context meta 8", ""),
                new ToolArguments(META_CONTEXT_META_PROMPT_8_ARG, "The prompt for context meta 8", ""),
                new ToolArguments(META_CONTEXT_META_FIELD_9_ARG, "The field name for context meta 9", ""),
                new ToolArguments(META_CONTEXT_META_PROMPT_9_ARG, "The prompt for context meta 9", ""),
                new ToolArguments(META_CONTEXT_META_FIELD_10_ARG, "The field name for context meta 10", ""),
                new ToolArguments(META_CONTEXT_META_PROMPT_10_ARG, "The prompt for context meta 10", ""),
                new ToolArguments(META_CONTEXT_META_FIELD_11_ARG, "The field name for context meta 11", ""),
                new ToolArguments(META_CONTEXT_META_PROMPT_11_ARG, "The prompt for context meta 11", ""),
                new ToolArguments(META_CONTEXT_META_FIELD_12_ARG, "The field name for context meta 12", ""),
                new ToolArguments(META_CONTEXT_META_PROMPT_12_ARG, "The prompt for context meta 12", ""),
                new ToolArguments(META_CONTEXT_META_FIELD_13_ARG, "The field name for context meta 13", ""),
                new ToolArguments(META_CONTEXT_META_PROMPT_13_ARG, "The prompt for context meta 13", ""),
                new ToolArguments(META_CONTEXT_META_FIELD_14_ARG, "The field name for context meta 14", ""),
                new ToolArguments(META_CONTEXT_META_PROMPT_14_ARG, "The prompt for context meta 14", ""),
                new ToolArguments(META_CONTEXT_META_FIELD_15_ARG, "The field name for context meta 15", ""),
                new ToolArguments(META_CONTEXT_META_PROMPT_15_ARG, "The prompt for context meta 15", ""),
                new ToolArguments(META_CONTEXT_META_FIELD_16_ARG, "The field name for context meta 16", ""),
                new ToolArguments(META_CONTEXT_META_PROMPT_16_ARG, "The prompt for context meta 16", ""),
                new ToolArguments(META_CONTEXT_META_FIELD_17_ARG, "The field name for context meta 17", ""),
                new ToolArguments(META_CONTEXT_META_PROMPT_17_ARG, "The prompt for context meta 17", ""),
                new ToolArguments(META_CONTEXT_META_FIELD_18_ARG, "The field name for context meta 18", ""),
                new ToolArguments(META_CONTEXT_META_PROMPT_18_ARG, "The prompt for context meta 18", ""),
                new ToolArguments(META_CONTEXT_META_FIELD_19_ARG, "The field name for context meta 19", ""),
                new ToolArguments(META_CONTEXT_META_PROMPT_19_ARG, "The prompt for context meta 19", ""),
                new ToolArguments(META_CONTEXT_META_FIELD_20_ARG, "The field name for context meta 20", ""),
                new ToolArguments(META_CONTEXT_META_PROMPT_20_ARG, "The prompt for context meta 20", ""),
                new ToolArguments(META_META_REPORT_ARG, "The meta report", "")
        );
    }

    @Override
    public List<RagDocumentContext<Void>> getContext(final Map<String, String> environmentSettings, final String prompt, List<ToolArgs> arguments) {
        final MetaConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        // Use the parsed tool names
        final List<String> toolNames = parsedArgs.getToolNames();

        // Filter the tools to those included in the toolNames
        final List<Tool<?>> filteredTools = tools.stream()
                .filter(tool -> toolNames.contains(tool.getName()))
                .filter(tool -> !tool.getName().equals(Meta.class.getSimpleName()))
                .toList();

        // Combine the results of getContext calls on all filtered tools
        return filteredTools.stream()
                .flatMap(tool -> tool.getContext(environmentSettings, prompt, arguments).stream())
                .map(RagDocumentContext::convertToRagDocumentContextVoid)
                .toList();
    }

    @Override
    public RagMultiDocumentContext<Void> call(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        logger.fine("Calling " + getName());

        final MetaConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        logger.info(parsedArgs.toString());

        final String cacheKey = generateCacheKey(parsedArgs, prompt);

        return Try.of(() -> localStorage.getOrPutObject(
                                getName(),
                                getName(),
                                cacheKey,
                                parsedArgs.getCacheTtl(),
                                RagMultiDocumentContext.class,
                                () -> callPrivate(environmentSettings, prompt, arguments))
                        .result())
                .filter(Objects::nonNull)
                .onFailure(NoSuchElementException.class, ex -> logger.info("Failed to generate meta tool result: " + ex.getMessage()))
                .get()
                .convertToRagMultiDocumentContextVoid();
    }

    private String generateCacheKey(final MetaConfig.LocalArguments parsedArgs, final String prompt) {
        return parsedArgs.toString().hashCode() + "_" + prompt.hashCode();
    }

    private RagMultiDocumentContext<Void> callPrivate(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {
        final MetaConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> getContext(environmentSettings, prompt, arguments))
                .map(context -> new RagMultiDocumentContext<Void>(
                        prompt,
                        "You are a helpful agent. You have information from multiple tools. Answer the prompt based on the provided information.",
                        context,
                        null,
                        null,
                        "",
                        new MetaObjectResults(getMetaResults(context, parsedArgs), parsedArgs.getMetaReport(), "")))
                .map(ragDoc -> llmClient.callWithCache(ragDoc, environmentSettings, getName()));

        return exceptionMapping.map(result).get();
    }

    @Override
    public String getContextLabel() {
        return "Unused";
    }

    private List<MetaObjectResult> getMetaResults(final List<RagDocumentContext<Void>> ragContext, final MetaConfig.LocalArguments parsedArgs) {
        final List<MetaObjectResult> results = new ArrayList<>();

        final List<Pair<String, String>> metaFields = List.of(
                Pair.of(parsedArgs.getContextMetaField1(), parsedArgs.getContextMetaPrompt1()),
                Pair.of(parsedArgs.getContextMetaField2(), parsedArgs.getContextMetaPrompt2()),
                Pair.of(parsedArgs.getContextMetaField3(), parsedArgs.getContextMetaPrompt3()),
                Pair.of(parsedArgs.getContextMetaField4(), parsedArgs.getContextMetaPrompt4()),
                Pair.of(parsedArgs.getContextMetaField5(), parsedArgs.getContextMetaPrompt5()),
                Pair.of(parsedArgs.getContextMetaField6(), parsedArgs.getContextMetaPrompt6()),
                Pair.of(parsedArgs.getContextMetaField7(), parsedArgs.getContextMetaPrompt7()),
                Pair.of(parsedArgs.getContextMetaField8(), parsedArgs.getContextMetaPrompt8()),
                Pair.of(parsedArgs.getContextMetaField9(), parsedArgs.getContextMetaPrompt9()),
                Pair.of(parsedArgs.getContextMetaField10(), parsedArgs.getContextMetaPrompt10()),
                Pair.of(parsedArgs.getContextMetaField11(), parsedArgs.getContextMetaPrompt11()),
                Pair.of(parsedArgs.getContextMetaField12(), parsedArgs.getContextMetaPrompt12()),
                Pair.of(parsedArgs.getContextMetaField13(), parsedArgs.getContextMetaPrompt13()),
                Pair.of(parsedArgs.getContextMetaField14(), parsedArgs.getContextMetaPrompt14()),
                Pair.of(parsedArgs.getContextMetaField15(), parsedArgs.getContextMetaPrompt15()),
                Pair.of(parsedArgs.getContextMetaField16(), parsedArgs.getContextMetaPrompt16()),
                Pair.of(parsedArgs.getContextMetaField17(), parsedArgs.getContextMetaPrompt17()),
                Pair.of(parsedArgs.getContextMetaField18(), parsedArgs.getContextMetaPrompt18()),
                Pair.of(parsedArgs.getContextMetaField19(), parsedArgs.getContextMetaPrompt19()),
                Pair.of(parsedArgs.getContextMetaField20(), parsedArgs.getContextMetaPrompt20())
        );

        for (final Pair<String, String> metaField : metaFields) {
            if (StringUtils.isNotBlank(metaField.getLeft()) && StringUtils.isNotBlank(metaField.getRight()) && !ragContext.isEmpty()) {
                final String content = ragContext.stream()
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
        }

        return results;
    }
}

@ApplicationScoped
class MetaConfig {
    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private ToStringGenerator toStringGenerator;

    @Inject
    @ConfigProperty(name = "sb.meta.toolNames")
    private Optional<String> configToolNames;

    @Inject
    @ConfigProperty(name = "sb.meta.ttlSeconds")
    private Optional<String> configTtlSeconds;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaField1")
    private Optional<String> configContextMetaField1;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaPrompt1")
    private Optional<String> configContextMetaPrompt1;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaField2")
    private Optional<String> configContextMetaField2;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaPrompt2")
    private Optional<String> configContextMetaPrompt2;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaField3")
    private Optional<String> configContextMetaField3;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaPrompt3")
    private Optional<String> configContextMetaPrompt3;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaField4")
    private Optional<String> configContextMetaField4;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaPrompt4")
    private Optional<String> configContextMetaPrompt4;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaField5")
    private Optional<String> configContextMetaField5;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaPrompt5")
    private Optional<String> configContextMetaPrompt5;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaField6")
    private Optional<String> configContextMetaField6;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaPrompt6")
    private Optional<String> configContextMetaPrompt6;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaField7")
    private Optional<String> configContextMetaField7;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaPrompt7")
    private Optional<String> configContextMetaPrompt7;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaField8")
    private Optional<String> configContextMetaField8;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaPrompt8")
    private Optional<String> configContextMetaPrompt8;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaField9")
    private Optional<String> configContextMetaField9;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaPrompt9")
    private Optional<String> configContextMetaPrompt9;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaField10")
    private Optional<String> configContextMetaField10;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaPrompt10")
    private Optional<String> configContextMetaPrompt10;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaField11")
    private Optional<String> configContextMetaField11;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaPrompt11")
    private Optional<String> configContextMetaPrompt11;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaField12")
    private Optional<String> configContextMetaField12;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaPrompt12")
    private Optional<String> configContextMetaPrompt12;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaField13")
    private Optional<String> configContextMetaField13;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaPrompt13")
    private Optional<String> configContextMetaPrompt13;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaField14")
    private Optional<String> configContextMetaField14;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaPrompt14")
    private Optional<String> configContextMetaPrompt14;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaField15")
    private Optional<String> configContextMetaField15;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaPrompt15")
    private Optional<String> configContextMetaPrompt15;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaField16")
    private Optional<String> configContextMetaField16;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaPrompt16")
    private Optional<String> configContextMetaPrompt16;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaField17")
    private Optional<String> configContextMetaField17;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaPrompt17")
    private Optional<String> configContextMetaPrompt17;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaField18")
    private Optional<String> configContextMetaField18;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaPrompt18")
    private Optional<String> configContextMetaPrompt18;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaField19")
    private Optional<String> configContextMetaField19;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaPrompt19")
    private Optional<String> configContextMetaPrompt19;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaField20")
    private Optional<String> configContextMetaField20;

    @Inject
    @ConfigProperty(name = "sb.meta.contextMetaPrompt20")
    private Optional<String> configContextMetaPrompt20;

    @Inject
    @ConfigProperty(name = "sb.meta.metareport")
    private Optional<String> configMetaReport;

    public Optional<String> getConfigToolNames() {
        return configToolNames;
    }

    public Optional<String> getConfigTtlSeconds() {
        return configTtlSeconds;
    }

    public Optional<String> getConfigContextMetaField1() {
        return configContextMetaField1;
    }

    public Optional<String> getConfigContextMetaPrompt1() {
        return configContextMetaPrompt1;
    }

    public Optional<String> getConfigContextMetaField2() {
        return configContextMetaField2;
    }

    public Optional<String> getConfigContextMetaPrompt2() {
        return configContextMetaPrompt2;
    }

    public Optional<String> getConfigContextMetaField3() {
        return configContextMetaField3;
    }

    public Optional<String> getConfigContextMetaPrompt3() {
        return configContextMetaPrompt3;
    }

    public Optional<String> getConfigContextMetaField4() {
        return configContextMetaField4;
    }

    public Optional<String> getConfigContextMetaPrompt4() {
        return configContextMetaPrompt4;
    }

    public Optional<String> getConfigContextMetaField5() {
        return configContextMetaField5;
    }

    public Optional<String> getConfigContextMetaPrompt5() {
        return configContextMetaPrompt5;
    }

    public Optional<String> getConfigContextMetaField6() {
        return configContextMetaField6;
    }

    public Optional<String> getConfigContextMetaPrompt6() {
        return configContextMetaPrompt6;
    }

    public Optional<String> getConfigContextMetaField7() {
        return configContextMetaField7;
    }

    public Optional<String> getConfigContextMetaPrompt7() {
        return configContextMetaPrompt7;
    }

    public Optional<String> getConfigContextMetaField8() {
        return configContextMetaField8;
    }

    public Optional<String> getConfigContextMetaPrompt8() {
        return configContextMetaPrompt8;
    }

    public Optional<String> getConfigContextMetaField9() {
        return configContextMetaField9;
    }

    public Optional<String> getConfigContextMetaPrompt9() {
        return configContextMetaPrompt9;
    }

    public Optional<String> getConfigContextMetaField10() {
        return configContextMetaField10;
    }

    public Optional<String> getConfigContextMetaPrompt10() {
        return configContextMetaPrompt10;
    }

    public Optional<String> getConfigContextMetaField11() {
        return configContextMetaField11;
    }

    public Optional<String> getConfigContextMetaPrompt11() {
        return configContextMetaPrompt11;
    }

    public Optional<String> getConfigContextMetaField12() {
        return configContextMetaField12;
    }

    public Optional<String> getConfigContextMetaPrompt12() {
        return configContextMetaPrompt12;
    }

    public Optional<String> getConfigContextMetaField13() {
        return configContextMetaField13;
    }

    public Optional<String> getConfigContextMetaPrompt13() {
        return configContextMetaPrompt13;
    }

    public Optional<String> getConfigContextMetaField14() {
        return configContextMetaField14;
    }

    public Optional<String> getConfigContextMetaPrompt14() {
        return configContextMetaPrompt14;
    }

    public Optional<String> getConfigContextMetaField15() {
        return configContextMetaField15;
    }

    public Optional<String> getConfigContextMetaPrompt15() {
        return configContextMetaPrompt15;
    }

    public Optional<String> getConfigContextMetaField16() {
        return configContextMetaField16;
    }

    public Optional<String> getConfigContextMetaPrompt16() {
        return configContextMetaPrompt16;
    }

    public Optional<String> getConfigContextMetaField17() {
        return configContextMetaField17;
    }

    public Optional<String> getConfigContextMetaPrompt17() {
        return configContextMetaPrompt17;
    }

    public Optional<String> getConfigContextMetaField18() {
        return configContextMetaField18;
    }

    public Optional<String> getConfigContextMetaPrompt18() {
        return configContextMetaPrompt18;
    }

    public Optional<String> getConfigContextMetaField19() {
        return configContextMetaField19;
    }

    public Optional<String> getConfigContextMetaPrompt19() {
        return configContextMetaPrompt19;
    }

    public Optional<String> getConfigContextMetaField20() {
        return configContextMetaField20;
    }

    public Optional<String> getConfigContextMetaPrompt20() {
        return configContextMetaPrompt20;
    }

    public Optional<String> getConfigMetaReport() {
        return configMetaReport;
    }

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    public ToStringGenerator getToStringGenerator() {
        return toStringGenerator;
    }

    public class LocalArguments {
        private final List<ToolArgs> arguments;
        private final String prompt;
        private final Map<String, String> context;

        public LocalArguments(final List<ToolArgs> arguments, final String prompt, final Map<String, String> context) {
            this.arguments = arguments;
            this.prompt = prompt;
            this.context = context;
        }

        public String toString() {
            return getToStringGenerator().generateGetterConfig(this);
        }

        public List<String> getToolNames() {
            return getArgsAccessor().getArgumentList(
                            getConfigToolNames()::get,
                            arguments,
                            context,
                            Meta.META_TOOL_NAMES_ARG,
                            Meta.META_TOOL_NAMES_ARG,
                            "")
                    .stream()
                    .map(Argument::value)
                    .toList();
        }

        public int getCacheTtl() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigTtlSeconds()::get,
                    arguments,
                    context,
                    Meta.META_TTL_SECONDS_ARG,
                    Meta.META_TTL_SECONDS_ARG,
                    "604800");

            return Math.max(0, Integer.parseInt(argument.getSafeValue()));
        }

        public String getContextMetaField1() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaField1()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_FIELD_1_ARG,
                    Meta.META_CONTEXT_META_FIELD_1_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaPrompt1() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaPrompt1()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_PROMPT_1_ARG,
                    Meta.META_CONTEXT_META_PROMPT_1_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaField2() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaField2()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_FIELD_2_ARG,
                    Meta.META_CONTEXT_META_FIELD_2_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaPrompt2() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaPrompt2()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_PROMPT_2_ARG,
                    Meta.META_CONTEXT_META_PROMPT_2_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaField3() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaField3()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_FIELD_3_ARG,
                    Meta.META_CONTEXT_META_FIELD_3_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaPrompt3() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaPrompt3()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_PROMPT_3_ARG,
                    Meta.META_CONTEXT_META_PROMPT_3_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaField4() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaField4()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_FIELD_4_ARG,
                    Meta.META_CONTEXT_META_FIELD_4_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaPrompt4() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaPrompt4()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_PROMPT_4_ARG,
                    Meta.META_CONTEXT_META_PROMPT_4_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaField5() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaField5()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_FIELD_5_ARG,
                    Meta.META_CONTEXT_META_FIELD_5_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaPrompt5() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaPrompt5()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_PROMPT_5_ARG,
                    Meta.META_CONTEXT_META_PROMPT_5_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaField6() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaField6()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_FIELD_6_ARG,
                    Meta.META_CONTEXT_META_FIELD_6_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaPrompt6() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaPrompt6()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_PROMPT_6_ARG,
                    Meta.META_CONTEXT_META_PROMPT_6_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaField7() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaField7()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_FIELD_7_ARG,
                    Meta.META_CONTEXT_META_FIELD_7_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaPrompt7() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaPrompt7()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_PROMPT_7_ARG,
                    Meta.META_CONTEXT_META_PROMPT_7_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaField8() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaField8()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_FIELD_8_ARG,
                    Meta.META_CONTEXT_META_FIELD_8_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaPrompt8() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaPrompt8()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_PROMPT_8_ARG,
                    Meta.META_CONTEXT_META_PROMPT_8_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaField9() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaField9()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_FIELD_9_ARG,
                    Meta.META_CONTEXT_META_FIELD_9_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaPrompt9() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaPrompt9()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_PROMPT_9_ARG,
                    Meta.META_CONTEXT_META_PROMPT_9_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaField10() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaField10()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_FIELD_10_ARG,
                    Meta.META_CONTEXT_META_FIELD_10_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaPrompt10() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaPrompt10()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_PROMPT_10_ARG,
                    Meta.META_CONTEXT_META_PROMPT_10_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaField11() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaField11()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_FIELD_11_ARG,
                    Meta.META_CONTEXT_META_FIELD_11_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaPrompt11() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaPrompt11()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_PROMPT_11_ARG,
                    Meta.META_CONTEXT_META_PROMPT_11_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaField12() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaField12()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_FIELD_12_ARG,
                    Meta.META_CONTEXT_META_FIELD_12_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaPrompt12() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaPrompt12()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_PROMPT_12_ARG,
                    Meta.META_CONTEXT_META_PROMPT_12_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaField13() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaField13()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_FIELD_13_ARG,
                    Meta.META_CONTEXT_META_FIELD_13_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaPrompt13() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaPrompt13()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_PROMPT_13_ARG,
                    Meta.META_CONTEXT_META_PROMPT_13_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaField14() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaField14()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_FIELD_14_ARG,
                    Meta.META_CONTEXT_META_FIELD_14_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaPrompt14() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaPrompt14()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_PROMPT_14_ARG,
                    Meta.META_CONTEXT_META_PROMPT_14_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaField15() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaField15()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_FIELD_15_ARG,
                    Meta.META_CONTEXT_META_FIELD_15_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaPrompt15() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaPrompt15()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_PROMPT_15_ARG,
                    Meta.META_CONTEXT_META_PROMPT_15_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaField16() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaField16()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_FIELD_16_ARG,
                    Meta.META_CONTEXT_META_FIELD_16_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaPrompt16() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaPrompt16()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_PROMPT_16_ARG,
                    Meta.META_CONTEXT_META_PROMPT_16_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaField17() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaField17()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_FIELD_17_ARG,
                    Meta.META_CONTEXT_META_FIELD_17_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaPrompt17() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaPrompt17()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_PROMPT_17_ARG,
                    Meta.META_CONTEXT_META_PROMPT_17_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaField18() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaField18()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_FIELD_18_ARG,
                    Meta.META_CONTEXT_META_FIELD_18_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaPrompt18() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaPrompt18()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_PROMPT_18_ARG,
                    Meta.META_CONTEXT_META_PROMPT_18_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaField19() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaField19()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_FIELD_19_ARG,
                    Meta.META_CONTEXT_META_FIELD_19_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaPrompt19() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaPrompt19()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_PROMPT_19_ARG,
                    Meta.META_CONTEXT_META_PROMPT_19_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaField20() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaField20()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_FIELD_20_ARG,
                    Meta.META_CONTEXT_META_FIELD_20_ARG,
                    "").getSafeValue();
        }

        public String getContextMetaPrompt20() {
            return getArgsAccessor().getArgument(
                    getConfigContextMetaPrompt20()::get,
                    arguments,
                    context,
                    Meta.META_CONTEXT_META_PROMPT_20_ARG,
                    Meta.META_CONTEXT_META_PROMPT_20_ARG,
                    "").getSafeValue();
        }

        public String getMetaReport() {
            return getArgsAccessor().getArgument(
                    getConfigMetaReport()::get,
                    arguments,
                    context,
                    Meta.META_META_REPORT_ARG,
                    Meta.META_META_REPORT_ARG,
                    "").getSafeValue();
        }
    }
}
