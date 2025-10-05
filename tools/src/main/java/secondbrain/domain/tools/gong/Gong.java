package secondbrain.domain.tools.gong;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.config.LocalConfigFilteredItem;
import secondbrain.domain.config.LocalConfigKeywordsEntity;
import secondbrain.domain.config.LocalConfigSummarizer;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptionhandling.ExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.processing.DataToRagDoc;
import secondbrain.domain.processing.RagDocSummarizer;
import secondbrain.domain.processing.RatingMetadata;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.tools.gong.model.GongCallDetails;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.gong.GongClient;
import secondbrain.infrastructure.llm.LlmClient;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;

@ApplicationScoped
public class Gong implements Tool<GongCallDetails> {
    public static final String GONG_FILTER_QUESTION_ARG = "callRatingQuestion";
    public static final String GONG_FILTER_MINIMUM_RATING_ARG = "callFilterMinimumRating";
    public static final String GONG_DEFAULT_RATING_ARG = "defaultRating";
    public static final String DAYS_ARG = "days";
    public static final String COMPANY_ARG = "company";
    public static final String CALLID_ARG = "callId";
    public static final String GONG_KEYWORD_ARG = "keywords";
    public static final String GONG_KEYWORD_WINDOW_ARG = "keywordWindow";
    public static final String GONG_ENTITY_NAME_CONTEXT_ARG = "entityName";
    public static final String GONG_SUMMARIZE_TRANSCRIPT_ARG = "summarizeTranscript";
    public static final String GONG_SUMMARIZE_TRANSCRIPT_PROMPT_ARG = "summarizeTranscriptPrompt";
    private static final String INSTRUCTIONS = """
            You are a helpful assistant.
            You are given list of call transcripts from Gong.
            You must assume the information required to answer the question is present in the transcripts.
            You must answer the question based on the transcripts provided.
            You will be tipped $1000 for answering the question directly from the transcripts.
            When the user asks a question indicating that they want to know about transcripts, you must generate the answer based on the transcripts.
            You will be penalized for answering that the transcripts can not be accessed.
            """.stripLeading();

    @Inject
    private RatingMetadata ratingMetadata;

    @Inject
    private DataToRagDoc dataToRagDoc;

    @Inject
    private RagDocSummarizer ragDocSummarizer;

    @Inject
    private GongConfig config;

    @Inject
    @Preferred
    private GongClient gongClient;

    @Inject
    @Preferred
    private LlmClient llmClient;

    @Inject
    private ValidateString validateString;

    @Inject
    private Logger logger;

    @Inject
    private ExceptionMapping exceptionMapping;

    @Override
    public String getName() {
        return Gong.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Returns the transcripts of calls recorded in Gong";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return List.of();
    }

    @Override
    public List<RagDocumentContext<GongCallDetails>> getContext(
            final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        logger.log(Level.INFO, "Getting context for " + getName());

        final GongConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final List<GongCallDetails> calls = Try.of(() ->
                        gongClient.getCallsExtensive(
                                parsedArgs.getCompany(),
                                parsedArgs.getCallId(),
                                parsedArgs.getAccessKey(),
                                parsedArgs.getAccessSecretKey(),
                                parsedArgs.getStartDate(),
                                parsedArgs.getEndDate()))
                .map(e -> e.stream()
                        .map(gong -> new GongCallDetails(
                                gong.metaData().id(),
                                gong.metaData().url(),
                                gong.getSystemContext("Salesforce")
                                        .flatMap(c -> c.getObject("Name"))
                                        .map(f -> f.value().toString())
                                        .orElse("Unknown"),
                                gong.parties(),
                                gongClient.getCallTranscript(parsedArgs.getAccessKey(), parsedArgs.getAccessSecretKey(), gong)))
                        .toList())
                .onFailure(ex -> logger.severe("Failed to get Gong calls: " + ExceptionUtils.getRootCauseMessage(ex)))
                .get();

        return calls.stream()
                .map(call -> dataToRagDoc.getDocumentContext(call, getName(), getContextLabel(), parsedArgs))
                .filter(ragDoc -> !validateString.isEmpty(ragDoc, RagDocumentContext::document))
                // Get the metadata, which includes a rating against the filter question if present
                .map(ragDoc -> ragDoc.updateMetadata(ratingMetadata.getMetadata(getName(), environmentSettings, ragDoc, parsedArgs)))
                // Filter out any documents that don't meet the rating criteria
                .filter(ragDoc -> ratingMetadata.contextMeetsRating(ragDoc, parsedArgs))
                /*
                    Take the raw transcript and summarize them with individual calls to the LLM.
                    The transcripts are then combined into a single context.
                    This was necessary because the private LLMs didn't do a very good job of summarizing
                    raw tickets. The reality is that even LLMs with a context length of 128k can't process multiple
                    call transcripts.
                 */
                .map(ragDoc -> parsedArgs.getSummarizeTranscript()
                        ? ragDocSummarizer.getDocumentSummary(getName(), getContextLabel(), "Gong", ragDoc, environmentSettings, parsedArgs)
                        : ragDoc)
                .toList();
    }

