package secondbrain.domain.tools.gong;

import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.config.ModelConfig;
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
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.tools.gong.model.GongCallDetails;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.gong.GongClient;
import secondbrain.infrastructure.ollama.OllamaClient;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static com.google.common.base.Predicates.instanceOf;
import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;

@ApplicationScoped
public class Gong implements Tool<GongCallDetails> {
    public static final String DAYS_ARG = "days";
    public static final String COMPANY_ARG = "company";
    public static final String CALLID_ARG = "callId";
    public static final String GONG_KEYWORD_ARG = "keywords";
    public static final String GONG_DISABLELINKS_ARG = "disableLinks";
    public static final String GONG_KEYWORD_WINDOW_ARG = "keywordWindow";
    public static final String GONG_ENTITY_NAME_CONTEXT_ARG = "entityName";
    public static final String GONG_SUMMARIZE_TRANSCRIPT_ARG = "summarizeTranscript";
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
    private ModelConfig modelConfig;

    @Inject
    private PromptBuilderSelector promptBuilderSelector;

    @Inject
    private OllamaClient ollamaClient;

    @Inject
    private ValidateString validateString;

    @Inject
    private Logger logger;

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

        final GongConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final List<Pair<GongCallDetails, String>> calls = Try.withResources(ClientBuilder::newClient)
                .of(client -> Try.of(() ->
                                gongClient.getCallsExtensive(
                                        client,
                                        parsedArgs.getCompany(),
                                        parsedArgs.getCallId(),
                                        parsedArgs.getAccessKey(),
                                        parsedArgs.getAccessSecretKey(),
                                        parsedArgs.getStartDate(),
                                        parsedArgs.getEndDate()))
                        .map(c -> c.stream()
                                .map(call -> Pair.of(
                                        call,
                                        gongClient.getCallTranscript(client, parsedArgs.getAccessKey(), parsedArgs.getAccessSecretKey(), call.id())))
                                .toList())
                        /*
                            Take the raw transcript and summarize them with individual calls to the LLM.
                            The transcripts are then combined into a single context.
                            This was necessary because the private LLMs didn't do a very good job of summarizing
                            raw tickets. The reality is that even LLMs with a context length of 128k can't process multiple
                            call transcripts.
                         */
                        .map(transcripts -> parsedArgs.getSummarizeTranscript() ? summariseCalls(transcripts, environmentSettings) : transcripts)
                        .onFailure(ex -> logger.severe("Failed to get Gong calls: " + ExceptionUtils.getRootCauseMessage(ex)))
                        .get())
                .get();

