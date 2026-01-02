package secondbrain.domain.tools.meta;

import com.google.common.collect.ImmutableList;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.apache.commons.lang3.BooleanUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jooq.lambda.Seq;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptionhandling.ExceptionMapping;
import secondbrain.domain.hooks.HooksContainer;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.domain.tools.rating.RatingTool;
import secondbrain.infrastructure.llm.LlmClient;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * This tool combines many sub-tools to allow a single prompt to be answered by multiple different
 * data sources.
 */
@ApplicationScoped
public class Meta implements Tool<Void> {
    public static final String META_TOOL_NAMES_ARG = "toolNames";
    public static final String META_TTL_SECONDS_ARG = "ttlSeconds";

    @Inject
    private HooksContainer hooksContainer;

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
                new ToolArguments(CommonArguments.CONTENT_RATING_QUESTION_ARG, "The question used to determine the content rating of a document", ""),
                new ToolArguments(CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, "The minimum rating a document must have to be included in the context", "0"),
                new ToolArguments(CommonArguments.DEFAULT_RATING_ARG, "The default rating to assign to documents when no rating can be determined", "10"),
                new ToolArguments(CommonArguments.FILTER_GREATER_THAN_ARG, "Set to true to filter out any documents with a rating greater than the specified minimum rating", "false"),
                new ToolArguments(CommonArguments.KEYWORDS_ARG, "The keywords to use for context filtering", ""),
                new ToolArguments(CommonArguments.KEYWORD_WINDOW_ARG, "The window size to use when extracting keywords from documents", ""),
                new ToolArguments(CommonArguments.PREINITIALIZATION_HOOKS_ARG, "The names of classes implementing the PreProcessingHook interface to apply before initialization", ""),
                new ToolArguments(CommonArguments.PREPROCESSOR_HOOKS_ARG, "The names of classes implementing the PreProcessingHook interface to apply before processing the document", ""),
                new ToolArguments(CommonArguments.POSTINFERENCE_HOOKS_ARG, "The name of classes implementing the PostInferenceHook interface to apply after passing the context to the LLM", ""),
                new ToolArguments(CommonArguments.SUMMARIZE_DOCUMENT_ARG, "Whether to summarize the document before including it in the final context", "false"),
                new ToolArguments(CommonArguments.SUMMARIZE_DOCUMENT_PROMPT_ARG, "The prompt to use when summarizing the document", ""),
                new ToolArguments(CommonArguments.DAYS_ARG, "The number of days to look back when retrieving recent documents", "7"),
                new ToolArguments(CommonArguments.ENTITY_NAME_CONTEXT_ARG, "The entity name used to embed in sentences converted to embeddings", "")
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

        /*
            Common arguments are exposed by the meta tool and passed to each sub-tool to allow common
            values to be shared across all tools. Sub tools do not need to implement these arguments,
            but if they do, the values will be shared.
         */
        final List<ToolArgs> toolArgs = List.of(
                new ToolArgs(CommonArguments.CONTENT_RATING_QUESTION_ARG, parsedArgs.getIndividualContextFilterQuestion(), true),
                new ToolArgs(CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, parsedArgs.getIndividualContextFilterMinimumRating() + "", true),
                new ToolArgs(CommonArguments.DEFAULT_RATING_ARG, parsedArgs.getDefaultRating() + "", true),
                new ToolArgs(CommonArguments.FILTER_GREATER_THAN_ARG, parsedArgs.getFilterGreaterThan() + "", true),
                new ToolArgs(CommonArguments.KEYWORDS_ARG, parsedArgs.getKeywords(), true),
                new ToolArgs(CommonArguments.KEYWORD_WINDOW_ARG, parsedArgs.getKeywordWindow() + "", true),
                new ToolArgs(CommonArguments.PREINITIALIZATION_HOOKS_ARG, parsedArgs.getPreInitializationHooks(), true),
                new ToolArgs(CommonArguments.PREPROCESSOR_HOOKS_ARG, parsedArgs.getPreProcessorHooks(), true),
                new ToolArgs(CommonArguments.POSTINFERENCE_HOOKS_ARG, parsedArgs.getPostInferenceHooks(), true),
                new ToolArgs(CommonArguments.SUMMARIZE_DOCUMENT_ARG, parsedArgs.getSummarizeDocument() + "", true),
                new ToolArgs(CommonArguments.SUMMARIZE_DOCUMENT_PROMPT_ARG, parsedArgs.getSummarizeDocumentPrompt(), true),
                new ToolArgs(CommonArguments.DAYS_ARG, parsedArgs.getDays() + "", true),
                new ToolArgs(CommonArguments.ENTITY_NAME_CONTEXT_ARG, parsedArgs.getEntityName(), true)
        );

