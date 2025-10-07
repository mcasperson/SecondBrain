package secondbrain.domain.tools.salesforce;

import com.google.common.collect.ImmutableList;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
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
import secondbrain.domain.exceptionhandling.ExceptionMapping;
import secondbrain.domain.exceptions.InsufficientContext;
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
import secondbrain.infrastructure.salesforce.SalesforceClient;
import secondbrain.infrastructure.salesforce.api.SalesforceTaskRecord;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;

/**
 * A tool that answers a query based on the emails associated with a Salesforce account.
 */
@ApplicationScoped
public class Salesforce implements Tool<SalesforceTaskRecord> {
    public static final String ENTITY_NAME_CONTEXT_ARG = "entityName";
    public static final String FILTER_QUESTION_ARG = "contentRatingQuestion";
    public static final String FILTER_MINIMUM_RATING_ARG = "contextFilterMinimumRating";
    public static final String DEFAULT_RATING_ARG = "ticketDefaultRating";
    public static final String SUMMARIZE_DOCUMENT_ARG = "summarizeDocument";
    public static final String SUMMARIZE_DOCUMENT_PROMPT_ARG = "summarizeDocumentPrompt";
    public static final String DOMAIN = "domain";
    public static final String KEYWORD_ARG = "keywords";
    public static final String KEYWORD_WINDOW_ARG = "keywordWindow";
    public static final String ACCOUNT_ID = "accountId";
    public static final String CLIENT_SECRET = "clientSecret";
    public static final String CLIENT_ID = "clientId";
    public static final String DAYS_ARG = "days";
    public static final String HOURS_ARG = "hours";
    public static final String START_PERIOD_ARG = "start";
    public static final String END_PERIOD_ARG = "end";
    public static final String PREPROCESSOR_HOOKS_CONTEXT_ARG = "preProcessorHooks";
    public static final String PREINITIALIZATION_HOOKS_CONTEXT_ARG = "preInitializationHooks";
    public static final String POSTINFERENCE_HOOKS_CONTEXT_ARG = "postInferenceHooks";