        return calls.stream()
                .map(pair -> getDocumentContext(pair.getLeft(), pair.getRight(), parsedArgs))
                .filter(ragDoc -> !validateString.isEmpty(ragDoc, RagDocumentContext::document))
                .toList();
    }

    private RagDocumentContext<GongCallDetails> getDocumentContext(final GongCallDetails call, final String transcript, final GongConfig.LocalArguments parsedArgs) {
        final TrimResult trimmedConversationResult = documentTrimmer.trimDocumentToKeywords(transcript, parsedArgs.getKeywords(), parsedArgs.getKeywordWindow());

        if (parsedArgs.getDisableLinks()) {
            return new RagDocumentContext<>(
                    getContextLabel(),
                    trimmedConversationResult.document(), List.of(), null, null, null, trimmedConversationResult.keywordMatches());
        }

        return Try.of(() -> sentenceSplitter.splitDocument(trimmedConversationResult.document(), 10))
                .map(sentences -> new RagDocumentContext<GongCallDetails>(
                        getContextLabel(),
                        trimmedConversationResult.document(),
                        sentenceVectorizer.vectorize(sentences),
                        call.id(),
                        call,
                        "[Gong " + call.id() + "](" + call.url() + ")",
                        trimmedConversationResult.keywordMatches()))
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                .get();
    }

    @Override
    public RagMultiDocumentContext<GongCallDetails> call(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        final List<RagDocumentContext<GongCallDetails>> contextList = getContext(environmentSettings, prompt, arguments);

        final GongConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        if (StringUtils.isBlank(parsedArgs.getCompany()) && StringUtils.isBlank(parsedArgs.getCallId())) {
            throw new InternalFailure("You must provide a company or call ID to query");
        }

        final Try<RagMultiDocumentContext<GongCallDetails>> result = Try.of(() -> contextList)
                .map(ragDoc -> mergeContext(ragDoc, modelConfig.getCalculatedModel(environmentSettings)))
                .map(ragContext -> ragContext.updateDocument(promptBuilderSelector
                        .getPromptBuilder(modelConfig.getCalculatedModel(environmentSettings))
                        .buildFinalPrompt(
                                INSTRUCTIONS,
                                ragContext.getDocumentRight(modelConfig.getCalculatedContextWindowChars(environmentSettings)),
                                prompt)))
                .map(ragDoc -> ollamaClient.callOllamaWithCache(
                        ragDoc,
                        modelConfig.getCalculatedModel(environmentSettings),
                        getName(),
                        modelConfig.getCalculatedContextWindow(environmentSettings)));

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(
                        API.Case(API.$(instanceOf(EmptyString.class)), throwable -> new InternalFailure("The Gong transcript activities is empty", throwable)),
                        API.Case(API.$(instanceOf(FailedOllama.class)), throwable -> new InternalFailure(throwable.getMessage(), throwable)),
                        API.Case(API.$(instanceOf(InternalFailure.class)), throwable -> throwable),
                        API.Case(API.$(), ex -> new InternalFailure(getName() + " failed to call Ollama", ex)))
                .get();
    }

    private RagMultiDocumentContext<GongCallDetails> mergeContext(final List<RagDocumentContext<GongCallDetails>> context, final String customModel) {
        return new RagMultiDocumentContext<>(
                context.stream()
                        .map(ragDoc -> promptBuilderSelector
                                .getPromptBuilder(customModel)
                                .buildContextPrompt(
                                        ragDoc.contextLabel(),
                                        ragDoc.document()))
                        .collect(Collectors.joining("\n")),
                context);
    }

    @Override
    public String getContextLabel() {
        return "Gong Call";
    }

    /**
     * Summarise the calls by passing them through the LLM
     */
    private List<Pair<GongCallDetails, String>> summariseCalls(final List<Pair<GongCallDetails, String>> tickets, final Map<String, String> context) {
        return tickets.stream()
                .map(pair -> Pair.of(pair.getLeft(), getCallSummary(pair.getRight(), context)))
                .collect(Collectors.toList());
    }

    /**
     * Summarise an individual ticket
     */
    private String getCallSummary(final String transcript, final Map<String, String> environmentSettings) {
        final String ticketContext = promptBuilderSelector
                .getPromptBuilder(modelConfig.getCalculatedModel(environmentSettings))
                .buildContextPrompt("Gong Transcript", transcript);

        final String prompt = promptBuilderSelector
                .getPromptBuilder(modelConfig.getCalculatedModel(environmentSettings))
                .buildFinalPrompt("You are a helpful agent", ticketContext, "Summarise the Gong call transcript in three paragraphs");

        return ollamaClient.callOllamaWithCache(
                new RagMultiDocumentContext<>(prompt),
                modelConfig.getCalculatedModel(environmentSettings),
                getName(),
                modelConfig.getCalculatedContextWindow(environmentSettings)
        ).combinedDocument();
    }
}

@ApplicationScoped
class GongConfig {
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
    @ConfigProperty(name = "sb.gong.disablelinks")
    private Optional<String> configDisableLinks;

    @Inject
    @ConfigProperty(name = "sb.gong.keywords")
    private Optional<String> configKeywords;

    @Inject
    @ConfigProperty(name = "sb.gong.keywordwindow")
    private Optional<String> configKeywordWindow;

    @Inject
    @ConfigProperty(name = "sb.gong.summarizetranscript", defaultValue = "false")
    private Optional<String> configSummarizeTranscript;

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

    public Optional<String> getConfigDisableLinks() {
        return configDisableLinks;
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

        public boolean getDisableLinks() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigDisableLinks()::get,
                    arguments,
                    context,
                    Gong.GONG_DISABLELINKS_ARG,
                    "gong_disable_links",
                    "false").value();

            return BooleanUtils.toBoolean(stringValue);
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
    }
}