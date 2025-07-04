package secondbrain.domain.tools.slack;

import com.slack.api.Slack;
import io.smallrye.common.annotation.Identifier;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.collections4.CollectionUtils;
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
import secondbrain.domain.date.DateParser;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.FailedOllama;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.keyword.KeywordExtractor;
import secondbrain.domain.limit.DocumentTrimmer;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.slack.SlackClient;
import secondbrain.infrastructure.slack.api.SlackSearchResultResource;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.google.common.base.Predicates.instanceOf;

@ApplicationScoped
public class SlackSearch implements Tool<SlackSearchResultResource> {
    public static final String SLACK_SEARCH_DAYS_ARG = "days";
    public static final String SLACK_SEARCH_KEYWORDS_ARG = "searchKeywords";
    public static final String SLACK_SEARCH_FILTER_KEYWORDS_ARG = "keywords";
    public static final String SLACK_ENTITY_NAME_CONTEXT_ARG = "entityName";

    private static final String INSTRUCTIONS = """
            You are professional agent that understands Slack conversations.
            You are given Slack search results and asked to answer questions based on the messages provided.
            """;

    @Inject
    private ModelConfig modelConfig;

    @Inject
    private SlackSearchConfig config;

    @Inject
    private OllamaClient ollamaClient;

    @Inject
    private PromptBuilderSelector promptBuilderSelector;

    @Inject
    private SentenceSplitter sentenceSplitter;

    @Inject
    private SentenceVectorizer sentenceVectorizer;

    @Inject
    private DocumentTrimmer documentTrimmer;

    @Inject
    private ValidateString validateString;

    @Inject
    @Identifier("unix")
    private DateParser dateParser;

    @Inject
    @Preferred
    private SlackClient slackClient;

