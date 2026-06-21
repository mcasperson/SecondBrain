package secondbrain.domain.tools.planhat;

import io.smallrye.common.annotation.Identifier;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jooq.lambda.Seq;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.concurrency.SharedVirtualThreadExecutor;
import secondbrain.domain.config.*;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.converter.HtmlToText;
import secondbrain.domain.date.DateParser;
import secondbrain.domain.exceptionhandling.ExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.hooks.HooksContainer;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.limit.DocumentTrimmer;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.processing.DataToRagDoc;
import secondbrain.domain.processing.RagDocSummarizer;
import secondbrain.domain.processing.RatingFilter;
import secondbrain.domain.processing.RatingMetadata;
import secondbrain.domain.tooldefs.IntermediateResult;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.domain.tools.keyword.Keywords;
import secondbrain.domain.validate.ValidateString;
import secondbrain.domain.web.ClientConstructor;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.planhat.PlanHatClient;
import secondbrain.infrastructure.planhat.api.Conversation;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static com.pivovarit.collectors.ParallelCollectors.Batching.parallelToStream;

@ApplicationScoped
public class PlanHat implements Tool<Void> {
    public static final String COMPANY_ID_ARGS = "companyId";
    public static final String SEARCH_TTL_ARG = "searchTtl";
    public static final String PLANHAT_TTL_SECONDS_ARG = "ttlSeconds";
    private static final int PARALLEL_BATCH_SIZE = 10;

    private static final String INSTRUCTIONS = """
            You are a helpful assistant.
            You are given list of conversations and a question related to the conversations.
            You must assume the information required to answer the question is present in the conversations.
            You must answer the question based on the conversations provided.
            You will be tipped $1000 for answering the question directly from the conversation.
            When the user asks a question indicating that they want to know about conversation, you must generate the answer based on the conversation.
            You will be penalized for answering that the conversation can not be accessed.
            """.stripLeading();

    @Inject
    @ConfigProperty(name = "sb.planhat.publicappurl", defaultValue = "https://app.planhat.com")
    private String publicUrl;

    @Inject
    private RatingMetadata ratingMetadata;

    @Inject
    private RatingFilter ratingFilter;

    @Inject
    private DataToRagDoc dataToRagDoc;

    @Inject
    @Preferred
    private RagDocSummarizer ragDocSummarizer;

    @Inject
    private PlanHatConfig config;

    @Inject
    @Preferred
    private PlanHatClient planHatClient;

    @Inject
    @Preferred
    private LlmClient llmClient;

    @Inject
    private HtmlToText htmlToText;

    @Inject
    private Logger logger;

    @Inject
    private ExceptionMapping exceptionMapping;

    @Inject
    private HooksContainer hooksContainer;

    @Inject
    @Preferred
    private LocalStorage localStorage;

    @Inject
    private ClientConstructor clientConstructor;

    @Inject
    private SharedVirtualThreadExecutor sharedExecutor;

    @Inject
    private DocumentTrimmer documentTrimmer;

    @Override
    public String getName() {
        return PlanHat.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Queries PlanHat for customer information, activities, emails, and conversations";
    }