    private static final String INSTRUCTIONS = """
            You are a helpful assistant.
            You are given list of emails sent to people associated with a Salesforce account.
            You must assume the information required to answer the question is present in the emails.
            You must answer the question based on the emails provided.
            When the user asks a question indicating that they want to know about emails, you must generate the answer based on the emails.
            You will be penalized for answering that the emails can not be accessed.
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
    private ExceptionMapping exceptionMapping;

    @Inject
    private HooksContainer hooksContainer;

    @Inject
    private SalesforceClient salesforceClient;

    @Inject
    private SalesforceConfig config;

    @Inject
    private Logger logger;

    @Inject
    @Preferred
    private LlmClient llmClient;

    @Inject
    private ValidateString validateString;

    @Override
    public String getName() {
        return Salesforce.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Returns email communications with an account from Salesforce.";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return ImmutableList.of(new ToolArguments(
                "greeting",
                "The greeting to display",
                "World"));
    }

    @Override
    public List<RagDocumentContext<SalesforceTaskRecord>> getContext(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {
        logger.log(Level.INFO, "Getting context for " + getName());

        final SalesforceConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        // Get preinitialization hooks before ragdocs
        final List<RagDocumentContext<SalesforceTaskRecord>> preinitHooks = Seq.seq(hooksContainer.getMatchingPreProcessorHooks(parsedArgs.getPreinitializationHooks()))
                .foldLeft(List.of(), (docs, hook) -> hook.process(getName(), docs));

        final String startDate = StringUtils.isNotBlank(parsedArgs.getStartPeriod()) ? parsedArgs.getStartPeriod() : parsedArgs.getStartDate();
        final String endDate = StringUtils.isNotBlank(parsedArgs.getEndPeriod()) ? parsedArgs.getEndPeriod() : parsedArgs.getEndDate();

        final Try<List<RagDocumentContext<SalesforceTaskRecord>>> context = Try.of(() -> salesforceClient.getToken(parsedArgs.getClientId(), parsedArgs.getClientSecret()))
                .map(token -> salesforceClient.getTasks(token.accessToken(), parsedArgs.getAccountId(), "Email", startDate, endDate))
                .map(emails -> Stream.of(emails)
                        .map(email -> dataToRagDoc.getDocumentContext(email.updateDomain(parsedArgs.getDomain()), getName(), getContextLabel(), parsedArgs))
                        .filter(ragDoc -> !validateString.isEmpty(ragDoc, RagDocumentContext::document))
                        .toList());

        // Combine preinitialization hooks with ragDocs
        final List<RagDocumentContext<SalesforceTaskRecord>> combinedDocs = Stream.concat(
                preinitHooks.stream(),
                exceptionMapping.map(context).get().stream()
        ).toList();

        // Apply preprocessing hooks
        final List<RagDocumentContext<SalesforceTaskRecord>> filteredDocs = Seq.seq(hooksContainer.getMatchingPreProcessorHooks(parsedArgs.getPreprocessingHooks()))
                .foldLeft(combinedDocs, (docs, hook) -> hook.process(getName(), docs));

        if (filteredDocs.isEmpty()) {
            throw new InsufficientContext("No Salesforce emails found.");
        }

        // Only do the post processing after the hooks
        return filteredDocs.stream()
                // Get the metadata, which includes a rating against the filter question if present
                .map(ragDoc -> ragDoc.updateMetadata(ratingMetadata.getMetadata(getName(), environmentSettings, ragDoc, parsedArgs)))
                // Filter out any documents that don't meet the rating criteria
                .filter(ragDoc -> ratingFilter.contextMeetsRating(ragDoc, parsedArgs))
                .map(ragDoc -> ragDoc.addIntermediateResult(new IntermediateResult(ragDoc.document(), "Salesforce" + ragDoc.id() + ".txt")))
                .map(doc -> parsedArgs.getSummarizeDocument()
                        ? ragDocSummarizer.getDocumentSummary(getName(), getContextLabel(), "SalesforceEmail", doc, environmentSettings, parsedArgs)
                        : doc)
                .toList();
    }

    @Override
    public RagMultiDocumentContext<SalesforceTaskRecord> call(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        logger.log(Level.INFO, "Calling " + getName());

        final SalesforceConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        if (StringUtils.isBlank(parsedArgs.getAccountId())) {
            throw new InternalFailure("You must provide an account ID to query");
        }

        final List<RagDocumentContext<SalesforceTaskRecord>> contextList = getContext(environmentSettings, prompt, arguments);

        final Try<RagMultiDocumentContext<SalesforceTaskRecord>> result = Try.of(() -> contextList)
                .map(ragDoc -> new RagMultiDocumentContext<>(prompt, INSTRUCTIONS, ragDoc))
                .map(ragDoc -> llmClient.callWithCache(ragDoc, environmentSettings, getName()));

        final RagMultiDocumentContext<SalesforceTaskRecord> mappedResult = exceptionMapping.map(result).get();

        // Apply postinference hooks
        return Seq.seq(hooksContainer.getMatchingPostInferenceHooks(parsedArgs.getPostInferenceHooks()))
                .foldLeft(mappedResult, (docs, hook) -> hook.process(getName(), docs));
    }

    @Override
    public String getContextLabel() {
        return "Salesforce Email";
    }
}

@ApplicationScoped
class SalesforceConfig {
    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    @ConfigProperty(name = "sb.salesforce.domain")
    private Optional<String> configDomain;

    @Inject
    @ConfigProperty(name = "sb.salesforce.clientid")
    private Optional<String> configClientId;

    @Inject
    @ConfigProperty(name = "sb.salesforce.clientsecret")
    private Optional<String> configClientSecret;

    @Inject
    @ConfigProperty(name = "sb.salesforce.accountid")
    private Optional<String> configAccountId;

    @Inject
    @ConfigProperty(name = "sb.salesforce.days")
    private Optional<String> configSalesforceDays;

    @Inject
    @ConfigProperty(name = "sb.salesforce.hours")
    private Optional<String> configSalesforceHours;

    @Inject
    @ConfigProperty(name = "sb.salesforce.startperiod")
    private Optional<String> configSalesforceStartPeriod;

    @Inject
    @ConfigProperty(name = "sb.salesforce.endperiod")
    private Optional<String> configSalesforceEndPeriod;

    @Inject
    @ConfigProperty(name = "sb.salesforce.contextFilterQuestion")
    private Optional<String> configContextFilterQuestion;

    @Inject
    @ConfigProperty(name = "sb.salesforce.contextFilterMinimumRating")
    private Optional<String> configContextFilterMinimumRating;

    @Inject
    @ConfigProperty(name = "sb.salesforce.contextFilterDefaultRating")
    private Optional<String> configContextFilterDefaultRating;

    @Inject
    @ConfigProperty(name = "sb.salesforce.summarizedocument", defaultValue = "false")
    private Optional<String> configSummarizeDocument;

    @Inject
    @ConfigProperty(name = "sb.salesforce.summarizedocumentprompt")
    private Optional<String> configSummarizeDocumentPrompt;

    @Inject
    @ConfigProperty(name = "sb.salesforce.keywords")
    private Optional<String> configKeywords;

    @Inject
    @ConfigProperty(name = "sb.salesforce.keywordwindow")
    private Optional<String> configKeywordWindow;

    @Inject
    @ConfigProperty(name = "sb.salesforce.preprocessorHooks", defaultValue = "")
    private Optional<String> configPreprocessorHooks;

    @Inject
    @ConfigProperty(name = "sb.salesforce.preinitializationHooks", defaultValue = "")
    private Optional<String> configPreinitializationHooks;

    @Inject
    @ConfigProperty(name = "sb.salesforce.postinferenceHooks", defaultValue = "")
    private Optional<String> configPostInferenceHooks;

    public Optional<String> getConfigClientId() {
        return configClientId;
    }

    public Optional<String> getConfigClientSecret() {
        return configClientSecret;
    }

    public Optional<String> getConfigAccountId() {
        return configAccountId;
    }

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    public Optional<String> getConfigSalesforceDays() {
        return configSalesforceDays;
    }

    public Optional<String> getConfigSalesforceHours() {
        return configSalesforceHours;
    }

    public Optional<String> getConfigSalesforceStartPeriod() {
        return configSalesforceStartPeriod;
    }

    public Optional<String> getConfigSalesforceEndPeriod() {
        return configSalesforceEndPeriod;
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

    public Optional<String> getConfigSummarizeDocument() {
        return configSummarizeDocument;
    }

    public Optional<String> getConfigSummarizeDocumentPrompt() {
        return configSummarizeDocumentPrompt;
    }

    public Optional<String> getConfigDomain() {
        return configDomain;
    }

    public Optional<String> getConfigKeywords() {
        return configKeywords;
    }

    public Optional<String> getConfigKeywordWindow() {
        return configKeywordWindow;
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

    public class LocalArguments implements LocalConfigFilteredItem, LocalConfigFilteredParent, LocalConfigKeywordsEntity, LocalConfigSummarizer {
        private static final int DEFAULT_RATING = 10;

        private final List<ToolArgs> arguments;

        private final String prompt;

        private final Map<String, String> context;

        public LocalArguments(final List<ToolArgs> arguments, final String prompt, final Map<String, String> context) {
            this.arguments = arguments;
            this.prompt = prompt;
            this.context = context;
        }

        public String getAccountId() {
            return getArgsAccessor().getArgument(
                    getConfigAccountId()::get,
                    arguments,
                    context,
                    Salesforce.ACCOUNT_ID,
                    Salesforce.ACCOUNT_ID,
                    "").value();
        }

        public String getClientId() {
            return getArgsAccessor().getArgument(
                    getConfigClientId()::get,
                    arguments,
                    context,
                    Salesforce.CLIENT_ID,
                    Salesforce.CLIENT_ID,
                    "").value();
        }

        public String getClientSecret() {
            return getArgsAccessor().getArgument(
                    getConfigClientSecret()::get,
                    arguments,
                    context,
                    Salesforce.CLIENT_SECRET,
                    Salesforce.CLIENT_SECRET,
                    "").value();
        }

        private Argument getHoursArgument() {
            return getArgsAccessor().getArgument(
                    getConfigSalesforceHours()::get,
                    arguments,
                    context,
                    Salesforce.HOURS_ARG,
                    Salesforce.HOURS_ARG,
                    "0");
        }

        private Argument getDaysArgument() {
            return getArgsAccessor().getArgument(
                    getConfigSalesforceDays()::get,
                    arguments,
                    context,
                    Salesforce.DAYS_ARG,
                    Salesforce.DAYS_ARG,
                    "0");
        }

        public int getRawHours() {
            final String stringValue = getHoursArgument().value();

            return Try.of(() -> Integer.parseInt(stringValue))
                    .recover(throwable -> 0)
                    .map(i -> Math.max(0, i))
                    .get();
        }

        public int getRawDays() {
            final String stringValue = getDaysArgument().value();

            return Try.of(() -> Integer.parseInt(stringValue))
                    .recover(throwable -> 0)
                    .map(i -> Math.max(0, i))
                    .get();
        }

        public int getHours() {
            if (getHoursArgument().trusted() && getDaysArgument().trusted()) {
                return getRawHours();
            }

            return switchArguments(prompt, getRawHours(), getRawDays(), "hour", "day");
        }

        public int getDays() {
            if (getHoursArgument().trusted() && getDaysArgument().trusted()) {
                return getRawDays();
            }

            return switchArguments(prompt, getRawDays(), getRawHours(), "day", "hour");
        }

        public String getStartPeriod() {
            return getArgsAccessor().getArgument(
                            getConfigSalesforceStartPeriod()::get,
                            arguments,
                            context,
                            Salesforce.START_PERIOD_ARG,
                            Salesforce.START_PERIOD_ARG,
                            "")
                    .value();
        }

        public String getEndPeriod() {
            return getArgsAccessor().getArgument(
                            getConfigSalesforceEndPeriod()::get,
                            arguments,
                            context,
                            Salesforce.END_PERIOD_ARG,
                            Salesforce.END_PERIOD_ARG,
                            "")
                    .value();
        }

        public String getStartDate() {
            // Truncating to hours or days means the cache has a higher chance of being hit.
            final TemporalUnit truncatedTo = getHours() == 0
                    ? ChronoUnit.DAYS
                    : ChronoUnit.HOURS;

            return OffsetDateTime.now(ZoneId.of("UTC"))
                    .truncatedTo(truncatedTo)
                    // Assume one day if nothing was specified
                    .minusDays(getDays() + getHours() == 0 ? 1 : getDays())
                    .minusHours(getHours())
                    .format(ISO_LOCAL_DATE);
        }

        public String getEndDate() {
            return OffsetDateTime.now(ZoneId.of("UTC"))
                    .truncatedTo(ChronoUnit.DAYS)
                    .plusDays(1)
                    .format(ISO_LOCAL_DATE);
        }

        private int switchArguments(final String prompt, final int a, final int b, final String aPromptKeyword, final String bPromptKeyword) {
            final Locale locale = Locale.getDefault();

            // If the prompt did not mention the keyword for the first argument, assume that it was never mentioned, and return 0
            if (!prompt.toLowerCase(locale).contains(aPromptKeyword.toLowerCase(locale))) {
                return 0;
            }

            // If the prompt did mention the first argument, but did not mention the keyword for the second argument,
            // and the first argument is 0, assume the LLM switched things up, and return the second argument
            if (!prompt.toLowerCase(locale).contains(bPromptKeyword.toLowerCase(locale)) && a == 0) {
                return b;
            }

            // If both the first and second keywords were mentioned, we just have to trust the LLM
            return a;
        }

        public String getContextFilterQuestion() {
            return getArgsAccessor().getArgument(
                            getConfigContextFilterQuestion()::get,
                            arguments,
                            context,
                            Salesforce.FILTER_QUESTION_ARG,
                            Salesforce.FILTER_QUESTION_ARG,
                            "")
                    .value();
        }

        public Integer getContextFilterMinimumRating() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigContextFilterMinimumRating()::get,
                    arguments,
                    context,
                    Salesforce.FILTER_MINIMUM_RATING_ARG,
                    Salesforce.FILTER_MINIMUM_RATING_ARG,
                    "0");

            return org.apache.commons.lang.math.NumberUtils.toInt(argument.value(), 0);
        }

        public Integer getDefaultRating() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigContextFilterDefaultRating()::get,
                    arguments,
                    context,
                    Salesforce.DEFAULT_RATING_ARG,
                    Salesforce.DEFAULT_RATING_ARG,
                    DEFAULT_RATING + "");

            return Math.max(0, org.apache.commons.lang3.math.NumberUtils.toInt(argument.value(), DEFAULT_RATING));
        }

        public boolean getSummarizeDocument() {
            final String value = getArgsAccessor().getArgument(
                    getConfigSummarizeDocument()::get,
                    arguments,
                    context,
                    Salesforce.SUMMARIZE_DOCUMENT_ARG,
                    Salesforce.SUMMARIZE_DOCUMENT_ARG,
                    "").value();

            return BooleanUtils.toBoolean(value);
        }

        public String getDocumentSummaryPrompt() {
            return getArgsAccessor()
                    .getArgument(
                            getConfigSummarizeDocumentPrompt()::get,
                            arguments,
                            context,
                            Salesforce.SUMMARIZE_DOCUMENT_PROMPT_ARG,
                            Salesforce.SUMMARIZE_DOCUMENT_PROMPT_ARG,
                            "Summarise the document in three paragraphs")
                    .value();
        }

        public String getEntity() {
            return getArgsAccessor().getArgument(
                    null,
                    null,
                    context,
                    null,
                    Salesforce.ENTITY_NAME_CONTEXT_ARG,
                    "").value();
        }

        public String getDomain() {
            return getArgsAccessor()
                    .getArgument(
                            getConfigDomain()::get,
                            arguments,
                            context,
                            Salesforce.DOMAIN,
                            Salesforce.DOMAIN,
                            "")
                    .value();
        }

        public List<String> getKeywords() {
            return getArgsAccessor().getArgumentList(
                            getConfigKeywords()::get,
                            arguments,
                            context,
                            Salesforce.KEYWORD_ARG,
                            Salesforce.KEYWORD_ARG,
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
                    Salesforce.KEYWORD_WINDOW_ARG,
                    Salesforce.KEYWORD_WINDOW_ARG,
                    Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH + "");

            return NumberUtils.toInt(argument.value(), Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH);
        }

        public String getPreprocessingHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPreprocessorHooks()::get,
                    arguments,
                    context,
                    Salesforce.PREPROCESSOR_HOOKS_CONTEXT_ARG,
                    Salesforce.PREPROCESSOR_HOOKS_CONTEXT_ARG,
                    "").value();
        }

        public String getPreinitializationHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPreinitializationHooks()::get,
                    arguments,
                    context,
                    Salesforce.PREINITIALIZATION_HOOKS_CONTEXT_ARG,
                    Salesforce.PREINITIALIZATION_HOOKS_CONTEXT_ARG,
                    "").value();
        }

        public String getPostInferenceHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPostInferenceHooks()::get,
                    arguments,
                    context,
                    Salesforce.POSTINFERENCE_HOOKS_CONTEXT_ARG,
                    Salesforce.POSTINFERENCE_HOOKS_CONTEXT_ARG,
                    "").value();
        }
    }
}