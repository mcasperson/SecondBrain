package secondbrain.domain.tools.planhat;

import com.google.common.collect.ImmutableList;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jooq.lambda.Seq;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.config.LocalConfigFilteredItem;
import secondbrain.domain.config.LocalConfigFilteredParent;
import secondbrain.domain.config.LocalConfigKeywordsEntity;
import secondbrain.domain.config.LocalConfigSummarizer;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.converter.HtmlToText;
import secondbrain.domain.date.DateParser;
import secondbrain.domain.exceptionhandling.ExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.hooks.HooksContainer;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.processing.DataToRagDoc;
import secondbrain.domain.processing.RagDocSummarizer;
import secondbrain.domain.processing.RatingFilter;
import secondbrain.domain.processing.RatingMetadata;
import secondbrain.domain.tooldefs.IntermediateResult;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.planhat.PlanHatClient;
import secondbrain.infrastructure.planhat.api.Conversation;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

@ApplicationScoped
public class PlanHat implements Tool<Conversation> {
    public static final String PLANHAT_FILTER_QUESTION_ARG = "contentRatingQuestion";
    public static final String PLANHAT_FILTER_MINIMUM_RATING_ARG = "contextFilterMinimumRating";
    public static final String PLANHAT_ENSURE_GREATER_THAN_PROMPT_ARG = "filterGreaterThan";
    public static final String PLANHAT_DEFAULT_RATING_ARG = "ticketDefaultRating";
    public static final String DAYS_ARG = "days";
    public static final String SEARCH_TTL_ARG = "searchTtl";
    public static final String COMPANY_ID_ARGS = "companyId";
    public static final String PLANHAT_KEYWORD_ARG = "keywords";
    public static final String PLANHAT_KEYWORD_WINDOW_ARG = "keywordWindow";
    public static final String PLANHAT_ENTITY_NAME_CONTEXT_ARG = "entityName";
    public static final String PLANHAT_SUMMARIZE_DOCUMENT_ARG = "summarizeDocument";
    public static final String PLANHAT_SUMMARIZE_DOCUMENT_PROMPT_ARG = "summarizeDocumentPrompt";
    public static final String PREPROCESSOR_HOOKS_CONTEXT_ARG = "preProcessorHooks";
    public static final String PREINITIALIZATION_HOOKS_CONTEXT_ARG = "preInitializationHooks";
    public static final String POSTINFERENCE_HOOKS_CONTEXT_ARG = "postInferenceHooks";

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
    @ConfigProperty(name = "sb.planhat.appurl", defaultValue = "https://app-us4.planhat.com")
    private String url;

    @Inject
    private RatingMetadata ratingMetadata;

    @Inject
    private RatingFilter ratingFilter;

    @Inject
    private DataToRagDoc dataToRagDoc;

    @Inject
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
    private DateParser dateParser;

    @Inject
    private ValidateString validateString;

    @Inject
    private Logger logger;

    @Inject
    private ExceptionMapping exceptionMapping;

    @Inject
    private HooksContainer hooksContainer;

    @Override
    public String getName() {
        return PlanHat.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Queries PlanHat for customer information, activities, emails, and conversations";
    }

    @Override
    public String getContextLabel() {
        return "PlanHat Activity";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return ImmutableList.of(
                new ToolArguments(COMPANY_ID_ARGS, "The company ID to query", ""),
                new ToolArguments(PLANHAT_KEYWORD_WINDOW_ARG, "The window size around any matching keywords", ""),
                new ToolArguments(DAYS_ARG, "The number of days to query", ""),
                new ToolArguments(PLANHAT_KEYWORD_ARG, "The keywords to restrict the activities to", ""));
    }

