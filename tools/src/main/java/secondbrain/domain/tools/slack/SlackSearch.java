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
import secondbrain.domain.config.LocalConfigFilteredItem;
import secondbrain.domain.config.LocalConfigFilteredParent;
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
import secondbrain.domain.processing.RatingFilter;
import secondbrain.domain.processing.RatingMetadata;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.slack.SlackClient;
import secondbrain.infrastructure.slack.api.SlackSearchResultResource;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Predicates.instanceOf;

@ApplicationScoped
public class SlackSearch implements Tool<SlackSearchResultResource> {
    public static final String SLACK_FILTER_QUESTION_ARG = "contentRatingQuestion";
    public static final String SLACK_FILTER_MINIMUM_RATING_ARG = "contextFilterMinimumRating";
    public static final String SLACK_SEARCH_DAYS_ARG = "days";
    public static final String SLACK_SEARCH_KEYWORDS_ARG = "searchKeywords";
    public static final String SLACK_SEARCH_FILTER_KEYWORDS_ARG = "keywords";
    public static final String SLACK_ENTITY_NAME_CONTEXT_ARG = "entityName";
    public static final String SLACK_IGNORE_CHANNELS_ARG = "ignoreChannels";
    public static final String API_DELAY_ARG = "apiDelay";
    public static final String SEARCH_TTL_ARG = "searchTtl";
    public static final String SLACK_GENERATE_KEYWORDS_ARG = "generateKeywords";
    public static final String SLACK_DEFAULT_RATING_ARG = "defaultRating";

    private static final String INSTRUCTIONS = """
            You are professional agent that understands Slack conversations.
            You are given Slack search results and asked to answer questions based on the messages provided.
            """;

    @Inject
    private RatingMetadata ratingMetadata;

    @Inject
    private RatingFilter ratingFilter;

    @Inject
    private SlackSearchConfig config;

    @Inject
    @Preferred
    private LlmClient llmClient;

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

    @Inject
    private Logger logger;

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

        logger.log(Level.INFO, "Getting context for " + getName());

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
                // Get the metadata, which includes a rating against the filter question if present
                .map(ragDoc -> ragDoc.updateMetadata(ratingMetadata.getMetadata(getName(), environmentSettings, ragDoc, parsedArgs)))
                // Filter out any documents that don't meet the rating criteria
                .filter(ragDoc -> ratingFilter.contextMeetsRating(ragDoc, parsedArgs))
                .toList();

    }

    @Override
    public RagMultiDocumentContext<SlackSearchResultResource> call(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        logger.log(Level.INFO, "Calling " + getName());

        final List<RagDocumentContext<SlackSearchResultResource>> contextList = getContext(environmentSettings, prompt, arguments);

        final Try<RagMultiDocumentContext<SlackSearchResultResource>> result = Try.of(() -> new RagMultiDocumentContext<>(prompt, INSTRUCTIONS, contextList))
                .map(ragDoc -> llmClient.callWithCache(
                        ragDoc,
                        environmentSettings,
                        getName()));

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
                        getName(),
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
    private static final int DEFAULT_RATING = 10;

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

    @Inject
    @ConfigProperty(name = "sb.slack.contextFilterQuestion")
    private Optional<String> configContextFilterQuestion;

    @Inject
    @ConfigProperty(name = "sb.slack.contextFilterMinimumRating")
    private Optional<String> configContextFilterMinimumRating;

    @Inject
    @ConfigProperty(name = "sb.slack.contextFilterDefaultRating")
    private Optional<String> configContextFilterDefaultRating;

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

    public Optional<String> getConfigContextFilterQuestion() {
        return configContextFilterQuestion;
    }

    public Optional<String> getConfigContextFilterMinimumRating() {
        return configContextFilterMinimumRating;
    }

    public Optional<String> getConfigContextFilterDefaultRating() {
        return configContextFilterDefaultRating;
    }

    public class LocalArguments implements LocalConfigFilteredItem, LocalConfigFilteredParent {
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
                            SlackSearch.SLACK_SEARCH_KEYWORDS_ARG,
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
                            SlackChannel.SLACK_KEYWORD_ARG,
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
                    SlackSearch.SLACK_GENERATE_KEYWORDS_ARG,
                    SlackSearch.SLACK_GENERATE_KEYWORDS_ARG,
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
                    SlackSearch.SLACK_SEARCH_DAYS_ARG,
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
                    SlackSearch.SEARCH_TTL_ARG,
                    SlackSearch.SEARCH_TTL_ARG,
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
                    SlackSearch.API_DELAY_ARG,
                    SlackSearch.API_DELAY_ARG,
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
                    SlackSearch.SLACK_IGNORE_CHANNELS_ARG,
                    SlackSearch.SLACK_IGNORE_CHANNELS_ARG,
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
                    SlackChannel.SLACK_KEYWORD_WINDOW_ARG,
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

        public String getContextFilterQuestion() {
            return getArgsAccessor().getArgument(
                            getConfigContextFilterQuestion()::get,
                            arguments,
                            context,
                            SlackSearch.SLACK_FILTER_QUESTION_ARG,
                            SlackSearch.SLACK_FILTER_QUESTION_ARG,
                            "")
                    .value();
        }

        public Integer getContextFilterMinimumRating() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigContextFilterMinimumRating()::get,
                    arguments,
                    context,
                    SlackSearch.SLACK_FILTER_MINIMUM_RATING_ARG,
                    SlackSearch.SLACK_FILTER_MINIMUM_RATING_ARG,
                    "0");

            return org.apache.commons.lang.math.NumberUtils.toInt(argument.value(), 0);
        }

        public Integer getDefaultRating() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigContextFilterDefaultRating()::get,
                    arguments,
                    context,
                    SlackSearch.SLACK_DEFAULT_RATING_ARG,
                    SlackSearch.SLACK_DEFAULT_RATING_ARG,
                    DEFAULT_RATING + "");

            return Math.max(0, org.apache.commons.lang3.math.NumberUtils.toInt(argument.value(), DEFAULT_RATING));
        }
    }
}