    @Override
    public int contextHashCode(final Map<String, String> environmentSettings, final List<String> prompts, final List<ToolArgs> arguments) {
        final PlanHatConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompts, environmentSettings);
        return 31 * parsedArgs.hashCode() + prompts.hashCode();
    }

    @Override
    public String getContextLabel() {
        return "PlanHat Activity";
    }

    private String getContextLabelWithDate(@Nullable final Conversation conversation) {
        final StringBuilder sb = new StringBuilder();

        sb.append(getContextLabel());

        if (conversation == null) {
            return sb.toString();
        }

        if (StringUtils.isNotBlank(conversation.getType())) {
            sb.append(" Type: ").append(conversation.getType()).append(" ");
        }

        if (StringUtils.isNotBlank(conversation.getDate())) {
            sb.append(" Date: ").append(conversation.getDate());
        }

        return sb.toString();
    }

    @Override
    public List<ToolArguments> getArguments() {
        return List.of(
                new ToolArguments(COMPANY_ID_ARGS, "The company ID to query", ""),
                new ToolArguments(CommonArguments.DAYS_ARG, "The optional number of days worth of conversations to return", "0"),
                new ToolArguments(CommonArguments.START_DATE, "The optional date to start retrieving conversations from", ""),
                new ToolArguments(CommonArguments.END_DATE, "The optional date to stop retrieving conversations at", ""),
                new ToolArguments(CommonArguments.KEYWORDS_ARG, "The optional keywords to limit the conversations to", ""),
                new ToolArguments(CommonArguments.KEYWORD_WINDOW_ARG, "The window size around any matching keywords", ""),
                new ToolArguments(CommonArguments.AUTO_GENERATE_KEYWORDS_ARG, "Set to true to automatically generate keywords from the prompt using the Keywords LLM tool", "false"),
                new ToolArguments(CommonArguments.SUMMARIZE_DOCUMENT_ARG, "Set to true to first summarize each conversation", "false"),
                new ToolArguments(CommonArguments.SUMMARIZE_DOCUMENT_PROMPT_ARG, "The prompt used to summarize the conversation", ""),
                new ToolArguments(CommonArguments.CONTENT_RATING_QUESTION_ARG, "The question used to determine the content rating of a conversation", ""),
                new ToolArguments(CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, "The minimum rating a conversation must have to be included in the context", "0"),
                new ToolArguments(CommonArguments.DEFAULT_RATING_ARG, "The default rating to assign to conversations when no rating can be determined", "10"),
                new ToolArguments(CommonArguments.FILTER_GREATER_THAN_ARG, "Set to true to filter out any conversations with a rating greater than the specified minimum rating", "false"),
                new ToolArguments(CommonArguments.PREINITIALIZATION_HOOKS_ARG, "The names of pre-initialization hooks to apply before collecting conversation data", ""),
                new ToolArguments(CommonArguments.PREPROCESSOR_HOOKS_ARG, "The names of pre-processor hooks to apply before processing the conversations", ""),
                new ToolArguments(CommonArguments.POSTINFERENCE_HOOKS_ARG, "The names of post-inference hooks to apply after the LLM has processed the conversations", ""),
                new ToolArguments(PLANHAT_TTL_SECONDS_ARG, "The number of seconds to cache the PlanHat conversation results", "86400"),
                new ToolArguments(SEARCH_TTL_ARG, "The time-to-live in milliseconds for the PlanHat search query cache", ""),
                new ToolArguments(CommonArguments.MINIMUM_CONTENT_LENGTH, "The minimum number of characters a conversation must have to be included (0 = no minimum)", "0")
        );
    }

    @Override
    public List<RagDocumentContext<Void>> getContext(final Map<String, String> environmentSettings, final List<String> prompts, final List<ToolArgs> arguments) {
        final String prompt = prompts.isEmpty() ? "" : prompts.getFirst();
        final PlanHatConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompts, environmentSettings);

        if (StringUtils.isBlank(parsedArgs.getCompany())) {
            logger.info("Skipping Planhat context retrieval because no company has been specified");
            return List.of();
        }

        // Early out if we haven't seen any items in the last month
        if (parsedArgs.isSkipEmptyInLastDuration()) {
            final boolean hasItems = Try.withResources(clientConstructor::getClient)
                    .of(client -> planHatClient.anyItemsInDuration(client, parsedArgs.getCompany(), parsedArgs.getUrl(), parsedArgs.getSecretToken(), ChronoUnit.YEARS, ChronoUnit.MONTHS))
                    .getOrElse(true);
            if (!hasItems) {
                logger.info("Skipping PlanHat context retrieval because skipEmptyInLastDuration is set and there are no PlanHat conversations in the specified duration");
                return List.of();
            }
        }

        final String cacheKey = parsedArgs.toString().hashCode() + "_" + prompt.hashCode();
        return Try.of(() -> localStorage.getOrPutGeneric(
                                getName(),
                                getName(),
                                Integer.toString(cacheKey.hashCode()),
                                parsedArgs.getCacheTtl(),
                                List.class,
                                RagDocumentContext.class,
                                Void.class,
                                () -> getContextPrivate(environmentSettings, prompt, arguments))
                        .result())
                .filter(Objects::nonNull)
                .onFailure(NoSuchElementException.class, ex -> logger.warning("Failed to get PlanHat context from cache: " + ExceptionUtils.getRootCauseMessage(ex)))
                .getOrElse(List::of);
    }

    private List<RagDocumentContext<Void>> getContextPrivate(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        final PlanHatConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, List.of(prompt), environmentSettings);

        logger.fine("Getting PlanHat context for " + getName() + " for company " + parsedArgs.getCompany());

        if (StringUtils.isBlank(parsedArgs.getCompany())) {
            throw new InternalFailure("You must provide a company ID to query");
        }

        // Get preinitialization hooks before ragdocs
        final List<RagDocumentContext<Conversation>> preinitHooks = Seq.seq(hooksContainer.getMatchingPreProcessorHooks(parsedArgs.getPreinitializationHooks()))
                .foldLeft(List.of(), (docs, hook) -> hook.process(getName(), docs));

        // We can process multiple planhat instances
        final List<Pair<String, String>> tokens = Stream.of(
                        Pair.of(parsedArgs.getUrl(), parsedArgs.getSecretToken()),
                        Pair.of(parsedArgs.getUrl2(), parsedArgs.getSecretToken2()))
                .filter(pair -> StringUtils.isNotBlank(pair.getRight()) && StringUtils.isNotBlank(pair.getLeft()))
                .toList();

        final List<Conversation> conversations = tokens
                .stream()
                .flatMap(pair -> Try.withResources(clientConstructor::getClient)
                        .of(client -> planHatClient.getConversations(
                                client,
                                parsedArgs.getCompany(),
                                pair.getLeft(),
                                pair.getRight(),
                                parsedArgs.getStartDate(),
                                parsedArgs.getEndDate(),
                                parsedArgs.getSearchTTL()))
                        // Don't let the failure of one instance affect the other
                        .onFailure(throwable -> logger.warning("Failed to get PlanHat conversations from " + pair.getLeft() + " with token ending in " + StringUtils.substring(pair.getRight(), -4) + ": " + ExceptionUtils.getRootCauseMessage(throwable)))
                        .recover(ex -> List.of())
                        .get()
                        .stream())
                .filter(c -> parsedArgs.getMinimumContentLength() > 0 || c.getSnippet().length() >= parsedArgs.getMinimumContentLength())
                .toList();

        final List<RagDocumentContext<Conversation>> ragDocs = conversations.stream()
                // We filter after the API call to improve cache hits
                .filter(conversation -> parsedArgs.getCompany().equals(conversation.getCompanyId()))
                .filter(conversation -> !"ticket".equals(conversation.getType()))
                .collect(parallelToStream(conversation -> {
                    final Conversation updated = conversation.updateDescriptionAndSnippet(
                            htmlToText.getText(conversation.getDescription()),
                            htmlToText.getText(conversation.getSnippet()));
                    return dataToRagDoc.getDocumentContext(updated.updateUrl(publicUrl), getName(), getContextLabelWithDate(updated), parsedArgs);
                }, sharedExecutor.getExecutor(), PARALLEL_BATCH_SIZE))
                .filter(ragDoc -> StringUtils.isNotBlank(ragDoc.document()))
                .toList();

        // Combine preinitialization hooks with ragDocs
        final List<RagDocumentContext<Conversation>> combinedDocs = Stream.concat(preinitHooks.stream(), ragDocs.stream()).toList();

        // Apply preprocessing hooks
        final List<RagDocumentContext<Void>> context = Seq.seq(hooksContainer.getMatchingPreProcessorHooks(parsedArgs.getPreprocessingHooks()))
                .foldLeft(combinedDocs, (docs, hook) -> hook.process(getName(), docs))
                .stream()
                .map(ragDoc -> ragDoc.updateDocument(documentTrimmer.trimDocumentToKeywords(ragDoc.document(), parsedArgs.getKeywords(), parsedArgs.getKeywordWindow())))
                .filter(ragDoc -> StringUtils.isNotBlank(ragDoc.document()))
                // Get the metadata and summarize in parallel (these are the expensive LLM calls)
                .collect(parallelToStream(ragDoc -> enrichAndSummarize(ragDoc, environmentSettings, parsedArgs), sharedExecutor.getExecutor(), PARALLEL_BATCH_SIZE))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();

        logger.info("Found " + context.size() + " PlanHat conversations");

        return context;
    }

    private <T> Optional<RagDocumentContext<Void>> enrichAndSummarize(
            final RagDocumentContext<T> ragDoc,
            final Map<String, String> environmentSettings,
            final PlanHatConfig.LocalArguments parsedArgs) {

        final var enriched = ratingMetadata.getMetadata(getName(), environmentSettings, ragDoc, parsedArgs)
                .map(results -> ragDoc
                        .addMetadata(results.getMetadata())
                        .addIntermediateResults(results.getIntermediateResults()))
                .orElse(ragDoc);

        // Filter out any documents that don't meet the rating criteria
        if (!ratingFilter.contextMeetsRating(enriched, parsedArgs)) {
            return Optional.empty();
        }

        final var withIntermediate = enriched.addIntermediateResult(
                new IntermediateResult(
                        enriched.document(),
                        "Data-PlanHat-" + enriched.id() + "-" + parsedArgs.getEntity() + ".txt"));

        final var summarized = parsedArgs.getSummarizeDocument()
                ? ragDocSummarizer.getDocumentSummary(
                getName(),
                getContextLabelWithDate(withIntermediate.source() instanceof Conversation c ? c : null),
                "PlanHat",
                withIntermediate,
                environmentSettings,
                parsedArgs)
                : withIntermediate;

        return Optional.of(summarized.convertToRagDocumentContextVoid());
    }

    @Override
    public RagMultiDocumentContext<Void> call(Map<String, String> environmentSettings, List<String> prompts, List<ToolArgs> arguments) {
        logger.fine("Calling " + getName());

        final List<RagDocumentContext<Void>> contextList = getContext(environmentSettings, prompts, arguments);

        final PlanHatConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompts, environmentSettings);

        if (StringUtils.isBlank(parsedArgs.getCompany())) {
            throw new InternalFailure("You must provide a company to query");
        }

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> contextList)
                .map(ragDoc -> new RagMultiDocumentContext<>(prompts, INSTRUCTIONS, ragDoc))
                .map(ragDoc -> llmClient.callWithCache(ragDoc, environmentSettings, getName()));

        final RagMultiDocumentContext<Void> mappedResult = exceptionMapping.map(result).get();

        // Apply postinference hooks
        return Seq.seq(hooksContainer.getMatchingPostInferenceHooks(parsedArgs.getPostInferenceHooks()))
                .foldLeft(mappedResult, (docs, hook) -> hook.process(getName(), docs));
    }
}

