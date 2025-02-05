package secondbrain.domain.tools.slack;

import com.slack.api.Slack;
import com.slack.api.methods.response.search.SearchAllResponse;
import com.slack.api.model.MatchedItem;
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
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.keyword.KeywordExtractor;
import secondbrain.domain.limit.DocumentTrimmer;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.slack.SlackClient;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.google.common.base.Predicates.instanceOf;

@ApplicationScoped
public class SlackSearch implements Tool<MatchedItem> {
    public static final String SLACK_SEARCH_DAYS_ARG = "days";
    public static final String SLACK_SEARCH_KEYWORDS_ARG = "searchKeywords";
    public static final String SLACK_SEARCH_FILTER_KEYWORDS_ARG = "keywords";
    public static final String SLACK_SEARCH_DISABLELINKS_ARG = "disableLinks";

    private static final String INSTRUCTIONS = """
            You are professional agent that understands Slack conversations.
            You are given Slack search results and asked to answer questions based on the messages provided.
            """;

    @Inject
    private ModelConfig modelConfig;

    @Inject
    private SlackSearchArguments parsedArgs;

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
    public List<RagDocumentContext<MatchedItem>> getContext(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        parsedArgs.setInputs(arguments, prompt, context);

        final SearchAllResponse searchResult = Try.of(() -> slackClient.search(
                        Slack.getInstance().methodsAsync(),
                        parsedArgs.getAccessToken(),
                        parsedArgs.getSearchKeywords(),
                        parsedArgs.getSearchTTL()))
                .mapFailure(API.Case(API.$(), ex -> new ExternalFailure("Could not search messages", ex)))
                .get();

        if (searchResult == null || searchResult.getMessages() == null || CollectionUtils.isEmpty(searchResult.getMessages().getMatches())) {
            return List.of();
        }

        return searchResult
                .getMessages()
                .getMatches()
                .stream()
                .filter(matchedItem -> parsedArgs.getDays() == 0 || dateParser.parseDate(matchedItem.getTs()).isAfter(parsedArgs.getFromDate()))
                .filter(matchedItem -> parsedArgs.getIgnoreChannels()
                        .stream()
                        .noneMatch(matchedItem.getChannel().getName()::equalsIgnoreCase))
                .map(this::getDocumentContext)
                .map(ragDoc -> ragDoc.updateDocument(
                        documentTrimmer.trimDocument(
                                ragDoc.document(),
                                parsedArgs.getFilterKeywords(),
                                parsedArgs.getKeywordWindow())))
                .filter(ragDoc -> validateString.isNotEmpty(ragDoc.document()))
                .toList();

    }