    @Override
    public List<RagDocumentContext<Conversation>> getContext(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        logger.log(Level.INFO, "Getting context for " + getName());

        final PlanHatConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        if (StringUtils.isBlank(parsedArgs.getCompany())) {
            throw new InternalFailure("You must provide a company ID to query");
        }

        // Get preinitialization hooks before ragdocs
        final List<RagDocumentContext<Conversation>> preinitHooks = Seq.seq(hooksContainer.getMatchingPreProcessorHooks(parsedArgs.getPreinitializationHooks()))
                .foldLeft(List.of(), (docs, hook) -> hook.process(getName(), docs));

        // We can process multiple planhat instances
        final List<Pair<String, String>> tokens = Stream.of(
                        Pair.of(parsedArgs.getUrl(), parsedArgs.getToken()),
                        Pair.of(parsedArgs.getUrl2(), parsedArgs.getToken2()))
                .filter(pair -> StringUtils.isNotBlank(pair.getRight()) && StringUtils.isNotBlank(pair.getLeft()))
                .toList();

        final List<Conversation> conversations = tokens
                .stream()
                .flatMap(pair -> Try.withResources(ClientBuilder::newClient)
                        .of(client -> planHatClient.getConversations(
                                client,
                                "",
                                pair.getLeft(),
                                pair.getRight(),
                                parsedArgs.getStartDate(),
                                parsedArgs.getEndDate(),
                                parsedArgs.getSearchTTL()))
                        // Don't let the failure of one instance affect the other
                        .onFailure(throwable -> logger.warning("Failed to get conversations: " + ExceptionUtils.getRootCauseMessage(throwable)))
                        .recover(ex -> List.of())
                        .get()
                        .stream())
                .toList();

        final List<RagDocumentContext<Conversation>> ragDocs = conversations.stream()
                .filter(conversation -> parsedArgs.getCompany().equals(conversation.companyId()))
                .filter(conversation -> parsedArgs.getDays() == 0
                        || dateParser.parseDate(conversation.date()).isAfter(parsedArgs.getStartDate()))
                .filter(conversation -> !"ticket".equals(conversation.type()))
                .map(conversation -> conversation.updateDescriptionAndSnippet(
                        htmlToText.getText(conversation.description()),
                        htmlToText.getText(conversation.snippet()))
                )
                .map(conversation -> dataToRagDoc.getDocumentContext(conversation.updateUrl(url), getName(), getContextLabel(), parsedArgs))
                .filter(ragDoc -> !validateString.isBlank(ragDoc, RagDocumentContext::document))
                .toList();

        // Combine preinitialization hooks with ragDocs
        final List<RagDocumentContext<Conversation>> combinedDocs = Stream.concat(preinitHooks.stream(), ragDocs.stream()).toList();

        // Apply preprocessing hooks
        return Seq.seq(hooksContainer.getMatchingPreProcessorHooks(parsedArgs.getPreprocessingHooks()))
                .foldLeft(combinedDocs, (docs, hook) -> hook.process(getName(), docs))
                .stream()
                // Get the metadata, which includes a rating against the filter question if present
                .map(ragDoc -> ragDoc.addMetadata(ratingMetadata.getMetadata(getName(), environmentSettings, ragDoc, parsedArgs)))
                // Filter out any documents that don't meet the rating criteria
                .filter(ragDoc -> ratingFilter.contextMeetsRating(ragDoc, parsedArgs))
                .map(ragDoc -> ragDoc.addIntermediateResult(new IntermediateResult(ragDoc.document(), "PlanHat" + ragDoc.id() + ".txt")))
                .map(doc -> parsedArgs.getSummarizeDocument()
                        ? ragDocSummarizer.getDocumentSummary(getName(), getContextLabel(), "PlanHat", doc, environmentSettings, parsedArgs)
                        : doc)
                .toList();
    }