@ApplicationScoped
class PlanHatConfig {
    private static final String DEFAULT_TTL = (1000 * 60 * 60 * 24) + "";
    private static final int DEFAULT_RATING = 10;
    private static final int DEFAULT_TTL_SECONDS = 60 * 60 * 24; // 24 hours

    @Inject
    private ToStringGenerator toStringGenerator;

    @Inject
    @ConfigProperty(name = "sb.planhat.ttlSeconds")
    private Optional<String> configTtlSeconds;

    @Inject
    @ConfigProperty(name = "sb.planhat.skipEmptyInLastDuration", defaultValue = "")
    private Optional<String> configSkipEmptyInLastDuration;

    @Inject
    @ConfigProperty(name = "sb.planhat.company")
    private Optional<String> configCompany;

    @Inject
    @ConfigProperty(name = "sb.planhat.days")
    private Optional<String> configFrom;

    @Inject
    @ConfigProperty(name = "sb.planhat.startDate")
    private Optional<String> configStartDate;

    @Inject
    @ConfigProperty(name = "sb.planhat.endDate")
    private Optional<String> configEndDate;

    @Inject
    @ConfigProperty(name = "sb.planhat.accesstoken")
    private Optional<String> configToken;

    @Inject
    @ConfigProperty(name = "sb.planhat.url", defaultValue = "https://api-us4.planhat.com")
    private Optional<String> configUrl;

