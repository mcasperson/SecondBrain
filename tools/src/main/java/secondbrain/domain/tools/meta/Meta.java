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
import secondbrain.domain.exceptions.EmptyList;
import secondbrain.domain.hooks.HooksContainer;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.config.LocalSkipEmptyInLastDuration;
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
 * data sources. This is a more generic version of the MultiSlackZenGoogle tool.
 * <p>
 * An example execution:
 * <p>
 * java -jar secondbrain.jar \
 *   -Dsb.meta.toolNames="Gong,Salesforce" \
 *   -Dsb.meta.individualContextFilterQuestion="Does the document describe the use of GitHub Actions?" \
 *   -Dsb.meta.individualContextFilterMinimumRating=6 \
 *   -Dsb.meta.defaultRating=0 \
 *   -Dsb.meta.keywords="GitHub,Actions" \
 *   -Dsb.meta.summarizeDocument=true \
 *   -Dsb.meta.summarizeDocumentPrompt="Write a 3-paragraph summary of the document with a focus on any mentions of GitHub Actions."
 */
@ApplicationScoped
public class Meta implements Tool<Void> {
    public static final String META_TOOL_NAMES_ARG = "toolNames";
    public static final String META_TTL_SECONDS_ARG = "ttlSeconds";
    public static final String META_DISABLE_PROMPT_ARG = "disablePrompt";

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
                new ToolArguments(META_DISABLE_PROMPT_ARG, "Set to true to disable sending the prompt to the LLM and return only the context documents", "false"),
                new ToolArguments(CommonArguments.SKIP_EMPTY_IN_LAST_DURATION, "Set to true to skip results when there are no documents returned within the specified duration", "false"),
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
                new ToolArguments(CommonArguments.HOURS_ARG, "The number of hours to look back when retrieving recent documents", "0"),
                new ToolArguments(CommonArguments.ENTITY_NAME_CONTEXT_ARG, "The entity name used to embed in sentences converted to embeddings", ""),
                new ToolArguments(CommonArguments.START_DATE, "The optional start date (ISO-8601) to limit the scope of documents retrieved by sub-tools", ""),
                new ToolArguments(CommonArguments.END_DATE, "The optional end date (ISO-8601) to limit the scope of documents retrieved by sub-tools", ""),
                new ToolArguments(CommonArguments.REQUIRE_COMPANY, "Set to true to require a company name to be supplied before retrieving data from sub-tools", "false")
        );
    }

    @Override
    public List<RagDocumentContext<Void>> getContext(final Map<String, String> environmentSettings, final String prompt, List<ToolArgs> arguments) {
        final MetaConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        // Use the parsed tool names
        final List<String> toolNames = parsedArgs.getToolNames();

        // Filter the tools to those included in the toolNames
        final List<Tool<?>> filteredTools = tools.stream()
                .filter(tool -> toolNames.stream()
                        .map(String::toLowerCase)
                        .toList()
                        .contains(tool.getName().toLowerCase()))
                .filter(tool -> !tool.getName().equals(Meta.class.getSimpleName()))
                .toList();

        if (filteredTools.isEmpty()) {
            throw new EmptyList("No valid tools found for names: " + toolNames);
        }

        filteredTools.forEach(tool -> logger.info("Including tool: " + tool.getName()));

        /*
            Common arguments are exposed by the meta tool and passed to each sub-tool to allow common
            values to be shared across all tools. Sub tools do not need to implement these arguments,
            but if they do, the values will be shared.
         */
        final List<ToolArgs> toolArgs = List.of(
                new ToolArgs(CommonArguments.SKIP_EMPTY_IN_LAST_DURATION, parsedArgs.isSkipEmptyInLastDuration() + "", true),
                new ToolArgs(CommonArguments.CONTENT_RATING_QUESTION_ARG, parsedArgs.getIndividualContextFilterQuestion(), true),
                new ToolArgs(CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, parsedArgs.getIndividualContextFilterMinimumRating() + "", true),
                new ToolArgs(CommonArguments.DEFAULT_RATING_ARG, parsedArgs.getDefaultRating() + "", true),
                new ToolArgs(CommonArguments.FILTER_GREATER_THAN_ARG, parsedArgs.getFilterGreaterThan() + "", true),
                new ToolArgs(CommonArguments.KEYWORDS_ARG, parsedArgs.getKeywords(), true),
                new ToolArgs(CommonArguments.KEYWORD_WINDOW_ARG, parsedArgs.getKeywordWindow() + "", true),
                new ToolArgs(CommonArguments.SUMMARIZE_DOCUMENT_ARG, parsedArgs.getSummarizeDocument() + "", true),
                new ToolArgs(CommonArguments.SUMMARIZE_DOCUMENT_PROMPT_ARG, parsedArgs.getSummarizeDocumentPrompt(), true),
                new ToolArgs(CommonArguments.DAYS_ARG, parsedArgs.getDays() + "", true),
                new ToolArgs(CommonArguments.HOURS_ARG, parsedArgs.getHours() + "", true),
                new ToolArgs(CommonArguments.ENTITY_NAME_CONTEXT_ARG, parsedArgs.getEntityName(), true),
                new ToolArgs(CommonArguments.START_DATE, parsedArgs.getStartDate(), true),
                new ToolArgs(CommonArguments.END_DATE, parsedArgs.getEndDate(), true),
                new ToolArgs(CommonArguments.REQUIRE_COMPANY, parsedArgs.isRequireCompany() + "", true)
        );

        // Get the rag docs generated by preinitialization hooks
        final List<RagDocumentContext<Void>> preinitHooks = Seq.seq(hooksContainer.getMatchingPreProcessorHooks(parsedArgs.getPreInitializationHooks()))
                .foldLeft(List.of(), (docs, hook) -> hook.process(getName(), docs));

        // Get the rag docs from each tool
        final List<RagDocumentContext<Void>> ragDocs = filteredTools.stream()
                .flatMap(tool -> Try.of(() -> tool.getContext(environmentSettings, prompt, toolArgs).stream())
                        .onFailure(ex -> logger.severe("Failed to get context for tool " + tool.getName() + ": " + ex.toString()))
                        .getOrElse(Stream.of()))
                .map(RagDocumentContext::convertToRagDocumentContextVoid)
                .toList();

        // Combine reg docs generated by preinitialization hooks with ragDocs
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

        return Try.of(() -> callPrivate(environmentSettings, prompt, arguments))
                .filter(Objects::nonNull)
                .onFailure(NoSuchElementException.class, ex -> logger.warning("Failed to generate meta tool result: " + ex.getMessage()))
                .get()
                .convertToRagMultiDocumentContextVoid();
    }

    private RagMultiDocumentContext<Void> callPrivate(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {
        final MetaConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final Try<List<RagDocumentContext<Void>>> contextResult = Try.of(() -> getContext(environmentSettings, prompt, arguments));

        // If we have disabled the prompt (useful when you just want to get the intermediate data
        // generated by the tools), we return an empty result.
        if (parsedArgs.getDisablePrompt()) {
            logger.info("Prompt has been disabled, returning unprocessed result");
            return contextResult.map(context -> new RagMultiDocumentContext<Void>(
                    prompt,
                    "Unused",
                    context,
                    "Unused",
                    null,
                    "",
                    null))
                    .get();
        }

        final Try<RagMultiDocumentContext<Void>> result = contextResult
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
    @ConfigProperty(name = "sb.meta.disablePrompt")
    private Optional<String> configDisablePrompt;

    @Inject
    @ConfigProperty(name = "sb.meta.skipEmptyInLastDuration", defaultValue = "")
    private Optional<String> configSkipEmptyInLastDuration;

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
    @ConfigProperty(name = "sb.meta.hours")
    private Optional<String> configHours;

    @Inject
    @ConfigProperty(name = "sb.meta.entityName")
    private Optional<String> configEntityName;

    @Inject
    @ConfigProperty(name = "sb.meta.startDate")
    private Optional<String> configStartDate;

    @Inject
    @ConfigProperty(name = "sb.meta.endDate")
    private Optional<String> configEndDate;

    @Inject
    @ConfigProperty(name = "sb.meta.requireCompany")
    private Optional<String> configRequireCompany;

    public Optional<String> getConfigToolNames() {
        return configToolNames;
    }

    public Optional<String> getConfigTtlSeconds() {
        return configTtlSeconds;
    }

    public Optional<String> getConfigDisablePrompt() {
        return configDisablePrompt;
    }

    public Optional<String> getConfigSkipEmptyInLastDuration() {
        return configSkipEmptyInLastDuration;
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

    public Optional<String> getConfigHours() {
        return configHours;
    }

    public Optional<String> getConfigEntityName() {
        return configEntityName;
    }

    public Optional<String> getConfigStartDate() {
        return configStartDate;
    }

    public Optional<String> getConfigEndDate() {
        return configEndDate;
    }

    public Optional<String> getConfigRequireCompany() {
        return configRequireCompany;
    }

    public class LocalArguments implements LocalSkipEmptyInLastDuration {
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

        /**
         * Get the list of tool names to include in the meta tool execution.
         * This is a comma-separated list of tool names that are included in the tools available to the meta tool.
         * You must specify at least one tool name.
         */
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

        /**
         * Get the number of seconds to cache the result of the meta tool execution.
         * This value is passed to the local storage implementation and may be used to determine how long to cache the result.
         */
        public int getCacheTtl() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigTtlSeconds()::get,
                    arguments,
                    context,
                    Meta.META_TTL_SECONDS_ARG,
                    Meta.META_TTL_SECONDS_ARG,
                    "86400");

            return Math.max(0, Integer.parseInt(argument.getSafeValue()));
        }

        /**
         * Whether to disable sending the prompt to the LLM and return only the context documents.
         * If true, the result of the meta tool will be the raw context documents without any LLM inference.
         */
        public Boolean getDisablePrompt() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigDisablePrompt()::get,
                    arguments,
                    context,
                    Meta.META_DISABLE_PROMPT_ARG,
                    Meta.META_DISABLE_PROMPT_ARG,
                    "false");

            return BooleanUtils.toBoolean(argument.getSafeValue());
        }

        /**
         * Get the question used to determine the content rating of a document when filtering documents for inclusion in the context.
         */
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

        /**
         * A value from 0-10 indicating the minimum rating a document must have to be included in the context
         * when filtering documents for inclusion in the context. The default value is 0, meaning all
         * documents are included regardless of rating.
         */
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

        /**
         * The default rating to assign to documents when no rating can be determined.
         * This is a value from 0-10, where 0 is the lowest quality and 10 is the highest quality.
         * The default value is 10, meaning that unrated documents are treated as high quality.
         */
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

        /**
         * Whether to filter out documents with a rating greater than the specified minimum rating (as opposed to
         * filtering out documents with a rating less than the specified minimum rating).
         * Essentially, this reverses the way documents are filtered.
         */
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

        /**
         * The keywords to use for context filtering. This is a comma-separated list of keywords that are used to
         * extract a subset of the document to include in the context. If not specified, the full document is included in the context.
         */
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

        /**
         * The amount of content to include around any keyword matches.
         */
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

        /**
         * The names of any pre-initialization hooks to apply, as a comma-separated list.
         * Pre-initialization hooks are used to generate RagDocumentContexts before any tools are called.
         */
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

        /**
         * The names of any pre-processing hooks to apply, as a comma-separated list.
         * Pre-processing hooks are used to modify the list of RagDocumentContexts returned by the
         * tools before they are passed to the LLM.
         */
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

        /**
         * The names of any post-inference hooks to apply, as a comma-separated list.
         * Post-inference hooks are used to modify the RagMultiDocumentContext after it is returned
         * from the LLM but before it is returned from the meta tool. This can be used to modify the final output.
         */
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

        /**
         * Whether to summarize the document before including it in the final context.
         * If true, the document generated by a tool is summarized before being included in the context passed to the LLM
         * to answer the main prompt. This can be used to reduce the amount of context passed to the LLM while still providing relevant information from the document.
         */
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

        /**
         * The prompt to use when summarizing the document. This is only used if summarizeDocument is true.
         */
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

        /**
         * The number of days to look back when retrieving recent documents. This can be used by tools that retrieve
         * documents based on recency to limit the scope of documents considered for retrieval.
         */
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

        /**
         * The number of hours to look back when retrieving recent documents. This can be used by tools that retrieve
         * documents based on recency to limit the scope of documents considered for retrieval.
         */
        public Integer getHours() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigHours()::get,
                    arguments,
                    context,
                    CommonArguments.HOURS_ARG,
                    CommonArguments.HOURS_ARG,
                    "0");

            return Math.max(0, Integer.parseInt(argument.getSafeValue()));
        }

        /**
         * The entity name used to embed in sentences converted to embeddings. This is used to ensure that sentences
         * extracted from the external content have the name of the entity in them before they are converted to embeddings,
         * which can help ensure that the embeddings are relevant to the entity.
         * TODO: Is this useful for the meta tool? We may need to have a tool above the meta tool for this to be useful. Today, you can only supply one entity.
         */
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

        @Override
        public boolean isSkipEmptyInLastDuration() {
            return Boolean.parseBoolean(getArgsAccessor().getArgument(
                    getConfigSkipEmptyInLastDuration()::get,
                    arguments,
                    context,
                    CommonArguments.SKIP_EMPTY_IN_LAST_DURATION,
                    CommonArguments.SKIP_EMPTY_IN_LAST_DURATION,
                    "false").getSafeValue());
        }

        /**
         * The optional start date (ISO-8601) to limit the scope of documents retrieved by sub-tools.
         */
        public String getStartDate() {
            return getArgsAccessor().getArgument(
                    getConfigStartDate()::get,
                    arguments,
                    context,
                    CommonArguments.START_DATE,
                    CommonArguments.START_DATE,
                    "").getSafeValue();
        }

        /**
         * The optional end date (ISO-8601) to limit the scope of documents retrieved by sub-tools.
         */
        public String getEndDate() {
            return getArgsAccessor().getArgument(
                    getConfigEndDate()::get,
                    arguments,
                    context,
                    CommonArguments.END_DATE,
                    CommonArguments.END_DATE,
                    "").getSafeValue();
        }

        /**
         * Whether to require a company name to be supplied before retrieving data from sub-tools.
         * When true, sub-tools that support this argument will skip retrieval if no company is specified.
         */
        public boolean isRequireCompany() {
            return Boolean.parseBoolean(getArgsAccessor().getArgument(
                    getConfigRequireCompany()::get,
                    arguments,
                    context,
                    CommonArguments.REQUIRE_COMPANY,
                    CommonArguments.REQUIRE_COMPANY,
                    "false").getSafeValue());
        }
    }
}