    @Override
    public RagMultiDocumentContext<Conversation> call(Map<String, String> environmentSettings, String prompt, List<ToolArgs> arguments) {
        logger.log(Level.INFO, "Calling " + getName());

        final List<RagDocumentContext<Conversation>> contextList = getContext(environmentSettings, prompt, arguments);

        final PlanHatConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        if (StringUtils.isBlank(parsedArgs.getCompany())) {
            throw new InternalFailure("You must provide a company to query");
        }

        final Try<RagMultiDocumentContext<Conversation>> result = Try.of(() -> contextList)
                .map(ragDoc -> new RagMultiDocumentContext<>(prompt, INSTRUCTIONS, ragDoc))
                .map(ragDoc -> llmClient.callWithCache(ragDoc, environmentSettings, getName()));

        final RagMultiDocumentContext<Conversation> mappedResult = exceptionMapping.map(result).get();

        // Apply postinference hooks
        return Seq.seq(hooksContainer.getMatchingPostInferenceHooks(parsedArgs.getPostInferenceHooks()))
                .foldLeft(mappedResult, (docs, hook) -> hook.process(getName(), docs));
    }
}

@ApplicationScoped
class PlanHatConfig {
    private static final String DEFAULT_TTL = (1000 * 60 * 60 * 24) + "";
    private static final int DEFAULT_RATING = 10;

    @Inject
    @ConfigProperty(name = "sb.planhat.company")
    private Optional<String> configCompany;

    @Inject
    @ConfigProperty(name = "sb.planhat.days")
    private Optional<String> configFrom;

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
    @ConfigProperty(name = "sb.planhat.url2", defaultValue = "https://api.planhat.com")
    private Optional<String> configUrl2;

    @Inject
    @ConfigProperty(name = "sb.planhat.searchttl")
    private Optional<String> configSearchTtl;

    @Inject
    private ValidateString validateString;

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    @ConfigProperty(name = "sb.planhat.keywords")
    private Optional<String> configKeywords;

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

    public Optional<String> getConfigCompany() {
        return configCompany;
    }

    public Optional<String> getConfigFrom() {
        return configFrom;
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

    public Optional<String> getConfigKeywords() {
        return configKeywords;
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

    public class LocalArguments implements LocalConfigFilteredItem, LocalConfigFilteredParent, LocalConfigSummarizer, LocalConfigKeywordsEntity {
        private final List<ToolArgs> arguments;

        private final String prompt;

        private final Map<String, String> context;

        public LocalArguments(final List<ToolArgs> arguments, final String prompt, final Map<String, String> context) {
            this.arguments = arguments;
            this.prompt = prompt;
            this.context = context;
        }

        public String getCompany() {
            return getArgsAccessor().getArgument(
                    getConfigCompany()::get,
                    arguments,
                    context,
                    PlanHat.COMPANY_ID_ARGS,
                    PlanHat.COMPANY_ID_ARGS,
                    "").value();
        }

        public int getDays() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigFrom()::get,
                    arguments,
                    context,
                    PlanHat.DAYS_ARG,
                    PlanHat.DAYS_ARG,
                    "");

            return NumberUtils.toInt(argument.value(), 1);
        }

        public ZonedDateTime getStartDate() {
            if (getDays() == 0) {
                return null;
            }

            return ZonedDateTime.now(ZoneOffset.UTC)
                    .truncatedTo(ChronoUnit.DAYS)
                    .minusDays(getDays());
        }

        public ZonedDateTime getEndDate() {
            if (getDays() == 0) {
                return null;
            }

            return ZonedDateTime.now(ZoneId.systemDefault())
                    // truncate to the day to increase the chances of getting a cache hit
                    .truncatedTo(ChronoUnit.DAYS);
        }


        public String getToken() {
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

        public String getToken2() {
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

            return Try.of(argument::value)
                    .map(i -> Math.max(0, Integer.parseInt(i)))
                    .get();
        }

        public List<String> getKeywords() {
            return getArgsAccessor().getArgumentList(
                            getConfigKeywords()::get,
                            arguments,
                            context,
                            PlanHat.PLANHAT_KEYWORD_ARG,
                            PlanHat.PLANHAT_KEYWORD_ARG,
                            "")
                    .stream()
                    .map(Argument::value)
                    .toList();
        }

        public int getKeywordWindow() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigKeywordWindow()::get,
                    arguments,
                    context,
                    PlanHat.PLANHAT_KEYWORD_WINDOW_ARG,
                    PlanHat.PLANHAT_KEYWORD_WINDOW_ARG,
                    Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH + "");

            return NumberUtils.toInt(argument.value(), Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH);
        }