    @Override
    public RagMultiDocumentContext<MatchedItem> call(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        final List<RagDocumentContext<MatchedItem>> contextList = getContext(context, prompt, arguments);

        final Try<RagMultiDocumentContext<MatchedItem>> result = Try.of(() -> mergeContext(contextList, context))
                .map(ragContext -> ragContext.updateDocument(
                        promptBuilderSelector.getPromptBuilder(modelConfig.getCalculatedModel(context)).buildFinalPrompt(
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
        return result
                .mapFailure(
                        API.Case(API.$(instanceOf(EmptyString.class)), throwable -> new InternalFailure("The Slack channel had no matching messages")),
                        API.Case(API.$(instanceOf(InternalFailure.class)), throwable -> throwable),
                        API.Case(API.$(), ex -> new ExternalFailure(getName() + " failed to call Ollama", ex)))
                .get();

    }

    private RagDocumentContext<MatchedItem> getDocumentContext(final MatchedItem meta) {
        if (parsedArgs.getDisableLinks()) {
            return new RagDocumentContext<>(getContextLabel(), meta.getText(), List.of());
        }

        return Try.of(() -> sentenceSplitter.splitDocument(meta.getText(), 10))
                .map(sentences -> new RagDocumentContext<MatchedItem>(
                        getContextLabel(),
                        meta.getText(),
                        sentences.stream()
                                .map(sentenceVectorizer::vectorize)
                                .collect(Collectors.toList()),
                        meta.getId(),
                        meta,
                        matchToUrl(meta)))
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                // If we can't vectorize the sentences, just return the document
                .recover(e -> new RagDocumentContext<>(getContextLabel(), meta.getText(), List.of(), meta.getId(), meta, null))
                .get();
    }

    private String matchToUrl(final MatchedItem matchedItem) {
        return "[" + StringUtils.substring(matchedItem.getText()
                        .replaceAll(":.*?:", "")
                        .replaceAll("[^A-Za-z0-9-._ ]", " ")
                        .trim(),
                0, 75) + "](" + matchedItem.getPermalink() + ")";
    }

    private RagMultiDocumentContext<MatchedItem> mergeContext(final List<RagDocumentContext<MatchedItem>> ragContext, Map<String, String> context) {
        return new RagMultiDocumentContext<>(
                ragContext.stream()
                        .map(ragDoc -> promptBuilderSelector.getPromptBuilder(modelConfig.getCalculatedModel(context)).buildContextPrompt("Slack Messages", ragDoc.document()))
                        .collect(Collectors.joining("\n")),
                ragContext);
    }
}

@ApplicationScoped
class SlackSearchArguments {
    private static final String DEFAULT_TTL = "3600";

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private Encryptor textEncryptor;

    @Inject
    private KeywordExtractor keywordExtractor;

    @Inject
    @ConfigProperty(name = "sb.slack.accesstoken")
    private Optional<String> slackAccessToken;

    @Inject
    @ConfigProperty(name = "sb.slack.searchkeywords")
    private Optional<String> searchKeywords;

    @Inject
    @ConfigProperty(name = "sb.slack.filterkeywords")
    private Optional<String> keywords;

    @Inject
    @ConfigProperty(name = "sb.slack.keywordwindow")
    private Optional<String> keywordWindow;

    @Inject
    @ConfigProperty(name = "sb.slack.ignorechannels")
    private Optional<String> ignoreChannels;

    @Inject
    @ConfigProperty(name = "sb.slack.genetarekeywords")
    private Optional<String> generateKeywords;

    @Inject
    @ConfigProperty(name = "sb.slack.days")
    private Optional<String> days;

    @Inject
    @ConfigProperty(name = "sb.slack.searchttl")
    private Optional<String> searchTtl;

    @Inject
    @ConfigProperty(name = "sb.slack.disablelinks")
    private Optional<String> disableLinks;

    @Inject
    private ValidateString validateString;

    private List<ToolArgs> arguments;

    private String prompt;

    private Map<String, String> context;

    public void setInputs(final List<ToolArgs> arguments, final String prompt, final Map<String, String> context) {
        this.arguments = arguments;
        this.prompt = prompt;
        this.context = context;
    }

    public Set<String> getSearchKeywords() {
        final List<String> keywordslist = argsAccessor.getArgumentList(
                        searchKeywords::get,
                        arguments,
                        context,
                        SlackSearch.SLACK_SEARCH_KEYWORDS_ARG,
                        "slack_search_keywords",
                        "")
                .stream()
                .map(Argument::value)
                .toList();

        final List<String> keywordsGenerated = getGenerateKeywords() ? keywordExtractor.getKeywords(prompt) : List.of();

        final HashSet<String> retValue = new HashSet<>();
        retValue.addAll(keywordslist);
        retValue.addAll(keywordsGenerated);
        return retValue;
    }

    public List<String> getFilterKeywords() {
        return argsAccessor.getArgumentList(
                        keywords::get,
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
        final String stringValue = argsAccessor.getArgument(
                generateKeywords::get,
                arguments,
                context,
                "generateKeywords",
                "slack_generatekeywords",
                "false").value();

        return BooleanUtils.toBoolean(stringValue);
    }

    public String getAccessToken() {
        return Try.of(() -> textEncryptor.decrypt(context.get("slack_access_token")))
                .recover(e -> context.get("slack_access_token"))
                .mapTry(Objects::requireNonNull)
                .recoverWith(e -> Try.of(() -> slackAccessToken.get()))
                .getOrElseThrow(() -> new InternalFailure("Slack access token not found"));
    }

    public int getDays() {
        final String stringValue = argsAccessor.getArgument(
                days::get,
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
        final String stringValue = argsAccessor.getArgument(
                searchTtl::get,
                arguments,
                context,
                "searchTtl",
                "slack_searchttl",
                DEFAULT_TTL).value();

        return Try.of(() -> stringValue)
                .map(i -> Math.max(0, Integer.parseInt(i)))
                .get();
    }

    public List<String> getIgnoreChannels() {
        final String stringValue = argsAccessor.getArgument(
                ignoreChannels::get,
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

    public boolean getDisableLinks() {
        final String stringValue = argsAccessor.getArgument(
                disableLinks::get,
                arguments,
                context,
                SlackSearch.SLACK_SEARCH_DISABLELINKS_ARG,
                "slack_disable_links",
                "false").value();

        return BooleanUtils.toBoolean(stringValue);
    }

    public int getKeywordWindow() {
        final Argument argument = argsAccessor.getArgument(
                keywordWindow::get,
                arguments,
                context,
                SlackChannel.SLACK_KEYWORD_WINDOW_ARG,
                "slack_keyword_window",
                Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH + "");

        return NumberUtils.toInt(argument.value(), Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH);
    }
}