    @Override
    public String getName() {
        return SlackSearch.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Searches Slack for messages that match the prompt";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return List.of(
                new ToolArguments(SLACK_SEARCH_KEYWORDS_ARG, "Optional comma separated list of keywords defined in the prompt", "")
        );
    }

    @Override
    public String getContextLabel() {
        return "Slack Messages";
    }

    @Override
    public List<RagDocumentContext<SlackSearchResultResource>> getContext(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final SlackSearchConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        // If there is nothing to search for, return an empty list
        if (CollectionUtils.isEmpty(parsedArgs.getSearchKeywords())) {
            return List.of();
        }

        final List<SlackSearchResultResource> searchResult = Try.of(() -> slackClient.search(
                        Slack.getInstance().methodsAsync(),
                        parsedArgs.getAccessToken(),
                        parsedArgs.getSearchKeywords(),
                        parsedArgs.getSearchTTL(),
                        parsedArgs.getApiDelay()))
                .mapFailure(API.Case(API.$(), ex -> new ExternalFailure("Could not search messages", ex)))
                .get();

        if (searchResult == null || CollectionUtils.isEmpty(searchResult)) {
            return List.of();
        }

        return searchResult
                .stream()
                .filter(matchedItem -> parsedArgs.getDays() == 0 || dateParser.parseDate(matchedItem.timestamp()).isAfter(parsedArgs.getFromDate()))
                .filter(matchedItem -> parsedArgs.getIgnoreChannels()
                        .stream()
                        .noneMatch(matchedItem.channelName()::equalsIgnoreCase))
                .map(matchedItem -> getDocumentContext(matchedItem, parsedArgs))
                .map(ragDoc -> ragDoc.updateDocument(
                        documentTrimmer.trimDocumentToKeywords(
                                ragDoc.document(),
                                parsedArgs.getFilterKeywords(),
                                parsedArgs.getKeywordWindow())))
                .filter(ragDoc -> validateString.isNotEmpty(ragDoc.document()))
                .toList();

    }

    @Override
    public RagMultiDocumentContext<SlackSearchResultResource> call(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final List<RagDocumentContext<SlackSearchResultResource>> contextList = getContext(environmentSettings, prompt, arguments);

        final Try<RagMultiDocumentContext<SlackSearchResultResource>> result = Try.of(() -> mergeContext(contextList, environmentSettings))
                .map(ragContext -> ragContext.updateDocument(
                        promptBuilderSelector.getPromptBuilder(modelConfig.getCalculatedModel(environmentSettings)).buildFinalPrompt(
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
        return result
                .mapFailure(
                        API.Case(API.$(instanceOf(EmptyString.class)), throwable -> new InternalFailure("The Slack channel had no matching messages")),
                        API.Case(API.$(instanceOf(InternalFailure.class)), throwable -> throwable),
                        API.Case(API.$(instanceOf(FailedOllama.class)), throwable -> new InternalFailure(throwable.getMessage(), throwable)),
                        API.Case(API.$(), ex -> new ExternalFailure(getName() + " failed to call Ollama", ex)))
                .get();

    }

    private RagDocumentContext<SlackSearchResultResource> getDocumentContext(final SlackSearchResultResource meta, final SlackSearchConfig.LocalArguments parsedArgs) {
        return Try.of(() -> sentenceSplitter.splitDocument(meta.text(), 10))
                .map(sentences -> new RagDocumentContext<>(
                        getContextLabel(),
                        meta.text(),
                        sentenceVectorizer.vectorize(sentences, parsedArgs.getEntity()),
                        meta.id(),
                        meta,
                        matchToUrl(meta)))
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                .get();
    }

    private String matchToUrl(final SlackSearchResultResource matchedItem) {
        return "[" + StringUtils.substring(matchedItem.text()
                        .replaceAll(":.*?:", "")
                        .replaceAll("[^A-Za-z0-9-._ ]", " ")
                        .trim(),
                0, 75) + "](" + matchedItem.permalink() + ")";
    }

    private RagMultiDocumentContext<SlackSearchResultResource> mergeContext(final List<RagDocumentContext<SlackSearchResultResource>> ragContext, final Map<String, String> context) {
        return new RagMultiDocumentContext<>(
                ragContext.stream()
                        .map(ragDoc ->
                                promptBuilderSelector.getPromptBuilder(
                                                modelConfig.getCalculatedModel(context))
                                        .buildContextPrompt(getContextLabel(), ragDoc.document()))
                        .collect(Collectors.joining("\n")),
                ragContext);
    }
}

@ApplicationScoped
class SlackSearchConfig {
    /**
     * Cache slack search results for nearly a week. This is based on the assumption that
     * we're generating reports every week, and that rerunning the report generation within
     * a week should reuse the same search results.
     */
    private static final String DEFAULT_TTL = (1000 * 60 * 60 * 24 * 6) + "";
    private static final int DEFAULT_API_DELAY = (1000 * 120);

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private Encryptor textEncryptor;

    @Inject
    private KeywordExtractor keywordExtractor;

    @Inject
    @ConfigProperty(name = "sb.slack.accesstoken")
    private Optional<String> configSlackAccessToken;

    @Inject
    @ConfigProperty(name = "sb.slack.searchkeywords")
    private Optional<String> configSearchKeywords;

    @Inject
    @ConfigProperty(name = "sb.slack.filterkeywords")
    private Optional<String> configKeywords;

    @Inject
    @ConfigProperty(name = "sb.slack.keywordwindow")
    private Optional<String> configKeywordWindow;

    @Inject
    @ConfigProperty(name = "sb.slack.ignorechannels")
    private Optional<String> configIgnoreChannels;

    @Inject
    @ConfigProperty(name = "sb.slack.genetarekeywords")
    private Optional<String> configGenerateKeywords;

    @Inject
    @ConfigProperty(name = "sb.slack.days")
    private Optional<String> configDays;

    @Inject
    @ConfigProperty(name = "sb.slack.searchttl")
    private Optional<String> configSearchTtl;

    @Inject
    @ConfigProperty(name = "sb.slack.apidelay")
    private Optional<String> configApiDelay;

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    public Encryptor getTextEncryptor() {
        return textEncryptor;
    }

    public KeywordExtractor getKeywordExtractor() {
        return keywordExtractor;
    }

    public Optional<String> getConfigSlackAccessToken() {
        return configSlackAccessToken;
    }

    public Optional<String> getConfigSearchKeywords() {
        return configSearchKeywords;
    }

    public Optional<String> getConfigKeywords() {
        return configKeywords;
    }

    public Optional<String> getConfigKeywordWindow() {
        return configKeywordWindow;
    }

    public Optional<String> getConfigIgnoreChannels() {
        return configIgnoreChannels;
    }

    public Optional<String> getConfigGenerateKeywords() {
        return configGenerateKeywords;
    }

    public Optional<String> getConfigDays() {
        return configDays;
    }

    public Optional<String> getConfigSearchTtl() {
        return configSearchTtl;
    }

    public Optional<String> getConfigApiDelay() {
        return configApiDelay;
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

        public Set<String> getSearchKeywords() {
            final List<String> keywordslist = getArgsAccessor().getArgumentList(
                            getConfigSearchKeywords()::get,
                            arguments,
                            context,
                            SlackSearch.SLACK_SEARCH_KEYWORDS_ARG,
                            "slack_search_keywords",
                            "")
                    .stream()
                    .map(Argument::value)
                    .toList();

            final List<String> keywordsGenerated = getGenerateKeywords() ? getKeywordExtractor().getKeywords(prompt) : List.of();

            final HashSet<String> retValue = new HashSet<>();
            retValue.addAll(keywordslist);
            retValue.addAll(keywordsGenerated);
            return retValue;
        }

        public List<String> getFilterKeywords() {
            return getArgsAccessor().getArgumentList(
                            getConfigKeywords()::get,
                            arguments,
                            context,
                            SlackChannel.SLACK_KEYWORD_ARG,
                            "slack_keywords",
                            "")
                    .stream()
                    .map(Argument::value)
                    .toList();
        }

        public boolean getGenerateKeywords() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigGenerateKeywords()::get,
                    arguments,
                    context,
                    "generateKeywords",
                    "slack_generatekeywords",
                    "false").value();

            return BooleanUtils.toBoolean(stringValue);
        }

        public String getAccessToken() {
            return Try.of(() -> getTextEncryptor().decrypt(context.get("slack_access_token")))
                    .recover(e -> context.get("slack_access_token"))
                    .mapTry(Objects::requireNonNull)
                    .recoverWith(e -> Try.of(() -> getConfigSlackAccessToken().get()))
                    .getOrElseThrow(() -> new InternalFailure("Slack access token not found"));
        }

        public int getDays() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigDays()::get,
                    arguments,
                    context,
                    SlackSearch.SLACK_SEARCH_DAYS_ARG,
                    "slack_days",
                    "").value();

            return Try.of(() -> stringValue)
                    .map(i -> Math.max(0, Integer.parseInt(i)))
                    .get();
        }

        public ZonedDateTime getFromDate() {
            return ZonedDateTime.now(ZoneOffset.UTC).minusDays(getDays());
        }

        public int getSearchTTL() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigSearchTtl()::get,
                    arguments,
                    context,
                    "searchTtl",
                    "slack_searchttl",
                    DEFAULT_TTL).value();

            return Try.of(() -> stringValue)
                    .map(i -> Math.max(0, Integer.parseInt(i)))
                    .get();
        }

        public int getApiDelay() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigApiDelay()::get,
                    arguments,
                    context,
                    "apiDelay",
                    "slack_api_delay",
                    DEFAULT_API_DELAY + "").value();

            return Try.of(() -> stringValue)
                    .map(i -> Math.max(0, NumberUtils.toInt(i, DEFAULT_API_DELAY)))
                    .get();
        }

        public List<String> getIgnoreChannels() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigIgnoreChannels()::get,
                    arguments,
                    context,
                    "ignoreChannels",
                    "slack_ignorechannels",
                    "").value();

            return Arrays.stream(stringValue.split(","))
                    .filter(StringUtils::isNotBlank)
                    .map(StringUtils::trim)
                    .map(channel -> channel.replaceFirst("^#", ""))
                    .toList();
        }

        public int getKeywordWindow() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigKeywordWindow()::get,
                    arguments,
                    context,
                    SlackChannel.SLACK_KEYWORD_WINDOW_ARG,
                    "slack_keyword_window",
                    Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH + "");

            return NumberUtils.toInt(argument.value(), Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH);
        }

        public String getEntity() {
            return getArgsAccessor().getArgument(
                    null,
                    null,
                    context,
                    null,
                    SlackSearch.SLACK_ENTITY_NAME_CONTEXT_ARG,
                    "").value();
        }
    }
}