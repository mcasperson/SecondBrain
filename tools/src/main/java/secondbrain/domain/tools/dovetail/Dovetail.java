package secondbrain.domain.tools.dovetail;

import io.smallrye.common.annotation.Identifier;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jooq.lambda.Seq;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.config.*;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.date.DateParser;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptionhandling.ExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.hooks.HooksContainer;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.processing.DataToRagDoc;
import secondbrain.domain.processing.RagDocSummarizer;
import secondbrain.domain.processing.RatingFilter;
import secondbrain.domain.processing.RatingMetadata;
import secondbrain.domain.tooldefs.*;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.domain.tools.dovetail.model.DovetailDataDetails;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.dovetail.DovetailClient;
import secondbrain.infrastructure.dovetail.api.DovetailDataItem;
import secondbrain.infrastructure.llm.LlmClient;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;

@ApplicationScoped
public class Dovetail implements Tool<DovetailDataDetails> {
    public static final String DOVETAIL_API_KEY_ARG = "apiKey";
    public static final String DOVETAIL_BASE_URL_ARG = "dovetailBaseUrl";
    public static final String TTL_SECONDS_ARG = "ttlSeconds";
    public static final String MINIMUM_CONTENT_LENGTH_ARG = "minimumContentLength";

    private static final String INSTRUCTIONS = """
            You are a helpful assistant.
            You are given a list of user research data items exported from Dovetail.
            You must assume the provided content is user research data.
            Assume the information required to answer the question is present in the data items.
            Answer the question based on the Dovetail data items provided.
            You will be penalized for answering that you cannot access Dovetail.
            You will be penalized for answering that the data items cannot be accessed.
            """.stripLeading();

    @Inject
    private RatingMetadata ratingMetadata;

    @Inject
    private RatingFilter ratingFilter;

    @Inject
    private DataToRagDoc dataToRagDoc;

    @Inject
    private RagDocSummarizer ragDocSummarizer;

    @Inject
    private DovetailConfig config;

    @Inject
    @Preferred
    private DovetailClient dovetailClient;

    @Inject
    @Preferred
    private LlmClient llmClient;

    @Inject
    private ValidateString validateString;

    @Inject
    private Logger logger;

    @Inject
    private ExceptionMapping exceptionMapping;

    @Inject
    private HooksContainer hooksContainer;

    @Inject
    @Preferred
    private LocalStorage localStorage;

