package secondbrain.domain.tools.planhat;

import com.google.common.collect.ImmutableList;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.context.SentenceSplitter;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.converter.HtmlToText;
import secondbrain.domain.date.DateParser;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.exceptions.FailedOllama;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.limit.DocumentTrimmer;
import secondbrain.domain.limit.TrimResult;
import secondbrain.domain.tooldefs.IntermediateResult;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.planhat.PlanHatClient;
import secondbrain.infrastructure.planhat.api.Conversation;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static com.google.common.base.Predicates.instanceOf;

@ApplicationScoped
public class PlanHat implements Tool<Conversation> {
    public static final String DAYS_ARG = "days";
    public static final String SEARCH_TTL_ARG = "searchTtl";
    public static final String COMPANY_ID_ARGS = "companyId";
    public static final String PLANHAT_KEYWORD_ARG = "keywords";
    public static final String PLANHAT_KEYWORD_WINDOW_ARG = "keywordWindow";
    public static final String PLANHAT_ENTITY_NAME_CONTEXT_ARG = "entityName";
    public static final String PLANHAT_SUMMARIZE_DOCUMENT_ARG = "summarizeDocument";
    public static final String PLANHAT_SUMMARIZE_DOCUMENT_PROMPT_ARG = "summarizeDocumentPrompt";

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
    private PlanHatConfig config;

    @Inject
    @Preferred
    private PlanHatClient planHatClient;

    @Inject
    private SentenceSplitter sentenceSplitter;

    @Inject
    private SentenceVectorizer sentenceVectorizer;

    @Inject
    @Preferred
    private LlmClient llmClient;

    @Inject
    private HtmlToText htmlToText;

    @Inject
    private DateParser dateParser;

    @Inject
    private DocumentTrimmer documentTrimmer;

    @Inject
    private ValidateString validateString;

    @Inject
    private Logger logger;

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
                                parsedArgs.getSearchTTL()))
                        // Don't let the failure of one instance affect the other
                        .onFailure(throwable -> logger.warning("Failed to get conversations: " + ExceptionUtils.getRootCauseMessage(throwable)))
                        .recover(ex -> List.of())
                        .get()
                        .stream())
                .toList();

        return conversations.stream()
                .filter(conversation -> parsedArgs.getCompany().equals(conversation.companyId()))
                .filter(conversation -> parsedArgs.getDays() == 0
                        || dateParser.parseDate(conversation.date()).isAfter(ZonedDateTime.now(ZoneOffset.UTC).minusDays(parsedArgs.getDays())))
                .filter(conversation -> !"ticket".equals(conversation.type()))
                .map(conversation -> conversation.updateDescriptionAndSnippet(
                        htmlToText.getText(conversation.description()),
                        htmlToText.getText(conversation.snippet()))
                )
                .map(conversation -> getDocumentContext(conversation, parsedArgs))
                .filter(ragDoc -> !validateString.isEmpty(ragDoc, RagDocumentContext::document))
                .map(ragDoc -> ragDoc.updateIntermediateResult(new IntermediateResult(ragDoc.document(), "PlanHat" + ragDoc.id() + ".txt")))
                .map(doc -> parsedArgs.getSummarizeDocument()
                        ? doc.updateDocument(getDocumentSummary(doc.document(), environmentSettings, parsedArgs))
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
                .map(ragDoc -> llmClient.callWithCache(
                        ragDoc,
                        environmentSettings,
                        getName()));

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(
                        API.Case(API.$(instanceOf(EmptyString.class)), throwable -> new InternalFailure("The PlanHat activities is empty", throwable)),
                        API.Case(API.$(instanceOf(InternalFailure.class)), throwable -> throwable),
                        API.Case(API.$(instanceOf(FailedOllama.class)), throwable -> new InternalFailure(throwable.getMessage(), throwable)),
                        API.Case(API.$(), ex -> new InternalFailure(getName() + " failed to call Ollama", ex)))
                .get();
    }

    private Pair<Conversation, List<String>> trimConversation(final Conversation conversation, final PlanHatConfig.LocalArguments parsedArgs) {
        final TrimResult description = documentTrimmer.trimDocumentToKeywords(conversation.description(), parsedArgs.getKeywords(), parsedArgs.getKeywordWindow());
        final TrimResult snippet = documentTrimmer.trimDocumentToKeywords(conversation.snippet(), parsedArgs.getKeywords(), parsedArgs.getKeywordWindow());
        final Conversation trimmedConversation = conversation.updateDescriptionAndSnippet(description.document(), snippet.document());
        final List<String> keywords = CollectionUtils.union(description.keywordMatches(), snippet.keywordMatches())
                .stream()
                .distinct()
                .toList();

        return Pair.of(trimmedConversation, keywords);
    }

    private RagDocumentContext<Conversation> getDocumentContext(final Conversation conversation, final PlanHatConfig.LocalArguments parsedArgs) {
        final Pair<Conversation, List<String>> trimmedConversationResult = trimConversation(conversation, parsedArgs);

        final String contextLabel = getContextLabel() + " " + trimmedConversationResult.getLeft().companyName() + " " + trimmedConversationResult.getLeft().date();

        return Try.of(() -> sentenceSplitter.splitDocument(trimmedConversationResult.getLeft().getContent(), 10))
                .map(sentences -> new RagDocumentContext<Conversation>(
                        getName(),
                        contextLabel,
                        trimmedConversationResult.getLeft().getContent(),
                        sentenceVectorizer.vectorize(sentences, parsedArgs.getEntity()),
                        trimmedConversationResult.getLeft().id(),
                        trimmedConversationResult.getLeft(),
                        "[PlanHat " + trimmedConversationResult.getLeft().id() + "](" + trimmedConversationResult.getLeft().getPublicUrl(url) + ")",
                        trimmedConversationResult.getRight()))
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                .get();
    }

    private String getDocumentSummary(final String document, final Map<String, String> environmentSettings, final PlanHatConfig.LocalArguments parsedArgs) {
        final RagDocumentContext<String> context = new RagDocumentContext<>(
                getName(),
                getContextLabel(),
                document,
                List.of()
        );

        return llmClient.callWithCache(
                new RagMultiDocumentContext<>(
                        parsedArgs.getDocumentSummaryPrompt(),
                        "You are a helpful agent",
                        List.of(context)),
                environmentSettings,
                getName()
        ).getResponse();
    }
}