        public String getEntity() {
            return getArgsAccessor().getArgument(
                    null,
                    null,
                    context,
                    null,
                    PlanHat.PLANHAT_ENTITY_NAME_CONTEXT_ARG,
                    "").value();
        }

        public boolean getSummarizeDocument() {
            final String value = getArgsAccessor().getArgument(
                    getConfigSummarizeDocument()::get,
                    arguments,
                    context,
                    PlanHat.PLANHAT_SUMMARIZE_DOCUMENT_ARG,
                    PlanHat.PLANHAT_SUMMARIZE_DOCUMENT_ARG,
                    "").value();

            return BooleanUtils.toBoolean(value);
        }

        public String getDocumentSummaryPrompt() {
            return getArgsAccessor()
                    .getArgument(
                            getConfigSummarizeDocumentPrompt()::get,
                            arguments,
                            context,
                            PlanHat.PLANHAT_SUMMARIZE_DOCUMENT_PROMPT_ARG,
                            PlanHat.PLANHAT_SUMMARIZE_DOCUMENT_PROMPT_ARG,
                            "Summarise the document in three paragraphs")
                    .value();
        }

        public String getContextFilterQuestion() {
            return getArgsAccessor().getArgument(
                            getConfigContextFilterQuestion()::get,
                            arguments,
                            context,
                            PlanHat.PLANHAT_FILTER_QUESTION_ARG,
                            PlanHat.PLANHAT_FILTER_QUESTION_ARG,
                            "")
                    .value();
        }

        public Integer getContextFilterMinimumRating() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigContextFilterMinimumRating()::get,
                    arguments,
                    context,
                    PlanHat.PLANHAT_FILTER_MINIMUM_RATING_ARG,
                    PlanHat.PLANHAT_FILTER_MINIMUM_RATING_ARG,
                    "0");

            return org.apache.commons.lang.math.NumberUtils.toInt(argument.value(), 0);
        }

        public Integer getDefaultRating() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigContextFilterDefaultRating()::get,
                    arguments,
                    context,
                    PlanHat.PLANHAT_DEFAULT_RATING_ARG,
                    PlanHat.PLANHAT_DEFAULT_RATING_ARG,
                    DEFAULT_RATING + "");

            return Math.max(0, org.apache.commons.lang3.math.NumberUtils.toInt(argument.value(), DEFAULT_RATING));
        }

        public boolean isContextFilterUpperLimit() {
            final String value = getArgsAccessor().getArgument(
                    getConfigContextFilterGreaterThan()::get,
                    arguments,
                    context,
                    PlanHat.PLANHAT_ENSURE_GREATER_THAN_PROMPT_ARG,
                    PlanHat.PLANHAT_ENSURE_GREATER_THAN_PROMPT_ARG,
                    "").value();

            return BooleanUtils.toBoolean(value);
        }

        public String getPreprocessingHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPreprocessorHooks()::get,
                    arguments,
                    context,
                    PlanHat.PREPROCESSOR_HOOKS_CONTEXT_ARG,
                    PlanHat.PREPROCESSOR_HOOKS_CONTEXT_ARG,
                    "").value();
        }

        public String getPreinitializationHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPreinitializationHooks()::get,
                    arguments,
                    context,
                    PlanHat.PREINITIALIZATION_HOOKS_CONTEXT_ARG,
                    PlanHat.PREINITIALIZATION_HOOKS_CONTEXT_ARG,
                    "").value();
        }

        public String getPostInferenceHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPostInferenceHooks()::get,
                    arguments,
                    context,
                    PlanHat.POSTINFERENCE_HOOKS_CONTEXT_ARG,
                    PlanHat.POSTINFERENCE_HOOKS_CONTEXT_ARG,
                    "").value();
        }
    }
}