    @Inject
    @ConfigProperty(name = "sb.planhat.accesstoken2")
    private Optional<String> configToken2;

    @Inject
    @ConfigProperty(name = "sb.planhat.url2")
    private Optional<String> configUrl2;

    @Inject
    @ConfigProperty(name = "sb.planhat.searchttl")
    private Optional<String> configSearchTtl;

    @Inject
    private ValidateString validateString;

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private Keywords keywords;

    @Identifier("everything")
    @Inject
    private DateParser dateParser;

    @Inject
    @ConfigProperty(name = "sb.planhat.keywords")
    private Optional<String> configKeywords;

    @Inject
    @ConfigProperty(name = "sb.planhat.autogeneratekeywords")
    private Optional<String> configAutoGenerateKeywords;

    @Inject
    @ConfigProperty(name = "sb.planhat.keywordwindow")
    private Optional<String> configKeywordWindow;


    @Inject
    @ConfigProperty(name = "sb.planhat.summarizedocument", defaultValue = "false")
    private Optional<String> configSummarizeDocument;

    @Inject
    @ConfigProperty(name = "sb.planhat.summarizedocumentprompt")
    private Optional<String> configSummarizeDocumentPrompt;

    @Inject
    @ConfigProperty(name = "sb.planhat.contextFilterQuestion")
    private Optional<String> configContextFilterQuestion;

    @Inject
    @ConfigProperty(name = "sb.planhat.contextFilterMinimumRating")
    private Optional<String> configContextFilterMinimumRating;

