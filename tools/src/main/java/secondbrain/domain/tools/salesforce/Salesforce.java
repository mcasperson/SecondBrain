package secondbrain.domain.tools.salesforce;

import com.google.common.collect.ImmutableList;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.context.*;
import secondbrain.domain.exceptionhandling.ExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.tooldefs.*;
import secondbrain.domain.tools.rating.RatingTool;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.salesforce.SalesforceClient;
import secondbrain.infrastructure.salesforce.api.SalesforceTaskRecord;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;

/**
 * A tool that answers a query based on the emails associated with a Salesforce account.
 */
@ApplicationScoped
public class Salesforce implements Tool<SalesforceTaskRecord> {
    public static final String FILTER_RATING_META = "FilterRating";
    public static final String ENTITY_NAME_CONTEXT_ARG = "entityName";
    public static final String FILTER_QUESTION_ARG = "contentRatingQuestion";
    public static final String FILTER_MINIMUM_RATING_ARG = "contextFilterMinimumRating";
    public static final String DEFAULT_RATING_ARG = "ticketDefaultRating";
    public static final String SUMMARIZE_DOCUMENT_ARG = "summarizeDocument";
    public static final String SUMMARIZE_DOCUMENT_PROMPT_ARG = "summarizeDocumentPrompt";
    public static final String ACCOUNT_ID = "accountId";
    public static final String CLIENT_SECRET = "clientSecret";
    public static final String CLIENT_ID = "clientId";
    public static final String DAYS_ARG = "days";
    public static final String HOURS_ARG = "hours";
    public static final String START_PERIOD_ARG = "start";
    public static final String END_PERIOD_ARG = "end";

    private static final String INSTRUCTIONS = """
            You are a helpful assistant.
            You are given list of emails sent to people associated with a Salesforce account.
            You must assume the information required to answer the question is present in the emails.
            You must answer the question based on the emails provided.
            When the user asks a question indicating that they want to know about emails, you must generate the answer based on the emails.
            You will be penalized for answering that the emails can not be accessed.
            """.stripLeading();

    @Inject
    @ConfigProperty(name = "sb.salesforce.domain")
    private Optional<String> domain;

    @Inject
    private ExceptionMapping exceptionMapping;

    @Inject
    private SalesforceClient salesforceClient;

    @Inject
    private SalesforceConfig config;

    @Inject
    private Logger logger;

    @Inject
    private SentenceSplitter sentenceSplitter;

    @Inject
    private SentenceVectorizer sentenceVectorizer;

    @Inject
    private RatingTool ratingTool;

    @Inject
    @Preferred
    private LlmClient llmClient;

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

        final String startDate = StringUtils.isNotBlank(parsedArgs.getStartPeriod()) ? parsedArgs.getStartPeriod() : parsedArgs.getStartDate();
        final String endDate = StringUtils.isNotBlank(parsedArgs.getEndPeriod()) ? parsedArgs.getEndPeriod() : parsedArgs.getEndDate();

        final Try<List<RagDocumentContext<SalesforceTaskRecord>>> context = Try.of(() -> salesforceClient.getToken(parsedArgs.getClientId(), parsedArgs.getClientSecret()))
                .map(token -> salesforceClient.getTasks(token.accessToken(), parsedArgs.getAccountId(), "Email", parsedArgs.getStartDate(), parsedArgs.getEndDate()))
                .map(emails -> Stream.of(emails)
                        .map(email -> getDocumentContext(email, parsedArgs))
                        // Get the metadata, which includes a rating against the filter question if present
                        .map(ragDoc -> ragDoc.updateMetadata(getMetadata(environmentSettings, ragDoc, parsedArgs)))
                        // Filter out any documents that don't meet the rating criteria
                        .filter(ragDoc -> contextMeetsRating(ragDoc, parsedArgs))
                        .map(ragDoc -> ragDoc.addIntermediateResult(new IntermediateResult(ragDoc.document(), "Salesforce" + ragDoc.id() + ".txt")))
                        .map(doc -> parsedArgs.getSummarizeDocument()
                                ? getDocumentSummary(doc, environmentSettings, parsedArgs)
                                : doc)
                        .toList());

