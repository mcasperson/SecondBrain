package secondbrain.domain.tools.gong;

import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.context.SentenceSplitter;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.exceptions.FailedOllama;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.limit.DocumentTrimmer;
import secondbrain.domain.limit.TrimResult;
import secondbrain.domain.tooldefs.*;
import secondbrain.domain.tools.gong.model.GongCallDetails;
import secondbrain.domain.tools.rating.RatingTool;
import secondbrain.domain.tools.zendesk.ZenDeskIndividualTicket;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.gong.GongClient;
import secondbrain.infrastructure.llm.LlmClient;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Predicates.instanceOf;
import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;

@ApplicationScoped
public class Gong implements Tool<GongCallDetails> {
    public static final String GONG_FILTER_RATING_META = "FilterRating";
    public static final String GONG_FILTER_QUESTION_ARG = "callRatingQuestion";
    public static final String GONG_FILTER_MINIMUM_RATING_ARG = "callFilterMinimumRating";
    public static final String DAYS_ARG = "days";
    public static final String COMPANY_ARG = "company";
    public static final String CALLID_ARG = "callId";
    public static final String GONG_KEYWORD_ARG = "keywords";
    public static final String GONG_KEYWORD_WINDOW_ARG = "keywordWindow";
    public static final String GONG_ENTITY_NAME_CONTEXT_ARG = "entityName";
    public static final String GONG_SUMMARIZE_TRANSCRIPT_ARG = "summarizeTranscript";
    public static final String GONG_SUMMARIZE_TRANSCRIPT_PROMPT_ARG = "summarizeTranscriptPrompt";
    private static final long API_CALL_TIMEOUT_SECONDS = 60 * 10; // 10 minutes
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
    private GongConfig config;

    @Inject
    @Preferred
    private GongClient gongClient;

    @Inject
    private SentenceSplitter sentenceSplitter;

    @Inject
    private SentenceVectorizer sentenceVectorizer;

    @Inject
    private DocumentTrimmer documentTrimmer;

    @Inject
    @Preferred
    private LlmClient llmClient;

    @Inject
    private ValidateString validateString;

    @Inject
    private Logger logger;

    @Inject
    private RatingTool ratingTool;

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

        final List<Pair<GongCallDetails, String>> calls = Try.of(() ->
                        gongClient.getCallsExtensive(
                                parsedArgs.getCompany(),
                                parsedArgs.getCallId(),
                                parsedArgs.getAccessKey(),
                                parsedArgs.getAccessSecretKey(),
                                parsedArgs.getStartDate(),
                                parsedArgs.getEndDate()))
                .map(c -> c.stream()
                        .map(call -> Pair.of(
                                call,
                                gongClient.getCallTranscript(parsedArgs.getAccessKey(), parsedArgs.getAccessSecretKey(), call)))
                        .toList())
                .onFailure(ex -> logger.severe("Failed to get Gong calls: " + ExceptionUtils.getRootCauseMessage(ex)))
                .get();