    @Inject
    @ConfigProperty(name = "sb.planhat.contextFilterDefaultRating")
    private Optional<String> configContextFilterDefaultRating;

    @Inject
    @ConfigProperty(name = "sb.planhat.contextFilterGreaterThan")
    private Optional<String> configContextFilterGreaterThan;

    @Inject
    @ConfigProperty(name = "sb.planhat.preprocessorHooks", defaultValue = "")
    private Optional<String> configPreprocessorHooks;

    @Inject
    @ConfigProperty(name = "sb.planhat.preinitializationHooks", defaultValue = "")
    private Optional<String> configPreinitializationHooks;

    @Inject
    @ConfigProperty(name = "sb.planhat.postinferenceHooks", defaultValue = "")
    private Optional<String> configPostInferenceHooks;

    @Inject
    @ConfigProperty(name = "sb.planhat.minimumContentLength", defaultValue = "0")
    private Optional<String> configMinimumContentLength;

    public Optional<String> getConfigCompany() {
        return configCompany;
    }

    public Optional<String> getConfigFrom() {
        return configFrom;
    }

    public Optional<String> getConfigStartDate() {
        return configStartDate;
    }

    public Optional<String> getConfigEndDate() {
        return configEndDate;
    }

    public Optional<String> getConfigToken() {
        return configToken;
    }

    public Optional<String> getConfigToken2() {
        return configToken2;
    }

    public Optional<String> getConfigSearchTtl() {
        return configSearchTtl;
    }

    public ValidateString getValidateString() {
        return validateString;
    }

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    public Keywords getKeywordsTool() {
        return keywords;
    }

    public Optional<String> getConfigKeywords() {
        return configKeywords;
    }

    public Optional<String> getConfigAutoGenerateKeywords() {
        return configAutoGenerateKeywords;
    }

    public Optional<String> getConfigKeywordWindow() {
        return configKeywordWindow;
    }

    public Optional<String> getConfigUrl() {
        return configUrl;
    }

    public Optional<String> getConfigUrl2() {
        return configUrl2;
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

    public Optional<String> getConfigContextFilterMinimumRating() {
        return configContextFilterMinimumRating;
    }

    public Optional<String> getConfigContextFilterDefaultRating() {
        return configContextFilterDefaultRating;
    }

    public Optional<String> getConfigContextFilterGreaterThan() {
        return configContextFilterGreaterThan;
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

    public Optional<String> getConfigMinimumContentLength() {
        return configMinimumContentLength;
    }

    public Optional<String> getConfigTtlSeconds() {
        return configTtlSeconds;
    }

    public Optional<String> getConfigSkipEmptyInLastDuration() {
        return configSkipEmptyInLastDuration;
    }

    public ToStringGenerator getToStringGenerator() {
        return toStringGenerator;
    }

    public DateParser getDateParser() {
        return dateParser;
    }

    public class LocalArguments implements LocalSkipEmptyInLastDuration, LocalConfigFilteredItem, LocalConfigFilteredParent, LocalConfigSummarizer, LocalConfigKeywordsEntity {
        private final List<ToolArgs> arguments;

        private final List<String> prompts;

        private final Map<String, String> context;

        public LocalArguments(final List<ToolArgs> arguments, final List<String> prompts, final Map<String, String> context) {
            this.arguments = List.copyOf(arguments);
            this.prompts = List.copyOf(prompts);
            this.context = Map.copyOf(context);
        }

        @Override
        public String toString() {
            return getToStringGenerator().generateGetterConfig(this);
        }

        @Override
        public int hashCode() {
            return getToStringGenerator().generateHashGetterConfig(this);
        }

        public String getCompany() {
            return getArgsAccessor().getArgument(
                    getConfigCompany()::get,
                    arguments,
                    context,
                    PlanHat.COMPANY_ID_ARGS,
                    PlanHat.COMPANY_ID_ARGS,
                    "").getSafeValue();
        }

        public int getDays() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigFrom()::get,
                    arguments,
                    context,
                    CommonArguments.DAYS_ARG,
                    CommonArguments.DAYS_ARG,
                    "");

            return NumberUtils.toInt(argument.getSafeValue(), 1);
        }

        @Nullable
        public ZonedDateTime getStartDate() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigStartDate()::get,
                    arguments,
                    context,
                    CommonArguments.START_DATE,
                    CommonArguments.START_DATE,
                    "").getSafeValue();