        // Get the reg docs generated by preinitialization hooks
        final List<RagDocumentContext<Void>> preinitHooks = Seq.seq(hooksContainer.getMatchingPreProcessorHooks(parsedArgs.getPreInitializationHooks()))
                .foldLeft(List.of(), (docs, hook) -> hook.process(getName(), docs));

        // Get the rag docs from each tool
        final List<RagDocumentContext<Void>> ragDocs = filteredTools.stream()
                .flatMap(tool -> tool.getContext(environmentSettings, prompt, toolArgs).stream())
                .map(RagDocumentContext::convertToRagDocumentContextVoid)
                .toList();

        // Combine preinitialization hooks with ragDocs
        final List<RagDocumentContext<Void>> combinedDocs = Stream.concat(preinitHooks.stream(), ragDocs.stream()).toList();

        // Apply preprocessing hooks
        return Seq.seq(hooksContainer.getMatchingPreProcessorHooks(parsedArgs.getPreProcessorHooks()))
                .foldLeft(combinedDocs, (docs, hook) -> hook.process(getName(), docs));
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
                .onFailure(NoSuchElementException.class, ex -> logger.warning("Failed to generate meta tool result: " + ex.getMessage()))
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
                        null))
                .map(ragDoc -> llmClient.callWithCache(ragDoc, environmentSettings, getName()));

        final RagMultiDocumentContext<Void> mappedResult = exceptionMapping.map(result).get();

        // Apply postinference hooks
        return Seq.seq(hooksContainer.getMatchingPostInferenceHooks(parsedArgs.getPostInferenceHooks()))
                .foldLeft(mappedResult, (docs, hook) -> hook.process(getName(), docs));
    }

    @Override
    public String getContextLabel() {
        return "Unused";
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
    @ConfigProperty(name = "sb.meta.individualContextFilterQuestion")
    private Optional<String> configIndividualContextFilterQuestion;

    @Inject
    @ConfigProperty(name = "sb.meta.individualContextFilterMinimumRating")
    private Optional<String> configIndividualContextFilterMinimumRating;

    @Inject
    @ConfigProperty(name = "sb.meta.defaultRating")
    private Optional<String> configDefaultRating;

    @Inject
    @ConfigProperty(name = "sb.meta.filterGreaterThan")
    private Optional<String> configFilterGreaterThan;

    @Inject
    @ConfigProperty(name = "sb.meta.keywords")
    private Optional<String> configKeywords;

    @Inject
    @ConfigProperty(name = "sb.meta.keywordWindow")
    private Optional<String> configKeywordWindow;

    @Inject
    @ConfigProperty(name = "sb.meta.preInitializationHooks")
    private Optional<String> configPreInitializationHooks;

    @Inject
    @ConfigProperty(name = "sb.meta.preProcessorHooks")
    private Optional<String> configPreProcessorHooks;

    @Inject
    @ConfigProperty(name = "sb.meta.postInferenceHooks")
    private Optional<String> configPostInferenceHooks;

    @Inject
    @ConfigProperty(name = "sb.meta.summarizeDocument")
    private Optional<String> configSummarizeDocument;

    @Inject
    @ConfigProperty(name = "sb.meta.summarizeDocumentPrompt")
    private Optional<String> configSummarizeDocumentPrompt;

    @Inject
    @ConfigProperty(name = "sb.meta.days")
    private Optional<String> configDays;

    @Inject
    @ConfigProperty(name = "sb.meta.entityName")
    private Optional<String> configEntityName;

    public Optional<String> getConfigToolNames() {
        return configToolNames;
    }

    public Optional<String> getConfigTtlSeconds() {
        return configTtlSeconds;
    }

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    public ToStringGenerator getToStringGenerator() {
        return toStringGenerator;
    }

    public Optional<String> getConfigIndividualContextFilterQuestion() {
        return configIndividualContextFilterQuestion;
    }

    public Optional<String> getConfigIndividualContextFilterMinimumRating() {
        return configIndividualContextFilterMinimumRating;
    }

    public Optional<String> getConfigDefaultRating() {
        return configDefaultRating;
    }

    public Optional<String> getConfigFilterGreaterThan() {
        return configFilterGreaterThan;
    }

    public Optional<String> getConfigKeywords() {
        return configKeywords;
    }

    public Optional<String> getConfigKeywordWindow() {
        return configKeywordWindow;
    }

    public Optional<String> getConfigPreInitializationHooks() {
        return configPreInitializationHooks;
    }

    public Optional<String> getConfigPreProcessorHooks() {
        return configPreProcessorHooks;
    }

    public Optional<String> getConfigPostInferenceHooks() {
        return configPostInferenceHooks;
    }

    public Optional<String> getConfigSummarizeDocument() {
        return configSummarizeDocument;
    }

    public Optional<String> getConfigSummarizeDocumentPrompt() {
        return configSummarizeDocumentPrompt;
    }

    public Optional<String> getConfigDays() {
        return configDays;
    }

    public Optional<String> getConfigEntityName() {
        return configEntityName;
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

        public String getIndividualContextFilterQuestion() {
            return getArgsAccessor().getArgument(
                            getConfigIndividualContextFilterQuestion()::get,
                            arguments,
                            context,
                            CommonArguments.CONTENT_RATING_QUESTION_ARG,
                            CommonArguments.CONTENT_RATING_QUESTION_ARG,
                            "")
                    .getSafeValue();
        }

        public Integer getIndividualContextFilterMinimumRating() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigIndividualContextFilterMinimumRating()::get,
                    arguments,
                    context,
                    CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG,
                    CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG,
                    "0");

            return org.apache.commons.lang.math.NumberUtils.toInt(argument.getSafeValue(), 0);
        }

        public Integer getDefaultRating() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigDefaultRating()::get,
                    arguments,
                    context,
                    CommonArguments.DEFAULT_RATING_ARG,
                    CommonArguments.DEFAULT_RATING_ARG,
                    "10");

            return Math.max(0, org.apache.commons.lang.math.NumberUtils.toInt(argument.getSafeValue(), 10));
        }

        public Boolean getFilterGreaterThan() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigFilterGreaterThan()::get,
                    arguments,
                    context,
                    CommonArguments.FILTER_GREATER_THAN_ARG,
                    CommonArguments.FILTER_GREATER_THAN_ARG,
                    "false");

            return BooleanUtils.toBoolean(argument.getSafeValue());
        }

        public String getKeywords() {
            return getArgsAccessor().getArgument(
                            getConfigKeywords()::get,
                            arguments,
                            context,
                            CommonArguments.KEYWORDS_ARG,
                            CommonArguments.KEYWORDS_ARG,
                            "")
                    .getSafeValue();
        }

        public Integer getKeywordWindow() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigKeywordWindow()::get,
                    arguments,
                    context,
                    CommonArguments.KEYWORD_WINDOW_ARG,
                    CommonArguments.KEYWORD_WINDOW_ARG,
                    Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH + "");

            return org.apache.commons.lang.math.NumberUtils.toInt(argument.getSafeValue(), Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH);
        }

        public String getPreInitializationHooks() {
            return getArgsAccessor().getArgument(
                            getConfigPreInitializationHooks()::get,
                            arguments,
                            context,
                            CommonArguments.PREINITIALIZATION_HOOKS_ARG,
                            CommonArguments.PREINITIALIZATION_HOOKS_ARG,
                            "")
                    .getSafeValue();
        }

        public String getPreProcessorHooks() {
            return getArgsAccessor().getArgument(
                            getConfigPreProcessorHooks()::get,
                            arguments,
                            context,
                            CommonArguments.PREPROCESSOR_HOOKS_ARG,
                            CommonArguments.PREPROCESSOR_HOOKS_ARG,
                            "")
                    .getSafeValue();
        }

        public String getPostInferenceHooks() {
            return getArgsAccessor().getArgument(
                            getConfigPostInferenceHooks()::get,
                            arguments,
                            context,
                            CommonArguments.POSTINFERENCE_HOOKS_ARG,
                            CommonArguments.POSTINFERENCE_HOOKS_ARG,
                            "")
                    .getSafeValue();
        }

        public Boolean getSummarizeDocument() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigSummarizeDocument()::get,
                    arguments,
                    context,
                    CommonArguments.SUMMARIZE_DOCUMENT_ARG,
                    CommonArguments.SUMMARIZE_DOCUMENT_ARG,
                    "false");

            return BooleanUtils.toBoolean(argument.getSafeValue());
        }

        public String getSummarizeDocumentPrompt() {
            return getArgsAccessor().getArgument(
                            getConfigSummarizeDocumentPrompt()::get,
                            arguments,
                            context,
                            CommonArguments.SUMMARIZE_DOCUMENT_PROMPT_ARG,
                            CommonArguments.SUMMARIZE_DOCUMENT_PROMPT_ARG,
                            "")
                    .getSafeValue();
        }

        public Integer getDays() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigDays()::get,
                    arguments,
                    context,
                    CommonArguments.DAYS_ARG,
                    CommonArguments.DAYS_ARG,
                    "7");

            return Math.max(0, Integer.parseInt(argument.getSafeValue()));
        }

        public String getEntityName() {
            return getArgsAccessor().getArgument(
                            getConfigEntityName()::get,
                            arguments,
                            context,
                            CommonArguments.ENTITY_NAME_CONTEXT_ARG,
                            CommonArguments.ENTITY_NAME_CONTEXT_ARG,
                            "")
                    .getSafeValue();
        }
    }
}
