package secondbrain.domain.tools.planhat;

import com.google.common.collect.ImmutableList;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.config.ModelConfig;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.context.SentenceSplitter;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.converter.HtmlToText;
import secondbrain.domain.date.DateParser;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.limit.DocumentTrimmer;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.planhat.Conversation;
import secondbrain.infrastructure.planhat.PlanHatClient;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.google.common.base.Predicates.instanceOf;

@ApplicationScoped
public class PlanHat implements Tool<Conversation> {
    public static final String DAYS_ARG = "days";
    public static final String SEARCHTTL_ARG = "searchTtl";
    public static final String COMPANY_ID_ARGS = "companyId";
    public static final String DISABLE_LINKS_ARG = "disableLinks";
    public static final String PLANHAT_KEYWORD_ARG = "keywords";
    public static final String PLANHAT_KEYWORD_WINDOW_ARG = "keywordWindow";

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
    private PlanHatClient planHatClient;

    @Inject
    private SentenceSplitter sentenceSplitter;

    @Inject
    private SentenceVectorizer sentenceVectorizer;

    @Inject
    private ModelConfig modelConfig;

    @Inject
    private PromptBuilderSelector promptBuilderSelector;

    @Inject
    private OllamaClient ollamaClient;

    @Inject
    private HtmlToText htmlToText;

    @Inject
    private DateParser dateParser;

    @Inject
    private DocumentTrimmer documentTrimmer;

    @Inject
    private ValidateString validateString;

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
    public List<RagDocumentContext<Conversation>> getContext(Map<String, String> context, String prompt, List<ToolArgs> arguments) {
        final PlanHatConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, context);

        if (StringUtils.isBlank(parsedArgs.getCompany())) {
            throw new InternalFailure("You must provide a company ID to query");
        }

        final List<Conversation> conversations = Try.withResources(ClientBuilder::newClient)
                .of(client -> planHatClient.getConversations(
                        client,
                        parsedArgs.getCompany(),
                        parsedArgs.getToken(),
                        parsedArgs.getSearchTTL()))
                .get();

        return conversations.stream()
                .filter(conversation -> parsedArgs.getDays() == 0
                        || dateParser.parseDate(conversation.date()).isAfter(ZonedDateTime.now(ZoneOffset.UTC).minusDays(parsedArgs.getDays())))
                .filter(conversation -> !"ticket".equals(conversation.type()))
                .map(conversation -> conversation.updateDescriptionAndSnippet(
                        htmlToText.getText(conversation.description()),
                        htmlToText.getText(conversation.snippet()))
                )
                .map(conversation -> conversation.updateDescriptionAndSnippet(
                        documentTrimmer.trimDocumentToKeywords(conversation.description(), parsedArgs.getKeywords(), parsedArgs.getKeywordWindow()),
                        documentTrimmer.trimDocumentToKeywords(conversation.snippet(), parsedArgs.getKeywords(), parsedArgs.getKeywordWindow()))
                )
                .filter(conversation -> !validateString.isEmpty(conversation, Conversation::getContent))
                .map(conversation -> getDocumentContext(conversation, parsedArgs))
                .collect(Collectors.toList());
    }

    @Override
    public RagMultiDocumentContext<Conversation> call(Map<String, String> context, String prompt, List<ToolArgs> arguments) {
        final List<RagDocumentContext<Conversation>> contextList = getContext(context, prompt, arguments);

        final PlanHatConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, context);

        if (StringUtils.isBlank(parsedArgs.getCompany())) {
            throw new InternalFailure("You must provide a company to query");
        }

        final Try<RagMultiDocumentContext<Conversation>> result = Try.of(() -> contextList)
                .map(ragDoc -> mergeContext(ragDoc, modelConfig.getCalculatedModel(context)))
                .map(ragContext -> ragContext.updateDocument(promptBuilderSelector
                        .getPromptBuilder(modelConfig.getCalculatedModel(context))
                        .buildFinalPrompt(
                                INSTRUCTIONS,
                                ragContext.getDocumentLeft(modelConfig.getCalculatedContextWindowChars()),
                                prompt)))
                .map(ragDoc -> ollamaClient.callOllamaWithCache(
                        ragDoc,
                        modelConfig.getCalculatedModel(context),
                        getName(),
                        modelConfig.getCalculatedContextWindow()));

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(
                        API.Case(API.$(instanceOf(EmptyString.class)), throwable -> new InternalFailure("The PlanHat activities is empty", throwable)),
                        API.Case(API.$(instanceOf(InternalFailure.class)), throwable -> throwable),
                        API.Case(API.$(), ex -> new InternalFailure(getName() + " failed to call Ollama", ex)))
                .get();
    }

    private RagDocumentContext<Conversation> getDocumentContext(final Conversation conversation, final PlanHatConfig.LocalArguments parsedArgs) {
        if (parsedArgs.getDisableLinks()) {
            return new RagDocumentContext<>(getContextLabel(), conversation.getContent(), List.of());
        }

        return Try.of(() -> sentenceSplitter.splitDocument(conversation.getContent(), 10))
                .map(sentences -> new RagDocumentContext<Conversation>(
                        getContextLabel() + " " + conversation.date(),
                        conversation.getContent(),
                        sentences.stream()
                                .map(sentenceVectorizer::vectorize)
                                .collect(Collectors.toList()),
                        conversation.id(),
                        conversation,
                        "[PlanHat " + conversation.id() + "](" + conversation.getPublicUrl(url) + ")"))
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                // If we can't vectorize the sentences, just return the document
                .recover(e -> new RagDocumentContext<>(
                        getContextLabel(),
                        conversation.getContent() + " " + conversation.date(),
                        List.of()))
                .get();
    }

    private RagMultiDocumentContext<Conversation> mergeContext(final List<RagDocumentContext<Conversation>> context, final String customModel) {
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
}

@ApplicationScoped
class PlanHatConfig {
    private static final String DEFAULT_TTL = "3600";

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
    @ConfigProperty(name = "sb.planhat.searchttl")
    private Optional<String> configSearchTtl;

    @Inject
    @ConfigProperty(name = "sb.planhat.disablelinks")
    private Optional<String> configDisableLinks;

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

    public Optional<String> getConfigCompany() {
        return configCompany;
    }

    public Optional<String> getConfigFrom() {
        return configFrom;
    }

    public Optional<String> getConfigToken() {
        return configToken;
    }

    public Optional<String> getConfigSearchTtl() {
        return configSearchTtl;
    }

    public Optional<String> getConfigDisableLinks() {
        return configDisableLinks;
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

        public int getSearchTTL() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigSearchTtl()::get,
                    arguments,
                    context,
                    PlanHat.SEARCHTTL_ARG,
                    "planhat_searchttl",
                    DEFAULT_TTL);

            return Try.of(argument::value)
                    .map(i -> Math.max(0, Integer.parseInt(i)))
                    .get();
        }

        public boolean getDisableLinks() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigDisableLinks()::get,
                    arguments,
                    context,
                    PlanHat.DISABLE_LINKS_ARG,
                    "planhat_disablelinks",
                    "false");

            return BooleanUtils.toBoolean(argument.value());
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
    }
}