    @Override
    public String getName() {
        return Dovetail.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Returns user research data items from Dovetail, exported as markdown.";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return List.of(
                new ToolArguments(DOVETAIL_API_KEY_ARG, "The Dovetail API key", ""),
                new ToolArguments(DOVETAIL_BASE_URL_ARG, "The base URL of the Dovetail instance used to build deep links (e.g. https://myorg.dovetail.com)", "https://dovetail.com"),
                new ToolArguments(CommonArguments.DAYS_ARG, "The optional number of days worth of data items to return", "0"),
                new ToolArguments(CommonArguments.START_DATE, "The optional date to start retrieving data items from", ""),
                new ToolArguments(CommonArguments.END_DATE, "The optional date to stop retrieving data items at", ""),
                new ToolArguments(CommonArguments.KEYWORDS_ARG, "The optional keywords to limit the data item content to", ""),
                new ToolArguments(CommonArguments.KEYWORD_WINDOW_ARG, "The window size around any matching keywords", ""),
                new ToolArguments(CommonArguments.SUMMARIZE_DOCUMENT_ARG, "Set to true to first summarize each data item", "false"),
                new ToolArguments(CommonArguments.SUMMARIZE_DOCUMENT_PROMPT_ARG, "The prompt used to summarize the data item", ""),
                new ToolArguments(CommonArguments.CONTENT_RATING_QUESTION_ARG, "The question used to determine the content rating of a data item", ""),
                new ToolArguments(CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, "The minimum rating a data item must have to be included in the context", "0"),
                new ToolArguments(CommonArguments.DEFAULT_RATING_ARG, "The default rating to assign to data items when no rating can be determined", "10"),
                new ToolArguments(CommonArguments.FILTER_GREATER_THAN_ARG, "Set to true to filter out any data items with a rating greater than the specified minimum rating", "false"),
                new ToolArguments(CommonArguments.PREINITIALIZATION_HOOKS_ARG, "The names of pre-initialization hooks to apply before collecting data", ""),
                new ToolArguments(CommonArguments.PREPROCESSOR_HOOKS_ARG, "The names of pre-processor hooks to apply before processing the data items", ""),
                new ToolArguments(CommonArguments.POSTINFERENCE_HOOKS_ARG, "The names of post-inference hooks to apply after the LLM has processed the data items", ""),
                new ToolArguments(TTL_SECONDS_ARG, "The number of seconds to cache the Dovetail results", "86400"),
                new ToolArguments(MINIMUM_CONTENT_LENGTH_ARG, "The minimum number of characters a data item's content must have to be included (0 = no minimum)", "0")
        );
    }

    @Override
    public List<RagDocumentContext<DovetailDataDetails>> getContext(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {
        final DovetailConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final String cacheKey = parsedArgs.toString().hashCode() + "_" + prompt.hashCode();
        return Try.of(() -> localStorage.getOrPutGeneric(
                        getName(),
                        getName(),
                        Integer.toString(cacheKey.hashCode()),
                        parsedArgs.getCacheTtl(),
                        List.class,
                        RagDocumentContext.class,
                        DovetailDataDetails.class,
                        () -> getContextPrivate(environmentSettings, prompt, arguments)).result())
                .filter(Objects::nonNull)
                .onFailure(NoSuchElementException.class, ex -> logger.warning("Failed to generate Dovetail context: " + ExceptionUtils.getRootCauseMessage(ex)))
                .get();
    }

    private List<RagDocumentContext<DovetailDataDetails>> getContextPrivate(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {
        final DovetailConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        if (StringUtils.isNotBlank(parsedArgs.getStartDate()) && StringUtils.isNotBlank(parsedArgs.getEndDate())){
            logger.info("Getting context for " + getName() + " from " + parsedArgs.getStartDate() + " to " + parsedArgs.getEndDate());
        } else if (StringUtils.isNotBlank(parsedArgs.getStartDate())) {
            logger.info("Getting context for " + getName() + " from " + parsedArgs.getStartDate());
        } else if (StringUtils.isNotBlank(parsedArgs.getEndDate())) {
            logger.info("Getting context for " + getName() + " to " + parsedArgs.getEndDate());
        } else {
            logger.info("Getting context for " + getName());
        }

        // Get preinitialization hooks before ragdocs
        final List<RagDocumentContext<DovetailDataDetails>> preinitHooks =
                Seq.seq(hooksContainer.getMatchingPreProcessorHooks(parsedArgs.getPreinitializationHooks()))
                        .foldLeft(List.of(), (docs, hook) -> hook.process(getName(), docs));

        final List<DovetailDataItem> items = Try.of(() ->
                dovetailClient.getDataItems(
                        parsedArgs.getSecretApiKey(),
                        parsedArgs.getStartDate(),
                        parsedArgs.getEndDate()))
                .onFailure(ex -> logger.severe("Failed to get Dovetail data items: " + ExceptionUtils.getRootCauseMessage(ex)))
                .get();

        logger.fine("Retrieved " + items.size() + " data items from Dovetail");

        final List<DovetailDataDetails> dataItems = Try.of(() -> items.stream()
                        .filter(item -> !item.deleted())
                        .map(item -> new DovetailDataDetails(
                                item.id(),
                                item.title(),
                                item.project() != null ? item.project().title() : "",
                                item.createdAt(),
                                dovetailClient.exportDataItemAsMarkdown(parsedArgs.getSecretApiKey(), item.id()),
                                parsedArgs.getDovetailBaseUrl()))
                        .toList())
                .onFailure(ex -> logger.severe("Failed to get Dovetail details: " + ExceptionUtils.getRootCauseMessage(ex)))
                .get();

        final List<RagDocumentContext<DovetailDataDetails>> ragDocs = dataItems.stream()
                .map(item -> dataToRagDoc.getDocumentContext(item, getName(), getContextLabelWithMeta(item), parsedArgs))
                .filter(ragDoc -> !validateString.isBlank(ragDoc, RagDocumentContext::document))
                .filter(ragDoc -> parsedArgs.getMinimumContentLength() == 0
                        || ragDoc.document().length() >= parsedArgs.getMinimumContentLength())
                .toList();

        // Combine preinitialization hooks with ragDocs
        final List<RagDocumentContext<DovetailDataDetails>> combinedDocs =
                Stream.concat(preinitHooks.stream(), ragDocs.stream()).toList();

        // Apply preprocessing hooks, and then rating metadata and filtering
        final List<RagDocumentContext<DovetailDataDetails>> context =
                Seq.seq(hooksContainer.getMatchingPreProcessorHooks(parsedArgs.getPreprocessingHooks()))
                        .foldLeft(combinedDocs, (docs, hook) -> hook.process(getName(), docs))
                        .stream()
                        .map(ragDoc -> ragDoc.addIntermediateResult(new IntermediateResult(ragDoc.document(), "Data-Dovetail-" + ragDoc.id() + ".txt")))
                        // Get the metadata, which includes a rating against the filter question if present
                        .map(ragDoc -> ragDoc.addMetadata(ratingMetadata.getMetadata(getName(), environmentSettings, ragDoc, parsedArgs)))
                        // Filter out any documents that don't meet the rating criteria
                        .filter(ragDoc -> ratingFilter.contextMeetsRating(ragDoc, parsedArgs))
                        .map(ragDoc -> parsedArgs.getSummarizeDocument()
                                ? ragDocSummarizer.getDocumentSummary(getName(), getContextLabelWithMeta(ragDoc.source()), "Dovetail", ragDoc, environmentSettings, parsedArgs)
                                : ragDoc)
                        .toList();

        logger.info("Found " + context.size() + " Dovetail data items");

        return context;
    }

    @Override
    public RagMultiDocumentContext<DovetailDataDetails> call(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {
        logger.fine("Calling " + getName());

        final List<RagDocumentContext<DovetailDataDetails>> contextList = getContext(environmentSettings, prompt, arguments);

        final Try<RagMultiDocumentContext<DovetailDataDetails>> result = Try.of(() -> contextList)
                .map(ragDoc -> new RagMultiDocumentContext<>(prompt, INSTRUCTIONS, ragDoc))
                .map(ragDoc -> llmClient.callWithCache(ragDoc, environmentSettings, getName()));

        final RagMultiDocumentContext<DovetailDataDetails> mappedResult = exceptionMapping.map(result).get();

        final DovetailConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        // Apply postinference hooks
        return Seq.seq(hooksContainer.getMatchingPostInferenceHooks(parsedArgs.getPostInferenceHooks()))
                .foldLeft(mappedResult, (docs, hook) -> hook.process(getName(), docs));
    }

    @Override
    public String getContextLabel() {
        return "Dovetail data item";
    }

    private String getContextLabelWithMeta(@Nullable final DovetailDataDetails item) {
        if (item == null) {
            return getContextLabel();
        }
        final String project = StringUtils.isNotBlank(item.projectTitle()) ? " [" + item.projectTitle() + "]" : "";
        final String date = StringUtils.isNotBlank(item.createdAt()) ? " (" + item.createdAt() + ")" : "";
        return getContextLabel() + ": \"" + Objects.requireNonNullElse(item.title(), item.id()) + "\"" + project + date;
    }
}

@ApplicationScoped
class DovetailConfig {
    private static final int DEFAULT_RATING = 10;
    private static final int DEFAULT_TTL_SECONDS = 60 * 60 * 24; // 24 hours

    @Inject
    @Identifier("everything")
    private DateParser dateParser;

    @Inject
    private Logger logger;

    @Inject
    private ToStringGenerator toStringGenerator;

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    @Identifier("AES")
    private Encryptor textEncryptor;

    @Inject
    private ValidateString validateString;

    @Inject
    @ConfigProperty(name = "sb.dovetail.apiKey")
    private Optional<String> configApiKey;

    @Inject
    @ConfigProperty(name = "sb.dovetail.baseUrl", defaultValue = "https://dovetail.com")
    private Optional<String> configBaseUrl;

    @Inject
    @ConfigProperty(name = "sb.dovetail.days")
    private Optional<String> configDays;

    @Inject
    @ConfigProperty(name = "sb.dovetail.startDate")
    private Optional<String> configStartDate;

    @Inject
    @ConfigProperty(name = "sb.dovetail.endDate")
    private Optional<String> configEndDate;

    @Inject
    @ConfigProperty(name = "sb.dovetail.keywords")
    private Optional<String> configKeywords;

    @Inject
    @ConfigProperty(name = "sb.dovetail.keywordWindow")
    private Optional<String> configKeywordWindow;

    @Inject
    @ConfigProperty(name = "sb.dovetail.summarizeDocument")
    private Optional<String> configSummarizeDocument;

    @Inject
    @ConfigProperty(name = "sb.dovetail.summarizeDocumentPrompt")
    private Optional<String> configSummarizeDocumentPrompt;

    @Inject
    @ConfigProperty(name = "sb.dovetail.contextFilterQuestion")
    private Optional<String> configContextFilterQuestion;

    @Inject
    @ConfigProperty(name = "sb.dovetail.contextFilterGreaterThan")
    private Optional<String> configContextFilterGreaterThan;

    @Inject
    @ConfigProperty(name = "sb.dovetail.contextFilterMinimumRating")
    private Optional<String> configContextFilterMinimumRating;

    @Inject
    @ConfigProperty(name = "sb.dovetail.contextFilterDefaultRating")
    private Optional<String> configContextFilterDefaultRating;

    @Inject
    @ConfigProperty(name = "sb.dovetail.preprocessorHooks", defaultValue = "")
    private Optional<String> configPreprocessorHooks;

    @Inject
    @ConfigProperty(name = "sb.dovetail.preinitializationHooks", defaultValue = "")
    private Optional<String> configPreinitializationHooks;

    @Inject
    @ConfigProperty(name = "sb.dovetail.postinferenceHooks", defaultValue = "")
    private Optional<String> configPostInferenceHooks;

    @Inject
    @ConfigProperty(name = "sb.dovetail.ttlSeconds")
    private Optional<String> configTtlSeconds;

    @Inject
    @ConfigProperty(name = "sb.dovetail.minimumContentLength", defaultValue = "0")
    private Optional<String> configMinimumContentLength;

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    public Encryptor getTextEncryptor() {
        return textEncryptor;
    }

    public ValidateString getValidateString() {
        return validateString;
    }

    public ToStringGenerator getToStringGenerator() {
        return toStringGenerator;
    }

    public DateParser getDateParser() {
        return dateParser;
    }

    public Logger getLogger() {
        return logger;
    }

    public Optional<String> getConfigApiKey() {
        return configApiKey;
    }

    public Optional<String> getConfigBaseUrl() {
        return configBaseUrl;
    }

    public Optional<String> getConfigDays() {
        return configDays;
    }

    public Optional<String> getConfigStartDate() {
        return configStartDate;
    }

    public Optional<String> getConfigEndDate() {
        return configEndDate;
    }

    public Optional<String> getConfigKeywords() {
        return configKeywords;
    }

    public Optional<String> getConfigKeywordWindow() {
        return configKeywordWindow;
    }

    public Optional<String> getConfigSummarizeDocument() {
        return configSummarizeDocument;
    }

    public Optional<String> getConfigSummarizeDocumentPrompt() {
        return configSummarizeDocumentPrompt;
    }

    public Optional<String> getConfigContextFilterQuestion() {
        return configContextFilterQuestion;
    }

    public Optional<String> getConfigContextFilterGreaterThan() {
        return configContextFilterGreaterThan;
    }

    public Optional<String> getConfigContextFilterMinimumRating() {
        return configContextFilterMinimumRating;
    }

    public Optional<String> getConfigContextFilterDefaultRating() {
        return configContextFilterDefaultRating;
    }

    public Optional<String> getConfigPreprocessorHooks() {
        return configPreprocessorHooks;
    }

    public Optional<String> getConfigPreinitializationHooks() {
        return configPreinitializationHooks;
    }

    public Optional<String> getConfigPostInferenceHooks() {
        return configPostInferenceHooks;
    }

    public Optional<String> getConfigTtlSeconds() {
        return configTtlSeconds;
    }

    public Optional<String> getConfigMinimumContentLength() {
        return configMinimumContentLength;
    }

    public class LocalArguments implements LocalConfigFilteredItem, LocalConfigFilteredParent, LocalConfigKeywordsEntity, LocalConfigSummarizer {
        private final List<ToolArgs> arguments;
        private final Map<String, String> context;

        public LocalArguments(final List<ToolArgs> arguments, final String prompt, final Map<String, String> context) {
            this.arguments = arguments;
            this.context = context;
        }

        @Override
        public String toString() {
            return getToStringGenerator().generateGetterConfig(this);
        }

        @SuppressWarnings("NullAway")
        public String getSecretApiKey() {
            final Try<String> token = Try.of(() -> getTextEncryptor().decrypt(context.get("dovetail_api_key")))
                    .recover(e -> context.get("dovetail_api_key"))
                    .mapTry(getValidateString()::throwIfBlank)
                    .recoverWith(e -> Try.of(() -> getTextEncryptor().decrypt(context.get(Dovetail.DOVETAIL_API_KEY_ARG)))
                            .recover(e2 -> context.get(Dovetail.DOVETAIL_API_KEY_ARG)))
                    .mapTry(getValidateString()::throwIfBlank)
                    .recoverWith(e -> Try.of(() -> getConfigApiKey().get()));

            if (token.isFailure() || StringUtils.isBlank(token.get())) {
                throw new InternalFailure("Failed to get Dovetail API key");
            }

            return token.get();
        }

        public String getDovetailBaseUrl() {
            return getArgsAccessor().getArgument(
                    getConfigBaseUrl()::get,
                    arguments,
                    context,
                    Dovetail.DOVETAIL_BASE_URL_ARG,
                    Dovetail.DOVETAIL_BASE_URL_ARG,
                    "https://dovetail.com").getSafeValue();
        }

        public int getDays() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigDays()::get,
                    arguments,
                    context,
                    CommonArguments.DAYS_ARG,
                    CommonArguments.DAYS_ARG,
                    "0").getSafeValue();

            return Try.of(() -> Integer.parseInt(stringValue))
                    .recover(throwable -> 0)
                    .map(i -> Math.max(0, i))
                    .get();
        }

        public String getStartDate() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigStartDate()::get,
                    arguments,
                    context,
                    CommonArguments.START_DATE,
                    CommonArguments.START_DATE,
                    "").getSafeValue();

            if (StringUtils.isNotBlank(stringValue)) {
                return Try.of(() -> getDateParser().parseDate(stringValue))
                        .map(date -> date.format(ISO_OFFSET_DATE_TIME))
                        .onFailure(ex -> getLogger().warning("Failed to parse start date: " + ExceptionUtils.getRootCauseMessage(ex)))
                        .get();
            }

            if (getDays() == 0) {
                return "";
            }

            return OffsetDateTime.now(ZoneId.systemDefault())
                    .truncatedTo(ChronoUnit.DAYS)
                    .minusDays(getDays())
                    .format(ISO_OFFSET_DATE_TIME);
        }

        public String getEndDate() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigEndDate()::get,
                    arguments,
                    context,
                    CommonArguments.END_DATE,
                    CommonArguments.END_DATE,
                    "").getSafeValue();

            if (StringUtils.isNotBlank(stringValue)) {
                return Try.of(() -> getDateParser().parseDate(stringValue))
                        .map(date -> date.format(ISO_OFFSET_DATE_TIME))
                        .onFailure(ex -> getLogger().warning("Failed to parse end date: " + ExceptionUtils.getRootCauseMessage(ex)))
                        .get();
            }

            if (getDays() == 0) {
                return "";
            }

            return OffsetDateTime.now(ZoneId.systemDefault())
                    .truncatedTo(ChronoUnit.DAYS)
                    .format(ISO_OFFSET_DATE_TIME);
        }

        @Override
        public List<String> getKeywords() {
            return getArgsAccessor().getArgumentList(
                            getConfigKeywords()::get,
                            arguments,
                            context,
                            CommonArguments.KEYWORDS_ARG,
                            CommonArguments.KEYWORDS_ARG,
                            "")
                    .stream()
                    .map(Argument::value)
                    .toList();
        }

        @Override
        public int getKeywordWindow() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigKeywordWindow()::get,
                    arguments,
                    context,
                    CommonArguments.KEYWORD_WINDOW_ARG,
                    CommonArguments.KEYWORD_WINDOW_ARG,
                    Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH + "");

            return NumberUtils.toInt(argument.getSafeValue(), Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH);
        }

        @Override
        public String getEntity() {
            return getArgsAccessor().getArgument(
                    null,
                    arguments,
                    context,
                    CommonArguments.ENTITY_NAME_CONTEXT_ARG,
                    CommonArguments.ENTITY_NAME_CONTEXT_ARG,
                    "").getSafeValue();
        }

        public boolean getSummarizeDocument() {
            final String value = getArgsAccessor().getArgument(
                    getConfigSummarizeDocument()::get,
                    arguments,
                    context,
                    CommonArguments.SUMMARIZE_DOCUMENT_ARG,
                    CommonArguments.SUMMARIZE_DOCUMENT_ARG,
                    "").getSafeValue();

            return BooleanUtils.toBoolean(value);
        }

        @Override
        public String getDocumentSummaryPrompt() {
            return getArgsAccessor()
                    .getArgument(
                            getConfigSummarizeDocumentPrompt()::get,
                            arguments,
                            context,
                            CommonArguments.SUMMARIZE_DOCUMENT_PROMPT_ARG,
                            CommonArguments.SUMMARIZE_DOCUMENT_PROMPT_ARG,
                            "Summarise the Dovetail data item in three paragraphs")
                    .getSafeValue();
        }

        @Override
        public String getContextFilterQuestion() {
            return getArgsAccessor().getArgument(
                            getConfigContextFilterQuestion()::get,
                            arguments,
                            context,
                            CommonArguments.CONTENT_RATING_QUESTION_ARG,
                            CommonArguments.CONTENT_RATING_QUESTION_ARG,
                            "")
                    .getSafeValue();
        }

        @Override
        public Integer getContextFilterMinimumRating() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigContextFilterMinimumRating()::get,
                    arguments,
                    context,
                    CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG,
                    CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG,
                    "0");

            return org.apache.commons.lang.math.NumberUtils.toInt(argument.getSafeValue(), 0);
        }

        @Override
        public Integer getDefaultRating() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigContextFilterDefaultRating()::get,
                    arguments,
                    context,
                    CommonArguments.DEFAULT_RATING_ARG,
                    CommonArguments.DEFAULT_RATING_ARG,
                    DEFAULT_RATING + "");

            return Math.max(0, NumberUtils.toInt(argument.getSafeValue(), DEFAULT_RATING));
        }

        @Override
        public boolean isContextFilterUpperLimit() {
            final String value = getArgsAccessor().getArgument(
                    getConfigContextFilterGreaterThan()::get,
                    arguments,
                    context,
                    CommonArguments.FILTER_GREATER_THAN_ARG,
                    CommonArguments.FILTER_GREATER_THAN_ARG,
                    "").getSafeValue();

            return BooleanUtils.toBoolean(value);
        }

        public String getPreprocessingHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPreprocessorHooks()::get,
                    arguments,
                    context,
                    CommonArguments.PREPROCESSOR_HOOKS_ARG,
                    CommonArguments.PREPROCESSOR_HOOKS_ARG,
                    "").getSafeValue();
        }

        public String getPreinitializationHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPreinitializationHooks()::get,
                    arguments,
                    context,
                    CommonArguments.PREINITIALIZATION_HOOKS_ARG,
                    CommonArguments.PREINITIALIZATION_HOOKS_ARG,
                    "").getSafeValue();
        }

        public String getPostInferenceHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPostInferenceHooks()::get,
                    arguments,
                    context,
                    CommonArguments.POSTINFERENCE_HOOKS_ARG,
                    CommonArguments.POSTINFERENCE_HOOKS_ARG,
                    "").getSafeValue();
        }

        public int getCacheTtl() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigTtlSeconds()::get,
                    arguments,
                    context,
                    Dovetail.TTL_SECONDS_ARG,
                    Dovetail.TTL_SECONDS_ARG,
                    DEFAULT_TTL_SECONDS + "");

            return Math.max(0, NumberUtils.toInt(argument.getSafeValue(), DEFAULT_TTL_SECONDS));
        }

        /**
         * The minimum number of characters a data item's content must have to be included.
         * Items whose document is shorter than this value are dropped before any further processing.
         * A value of 0 (the default) means no minimum is enforced.
         */
        public int getMinimumContentLength() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigMinimumContentLength()::get,
                    arguments,
                    context,
                    Dovetail.MINIMUM_CONTENT_LENGTH_ARG,
                    Dovetail.MINIMUM_CONTENT_LENGTH_ARG,
                    "0");

            return Math.max(0, NumberUtils.toInt(argument.getSafeValue(), 0));
        }
    }
}