    @Override
    public RagMultiDocumentContext<GongCallDetails> call(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        logger.log(Level.INFO, "Calling " + getName());

        final List<RagDocumentContext<GongCallDetails>> contextList = getContext(environmentSettings, prompt, arguments);

        final GongConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        if (StringUtils.isBlank(parsedArgs.getCompany()) && StringUtils.isBlank(parsedArgs.getCallId())) {
            throw new InternalFailure("You must provide a company or call ID to query");
        }

        final Try<RagMultiDocumentContext<GongCallDetails>> result = Try.of(() -> contextList)
                .map(ragDoc -> new RagMultiDocumentContext<>(prompt, INSTRUCTIONS, ragDoc))
                .map(ragDoc -> llmClient.callWithCache(
                        ragDoc,
                        environmentSettings,
                        getName()));

        return exceptionMapping.map(result).get();
    }

    @Override
    public String getContextLabel() {
        return "Gong Call";
    }
}

@ApplicationScoped
class GongConfig {
    private static final int DEFAULT_RATING = 10;

    @Inject
    @ConfigProperty(name = "sb.gong.accessKey")
    private Optional<String> configAccessKey;

    @Inject
    @ConfigProperty(name = "sb.gong.accessSecretKey")
    private Optional<String> configAccessSecretKey;

    @Inject
    @ConfigProperty(name = "sb.gong.days")
    private Optional<String> configDays;

    @Inject
    @ConfigProperty(name = "sb.gong.company")
    private Optional<String> configCompany;

    @Inject
    @ConfigProperty(name = "sb.gong.callId")
    private Optional<String> configCallId;

    @Inject
    @ConfigProperty(name = "sb.gong.keywords")
    private Optional<String> configKeywords;

    @Inject
    @ConfigProperty(name = "sb.gong.keywordwindow")
    private Optional<String> configKeywordWindow;

    @Inject
    @ConfigProperty(name = "sb.gong.summarizetranscript")
    private Optional<String> configSummarizeTranscript;

    @Inject
    @ConfigProperty(name = "sb.gong.summarizetranscriptprompt")
    private Optional<String> configSummarizeTranscriptPrompt;

    @Inject
    @ConfigProperty(name = "sb.gong.contextFilterQuestion")
    private Optional<String> configContextFilterQuestion;

    @Inject
    @ConfigProperty(name = "sb.gong.contextFilterMinimumRating")
    private Optional<String> configContextFilterMinimumRating;

    @Inject
    @ConfigProperty(name = "sb.gong.contextFilterDefaultRating")
    private Optional<String> configContextFilterDefaultRating;

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private Encryptor textEncryptor;

    @Inject
    private ValidateString validateString;

    public Optional<String> getConfigDays() {
        return configDays;
    }

    public Optional<String> getConfigAccessKey() {
        return configAccessKey;
    }

    public Optional<String> getConfigAccessSecretKey() {
        return configAccessSecretKey;
    }

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    public Encryptor getTextEncryptor() {
        return textEncryptor;
    }

    public ValidateString getValidateString() {
        return validateString;
    }

    public Optional<String> getConfigCompany() {
        return configCompany;
    }

    public Optional<String> getConfigKeywords() {
        return configKeywords;
    }

    public Optional<String> getConfigKeywordWindow() {
        return configKeywordWindow;
    }

    public Optional<String> getConfigCallId() {
        return configCallId;
    }

    public Optional<String> getConfigSummarizeTranscript() {
        return configSummarizeTranscript;
    }