        return calls.stream()
                .map(pair -> getDocumentContext(pair.getLeft(), pair.getRight(), parsedArgs))
                .filter(ragDoc -> !validateString.isEmpty(ragDoc, RagDocumentContext::document))
                // Get the metadata, which includes a rating against the filter question if present
                .map(ragDoc -> ragDoc.updateMetadata(getMetadata(ragDoc, parsedArgs)))
                // Filter out any documents that don't meet the rating criteria
                .filter(ragDoc -> contextMeetsRating(ragDoc, parsedArgs))
                /*
                    Take the raw transcript and summarize them with individual calls to the LLM.
                    The transcripts are then combined into a single context.
                    This was necessary because the private LLMs didn't do a very good job of summarizing
                    raw tickets. The reality is that even LLMs with a context length of 128k can't process multiple
                    call transcripts.
                 */
                .map(ragDoc -> parsedArgs.getSummarizeTranscript() ? getCallSummary(ragDoc, environmentSettings, parsedArgs) : ragDoc)
                .toList();
    }

    private RagDocumentContext<GongCallDetails> getDocumentContext(final GongCallDetails call, final String transcript, final GongConfig.LocalArguments parsedArgs) {
        final TrimResult trimmedConversationResult = documentTrimmer.trimDocumentToKeywords(transcript, parsedArgs.getKeywords(), parsedArgs.getKeywordWindow());

        return Try.of(() -> sentenceSplitter.splitDocument(trimmedConversationResult.document(), 10))
                .map(sentences -> new RagDocumentContext<GongCallDetails>(
                        getName(),
                        getContextLabel(),
                        trimmedConversationResult.document(),
                        sentenceVectorizer.vectorize(sentences),
                        call.id(),
                        call,
                        "[Gong " + call.id() + "](" + call.url() + ")",
                        trimmedConversationResult.keywordMatches()))
                // Capture the gong transcript or transcript summary as an intermediate result
                // This is useful for debugging and understanding the context of the call
                .map(ragDoc -> ragDoc.addIntermediateResult(new IntermediateResult(ragDoc.document(), "Gong-" + ragDoc.id() + ".txt")))
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                .get();
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

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(
                        API.Case(API.$(instanceOf(EmptyString.class)), throwable -> new InternalFailure("The Gong transcript activities is empty", throwable)),
                        API.Case(API.$(instanceOf(FailedOllama.class)), throwable -> new InternalFailure(throwable.getMessage(), throwable)),
                        API.Case(API.$(instanceOf(InternalFailure.class)), throwable -> throwable),
                        API.Case(API.$(), ex -> new InternalFailure(getName() + " failed to call Ollama", ex)))
                .get();
    }

    @Override
    public String getContextLabel() {
        return "Gong Call";
    }

    private MetaObjectResults getMetadata(
            final RagDocumentContext<GongCallDetails> gongCall,
            final GongConfig.LocalArguments parsedArgs) {

        final List<MetaObjectResult> metadata = new ArrayList<>();

        if (StringUtils.isNotBlank(parsedArgs.getContextFilterQuestion())) {
            final int filterRating = Try.of(() -> ratingTool.call(
                                    Map.of(RatingTool.RATING_DOCUMENT_CONTEXT_ARG, gongCall.document()),
                                    parsedArgs.getContextFilterQuestion(),
                                    List.of())
                            .getResponse())
                    .map(rating -> Integer.parseInt(rating.trim()))
                    .onFailure(e -> logger.warning("Failed to get Gong call rating for ticket " + gongCall.id() + ": " + ExceptionUtils.getRootCauseMessage(e)))
                    // Ratings are provided on a best effort basis, so we ignore any failures
                    .recover(ex -> parsedArgs.getDefaultRating())
                    .get();

            metadata.add(new MetaObjectResult(GONG_FILTER_RATING_META, filterRating));
        }

        return new MetaObjectResults(
                metadata,
                "Gong-" + gongCall.id() + ".json",
                gongCall.id());
    }

    private boolean contextMeetsRating(
            final RagDocumentContext<GongCallDetails> call,
            final GongConfig.LocalArguments parsedArgs) {
        // If there was no filter question, then return the whole list
        if (StringUtils.isBlank(parsedArgs.getContextFilterQuestion())) {
            return true;
        }

        return Objects.requireNonNullElse(call.metadata(), new MetaObjectResults())
                .getIntValueByName(Gong.GONG_FILTER_RATING_META, parsedArgs.getDefaultRating())
                >= parsedArgs.getContextFilterMinimumRating();
    }

    /**
     * Summarise an individual call transcript
     */
    private RagDocumentContext<GongCallDetails> getCallSummary(final RagDocumentContext<GongCallDetails> ragDoc, final Map<String, String> environmentSettings, final GongConfig.LocalArguments parsedArgs) {
        logger.log(Level.INFO, "Summarising Gong call transcript");

        final RagDocumentContext<String> context = new RagDocumentContext<>(
                getName(),
                getContextLabel(),
                ragDoc.document(),
                List.of()
        );

        final String response = llmClient.callWithCache(
                new RagMultiDocumentContext<>(
                        parsedArgs.getTranscriptSummaryPrompt(),
                        "You are a helpful agent",
                        List.of(context)),
                environmentSettings,
                getName()
        ).getResponse();

        return ragDoc.updateDocument(response)
                .addIntermediateResult(new IntermediateResult(
                        "Prompt: " + parsedArgs.getTranscriptSummaryPrompt() + "\n\n" + response,
                        "Gong-" + ragDoc.id() + "-" + DigestUtils.sha256Hex(parsedArgs.getTranscriptSummaryPrompt()) + ".txt"
                ));
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

    public class LocalArguments {
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
                    "gong_company",
                    "").value();
        }

        public String getCallId() {
            return getArgsAccessor().getArgument(
                    getConfigCallId()::get,
                    arguments,
                    context,
                    Gong.CALLID_ARG,
                    "gong_call_id",
                    "").value();
        }

        public int getDays() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigDays()::get,
                    arguments,
                    context,
                    Gong.DAYS_ARG,
                    "gong_days",
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
                            "gong_keywords",
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
                    "gong_keyword_window",
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
                    "gong_summarizetranscript",
                    "").value();

            return BooleanUtils.toBoolean(value);
        }

        public String getTranscriptSummaryPrompt() {
            return getArgsAccessor()
                    .getArgument(
                            getConfigSummarizeTranscriptPrompt()::get,
                            arguments,
                            context,
                            Gong.GONG_SUMMARIZE_TRANSCRIPT_PROMPT_ARG,
                            "gong_transcript_summary_prompt",
                            "Summarise the Gong call transcript in three paragraphs")
                    .value();
        }

        public String getContextFilterQuestion() {
            return getArgsAccessor().getArgument(
                            getConfigContextFilterQuestion()::get,
                            arguments,
                            context,
                            Gong.GONG_FILTER_QUESTION_ARG,
                            "gong_rating_question",
                            "")
                    .value();
        }

        public Integer getContextFilterMinimumRating() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigContextFilterMinimumRating()::get,
                    arguments,
                    context,
                    Gong.GONG_FILTER_MINIMUM_RATING_ARG,
                    "gong_filter_minimum_rating",
                    "0");

            return org.apache.commons.lang.math.NumberUtils.toInt(argument.value(), 0);
        }

        public int getDefaultRating() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigContextFilterDefaultRating()::get,
                    arguments,
                    context,
                    ZenDeskIndividualTicket.ZENDESK_DEFAULT_RATING_ARG,
                    ZenDeskIndividualTicket.ZENDESK_DEFAULT_RATING_ARG,
                    DEFAULT_RATING + "");

            return Math.max(0, NumberUtils.toInt(argument.value(), DEFAULT_RATING));
        }
    }
}