            if (StringUtils.isNotBlank(stringValue)) {
                return getDateParser().parseDate(stringValue);
            }

            if (getDays() == 0) {
                return null;
            }

            return ZonedDateTime.now(ZoneOffset.UTC)
                    .truncatedTo(ChronoUnit.DAYS)
                    .minusDays(getDays());
        }

        @Nullable
        public ZonedDateTime getEndDate() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigEndDate()::get,
                    arguments,
                    context,
                    CommonArguments.END_DATE,
                    CommonArguments.END_DATE,
                    "").getSafeValue();

            if (StringUtils.isNotBlank(stringValue)) {
                return getDateParser().parseDate(stringValue);
            }

            if (getDays() == 0) {
                return null;
            }

            return ZonedDateTime.now(ZoneId.systemDefault())
                    // truncate to the day to increase the chances of getting a cache hit
                    .truncatedTo(ChronoUnit.DAYS);
        }


        public String getSecretToken() {
            return Try.of(getConfigToken()::get)
                    .mapTry(getValidateString()::throwIfBlank)
                    .recover(e -> context.get("planhat_token"))
                    .mapTry(getValidateString()::throwIfBlank)
                    .recover(e -> "")
                    .get();
        }

        public String getUrl() {
            return Try.of(getConfigUrl()::get)
                    .mapTry(getValidateString()::throwIfBlank)
                    .recover(e -> context.get("planhat_url"))
                    .mapTry(getValidateString()::throwIfBlank)
                    .recover(e -> "")
                    .get();
        }

        public String getSecretToken2() {
            return Try.of(getConfigToken2()::get)
                    .mapTry(getValidateString()::throwIfBlank)
                    .recover(e -> context.get("planhat_token2"))
                    .mapTry(getValidateString()::throwIfBlank)
                    .recover(e -> "")
                    .get();
        }

        public String getUrl2() {
            return Try.of(getConfigUrl2()::get)
                    .mapTry(getValidateString()::throwIfBlank)
                    .recover(e -> context.get("planhat_url2"))
                    .mapTry(getValidateString()::throwIfBlank)
                    .recover(e -> "")
                    .get();
        }

        public int getSearchTTL() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigSearchTtl()::get,
                    arguments,
                    context,
                    PlanHat.SEARCH_TTL_ARG,
                    PlanHat.SEARCH_TTL_ARG,
                    DEFAULT_TTL);

            return Math.max(0, NumberUtils.toInt(argument.getSafeValue(), Integer.parseInt(DEFAULT_TTL)));
        }

        @Override
        public List<String> getKeywords() {
            final List<String> keywords = getArgsAccessor().getArgumentList(
                            getConfigKeywords()::get,
                            arguments,
                            context,
                            CommonArguments.KEYWORDS_ARG,
                            CommonArguments.KEYWORDS_ARG,
                            "")
                    .stream()
                    .map(Argument::value)
                    .toList();

            if (getAutoGenerateKeywords()) {
                return CollectionUtils.collate(keywords, getKeywordsTool().getKeywords(Map.of(), Stream.concat(prompts.stream(), Stream.of(getContextFilterQuestion())).toList(), List.of()), false);
            }

            return keywords;
        }

        public boolean getAutoGenerateKeywords() {
            final String value = getArgsAccessor().getArgument(
                    getConfigAutoGenerateKeywords()::get,
                    arguments,
                    context,
                    CommonArguments.AUTO_GENERATE_KEYWORDS_ARG,
                    CommonArguments.AUTO_GENERATE_KEYWORDS_ARG,
                    "false").getSafeValue();

            return BooleanUtils.toBoolean(value);
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
                            "Summarise the document in three paragraphs")
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

            return Math.max(0, org.apache.commons.lang3.math.NumberUtils.toInt(argument.getSafeValue(), DEFAULT_RATING));
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
                    PlanHat.PLANHAT_TTL_SECONDS_ARG,
                    PlanHat.PLANHAT_TTL_SECONDS_ARG,
                    DEFAULT_TTL_SECONDS + "");

            return Math.max(0, org.apache.commons.lang3.math.NumberUtils.toInt(argument.getSafeValue(), DEFAULT_RATING));
        }

        public int getMinimumContentLength() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigMinimumContentLength()::get,
                    arguments,
                    context,
                    CommonArguments.MINIMUM_CONTENT_LENGTH,
                    CommonArguments.MINIMUM_CONTENT_LENGTH,
                    "0");

            return Math.max(0, NumberUtils.toInt(argument.getSafeValue(), 0));
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
    }
}