@ApplicationScoped
class PlanHatConfig {
    private static final String DEFAULT_TTL = (1000 * 60 * 60 * 24) + "";

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

    public class LocalArguments {
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
                    "planhat_company",
                    "").value();
        }

        public int getDays() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigFrom()::get,
                    arguments,
                    context,
                    PlanHat.DAYS_ARG,
                    "planhat_days",
                    "");

            return NumberUtils.toInt(argument.value(), 1);
        }


        public String getToken() {
            return Try.of(getConfigToken()::get)
                    .mapTry(getValidateString()::throwIfEmpty)
                    .recover(e -> context.get("planhat_token"))
                    .mapTry(getValidateString()::throwIfEmpty)
                    .recover(e -> "")
                    .get();
        }

        public String getUrl() {
            return Try.of(getConfigUrl()::get)
                    .mapTry(getValidateString()::throwIfEmpty)
                    .recover(e -> context.get("planhat_url"))
                    .mapTry(getValidateString()::throwIfEmpty)
                    .recover(e -> "")
                    .get();
        }

        public String getToken2() {
            return Try.of(getConfigToken2()::get)
                    .mapTry(getValidateString()::throwIfEmpty)
                    .recover(e -> context.get("planhat_token2"))
                    .mapTry(getValidateString()::throwIfEmpty)
                    .recover(e -> "")
                    .get();
        }

        public String getUrl2() {
            return Try.of(getConfigUrl2()::get)
                    .mapTry(getValidateString()::throwIfEmpty)
                    .recover(e -> context.get("planhat_url2"))
                    .mapTry(getValidateString()::throwIfEmpty)
                    .recover(e -> "")
                    .get();
        }

        public int getSearchTTL() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigSearchTtl()::get,
                    arguments,
                    context,
                    PlanHat.SEARCH_TTL_ARG,
                    "planhat_searchttl",
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
                            "planhat_keywords",
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
                    "upload_keyword_window",
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
                    "planhat_summarizedocument",
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
                            "planhat_summarizedocument_prompt",
                            "Summarise the document in three paragraphs")
                    .value();
        }
    }
}