        return exceptionMapping.map(context).get();
    }

    @Override
    public RagMultiDocumentContext<SalesforceTaskRecord> call(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        logger.log(Level.INFO, "Calling " + getName());

        final List<RagDocumentContext<SalesforceTaskRecord>> contextList = getContext(environmentSettings, prompt, arguments);

        final SalesforceConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        if (StringUtils.isBlank(parsedArgs.getAccountId())) {
            throw new InternalFailure("You must provide an account ID to query");
        }

        final Try<RagMultiDocumentContext<SalesforceTaskRecord>> result = Try.of(() -> contextList)
                .map(ragDoc -> new RagMultiDocumentContext<>(prompt, INSTRUCTIONS, ragDoc))
                .map(ragDoc -> llmClient.callWithCache(ragDoc, environmentSettings, getName()));

        return exceptionMapping.map(result).get();
    }

    @Override
    public String getContextLabel() {
        return "Email";
    }

    private RagDocumentContext<SalesforceTaskRecord> getDocumentContext(final SalesforceTaskRecord task, final SalesforceConfig.LocalArguments parsedArgs) {
        return Try.of(() -> sentenceSplitter.splitDocument(task.getEmailText(), 10))
                .map(sentences -> new RagDocumentContext<SalesforceTaskRecord>(
                        getName(),
                        getContextLabel(),
                        task.getEmailText(),
                        sentenceVectorizer.vectorize(sentences, parsedArgs.getEntity()),
                        task.id(),
                        task,
                        "[Salesforce Task " + task.id() + "](https://" + domain.orElse("fixme") + ".my.salesforce.com)"))
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                .get();
    }

    private MetaObjectResults getMetadata(
            final Map<String, String> environmentSettings,
            final RagDocumentContext<SalesforceTaskRecord> activity,
            final SalesforceConfig.LocalArguments parsedArgs) {

        final List<MetaObjectResult> metadata = new ArrayList<>();

        // build the environment settings
        final EnvironmentSettings envSettings = new HashMapEnvironmentSettings(environmentSettings)
                .add(RatingTool.RATING_DOCUMENT_CONTEXT_ARG, activity.document())
                .addToolCall(getName() + "[" + activity.id() + "]");

        if (StringUtils.isNotBlank(parsedArgs.getContextFilterQuestion())) {
            final int filterRating = Try.of(() -> ratingTool.call(envSettings, parsedArgs.getContextFilterQuestion(), List.of()).getResponse())
                    .map(rating -> Integer.parseInt(rating.trim()))
                    .onFailure(e -> logger.warning("Failed to get Salesforce rating for email " + activity.id() + ": " + ExceptionUtils.getRootCauseMessage(e)))
                    // Ratings are provided on a best effort basis, so we ignore any failures
                    .recover(ex -> parsedArgs.getDefaultRating())
                    .get();

            metadata.add(new MetaObjectResult(FILTER_RATING_META, filterRating));
        }

        return new MetaObjectResults(
                metadata,
                "Gong-" + activity.id() + ".json",
                activity.id());
    }

    private boolean contextMeetsRating(
            final RagDocumentContext<SalesforceTaskRecord> call,
            final SalesforceConfig.LocalArguments parsedArgs) {
        // If there was no filter question, then return the whole list
        if (StringUtils.isBlank(parsedArgs.getContextFilterQuestion())) {
            return true;
        }

        return Objects.requireNonNullElse(call.metadata(), new MetaObjectResults())
                .getIntValueByName(FILTER_RATING_META, parsedArgs.getDefaultRating())
                >= parsedArgs.getContextFilterMinimumRating();
    }

    private RagDocumentContext<SalesforceTaskRecord> getDocumentSummary(
            final RagDocumentContext<SalesforceTaskRecord> ragDoc,
            final Map<String, String> environmentSettings,
            final SalesforceConfig.LocalArguments parsedArgs) {
        final RagDocumentContext<String> context = new RagDocumentContext<>(
                getName(),
                getContextLabel(),
                ragDoc.document(),
                List.of()
        );

        final String response = llmClient.callWithCache(
                new RagMultiDocumentContext<>(
                        parsedArgs.getDocumentSummaryPrompt(),
                        "You are a helpful agent",
                        List.of(context)),
                environmentSettings,
                getName()
        ).getResponse();

        return ragDoc.updateDocument(response)
                .addIntermediateResult(new IntermediateResult(
                        "Prompt: " + parsedArgs.getDocumentSummaryPrompt() + "\n\n" + response,
                        "Salesforce" + ragDoc.id() + "-" + DigestUtils.sha256Hex(parsedArgs.getDocumentSummaryPrompt()) + ".txt"));
    }
}

@ApplicationScoped
class SalesforceConfig {
    @Inject
    private ArgsAccessor argsAccessor;

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

    public class LocalArguments {
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

        public int getDefaultRating() {
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
    }
}