    public Optional<String> getConfigSummarizeTranscriptPrompt() {
        return configSummarizeTranscriptPrompt;
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

    public class LocalArguments implements LocalConfigFilteredItem, LocalConfigKeywordsEntity, LocalConfigSummarizer {
        private final List<ToolArgs> arguments;

        private final String prompt;

        private final Map<String, String> context;

        public LocalArguments(final List<ToolArgs> arguments, final String prompt, final Map<String, String> context) {
            this.arguments = arguments;
            this.prompt = prompt;
            this.context = context;
        }

        public String getAccessKey() {
            // Try to decrypt the value, otherwise assume it is a plain text value, and finally
            // fall back to the value defined in the local configuration.
            final Try<String> token = Try.of(() -> getTextEncryptor().decrypt(context.get("gong_access_key")))
                    .recover(e -> context.get("gong_access_key"))
                    .mapTry(getValidateString()::throwIfEmpty)
                    .recoverWith(e -> Try.of(() -> getConfigAccessKey().get()));

            if (token.isFailure() || StringUtils.isBlank(token.get())) {
                throw new InternalFailure("Failed to get Gong access key");
            }

            return token.get();
        }

        public String getAccessSecretKey() {
            // Try to decrypt the value, otherwise assume it is a plain text value, and finally
            // fall back to the value defined in the local configuration.
            final Try<String> token = Try.of(() -> getTextEncryptor().decrypt(context.get("gong_access_secret_key")))
                    .recover(e -> context.get("gong_access_secret_key"))
                    .mapTry(getValidateString()::throwIfEmpty)
                    .recoverWith(e -> Try.of(() -> getConfigAccessSecretKey().get()));

            if (token.isFailure() || StringUtils.isBlank(token.get())) {
                throw new InternalFailure("Failed to get Gong access secret key");
            }

            return token.get();
        }

        public String getCompany() {
            return getArgsAccessor().getArgument(
                    getConfigCompany()::get,
                    arguments,
                    context,
                    Gong.COMPANY_ARG,
                    Gong.COMPANY_ARG,
                    "").value();
        }

        public String getCallId() {
            return getArgsAccessor().getArgument(
                    getConfigCallId()::get,
                    arguments,
                    context,
                    Gong.CALLID_ARG,
                    Gong.CALLID_ARG,
                    "").value();
        }

        public int getDays() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigDays()::get,
                    arguments,
                    context,
                    Gong.DAYS_ARG,
                    Gong.DAYS_ARG,
                    "0").value();

            return Try.of(() -> Integer.parseInt(stringValue))
                    .recover(throwable -> 0)
                    .map(i -> Math.max(0, i))
                    .get();
        }

        public String getStartDate() {
            if (getDays() == 0) {
                return null;
            }

            return OffsetDateTime.now(ZoneId.systemDefault())
                    // truncate to the day to increase the chances of getting a cache hit
                    .truncatedTo(ChronoUnit.DAYS)
                    // Assume one day if nothing was specified
                    .minusDays(getDays())
                    .format(ISO_OFFSET_DATE_TIME);
        }

        public String getEndDate() {
            if (getDays() == 0) {
                return null;
            }

            return OffsetDateTime.now(ZoneId.systemDefault())
                    // truncate to the day to increase the chances of getting a cache hit
                    .truncatedTo(ChronoUnit.DAYS)
                    .format(ISO_OFFSET_DATE_TIME);
        }

        public List<String> getKeywords() {
            return getArgsAccessor().getArgumentList(
                            getConfigKeywords()::get,
                            arguments,
                            context,
                            Gong.GONG_KEYWORD_ARG,
                            Gong.GONG_KEYWORD_ARG,
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
                    Gong.GONG_KEYWORD_WINDOW_ARG,
                    Gong.GONG_KEYWORD_WINDOW_ARG,
                    Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH + "");

            return NumberUtils.toInt(argument.value(), Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH);
        }

        public String getEntity() {
            return getArgsAccessor().getArgument(
                    null,
                    null,
                    context,
                    null,
                    Gong.GONG_ENTITY_NAME_CONTEXT_ARG,
                    "").value();
        }

        public boolean getSummarizeTranscript() {
            final String value = getArgsAccessor().getArgument(
                    getConfigSummarizeTranscript()::get,
                    arguments,
                    context,
                    Gong.GONG_SUMMARIZE_TRANSCRIPT_ARG,
                    Gong.GONG_SUMMARIZE_TRANSCRIPT_ARG,
                    "").value();

            return BooleanUtils.toBoolean(value);
        }

        public String getDocumentSummaryPrompt() {
            return getArgsAccessor()
                    .getArgument(
                            getConfigSummarizeTranscriptPrompt()::get,
                            arguments,
                            context,
                            Gong.GONG_SUMMARIZE_TRANSCRIPT_PROMPT_ARG,
                            Gong.GONG_SUMMARIZE_TRANSCRIPT_PROMPT_ARG,
                            "Summarise the Gong call transcript in three paragraphs")
                    .value();
        }

        public String getContextFilterQuestion() {
            return getArgsAccessor().getArgument(
                            getConfigContextFilterQuestion()::get,
                            arguments,
                            context,
                            Gong.GONG_FILTER_QUESTION_ARG,
                            Gong.GONG_FILTER_QUESTION_ARG,
                            "")
                    .value();
        }

        public Integer getContextFilterMinimumRating() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigContextFilterMinimumRating()::get,
                    arguments,
                    context,
                    Gong.GONG_FILTER_MINIMUM_RATING_ARG,
                    Gong.GONG_FILTER_MINIMUM_RATING_ARG,
                    "0");

            return org.apache.commons.lang.math.NumberUtils.toInt(argument.value(), 0);
        }

        public Integer getDefaultRating() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigContextFilterDefaultRating()::get,
                    arguments,
                    context,
                    Gong.GONG_DEFAULT_RATING_ARG,
                    Gong.GONG_DEFAULT_RATING_ARG,
                    DEFAULT_RATING + "");

            return Math.max(0, NumberUtils.toInt(argument.value(), DEFAULT_RATING));
        }
    }
}