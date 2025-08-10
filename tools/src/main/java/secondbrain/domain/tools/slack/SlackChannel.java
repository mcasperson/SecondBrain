package secondbrain.domain.tools.slack;

import com.slack.api.Slack;
import com.slack.api.methods.AsyncMethodsClient;
import io.smallrye.common.annotation.Identifier;
import io.vavr.API;
import io.vavr.Tuple;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.FailedOllama;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.limit.DocumentTrimmer;
import secondbrain.domain.limit.TrimResult;
import secondbrain.domain.sanitize.SanitizeDocument;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.slack.SlackClient;
import secondbrain.infrastructure.slack.api.SlackChannelResource;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.google.common.base.Predicates.instanceOf;

@ApplicationScoped
public class SlackChannel implements Tool<Void> {
    public static final String SLACK_CHANEL_ARG = "slackChannel";
    public static final String DAYS_ARG = "days";
    public static final String SLACK_KEYWORD_ARG = "keywords";
    public static final String SLACK_KEYWORD_WINDOW_ARG = "keywordWindow";
    public static final String SLACK_ENTITY_NAME_CONTEXT_ARG = "entityName";
    public static final String SLACK_SUMMARIZE_DOCUMENT_ARG = "summarizeDocument";
    public static final String SLACK_SUMMARIZE_DOCUMENT_PROMPT_ARG = "summarizeDocumentPrompt";

    private static final int MINIMUM_MESSAGE_LENGTH = 300;
    private static final String INSTRUCTIONS = """
            You are professional agent that understands Slack conversations.
            You are given the history of a Slack channel and asked to answer questions based on the messages provided.
            The tokens "<!here>" and "<!channel>" are used to notify all members of the channel.
            You must consider any message with the tokens "<!here>" or "<!channel>" to be important.
            """;

    @Inject
    private ModelConfig modelConfig;

    @Inject
    private SlackChannelConfig config;

    @Inject
    @Identifier("removeMarkdnUrls")
    private SanitizeDocument removeMarkdnUrls;

    @Inject
    private SentenceSplitter sentenceSplitter;

    @Inject
    private SentenceVectorizer sentenceVectorizer;

    @Inject
    private LlmClient llmClient;

    @Inject
    @Preferred
    private SlackClient slackClient;

    @Inject
    private ValidateString validateString;

    @Inject
    private DocumentTrimmer documentTrimmer;

    @Override
    public String getName() {
        return SlackChannel.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Returns messages from a Slack channel";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return List.of(
                new ToolArguments(SLACK_CHANEL_ARG, "The Slack channel to read", "general"),
                new ToolArguments(SLACK_KEYWORD_ARG, "The keywords to limit the Slack messages to", ""),
                new ToolArguments(SLACK_KEYWORD_WINDOW_ARG, "The window size around any matching keywords", ""),
                new ToolArguments(DAYS_ARG, "The number of days worth of messages to return", "7")
        );
    }

    @Override
    public String getContextLabel() {
        return "Slack Messages";
    }

    @Override
    public List<RagDocumentContext<Void>> getContext(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final SlackChannelConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        /*
            Get the oldest date to search from, starting from the starr of the current day.
            This improves the cache if we have to rerun the tool multiple times in a day, as the
            oldest date will be the same.
        */
        final String oldest = Long.valueOf(LocalDateTime.now(ZoneId.systemDefault())
                        .toLocalDate()
                        .atStartOfDay()
                        .minusDays(parsedArgs.getDays())
                        .atZone(ZoneId.systemDefault())
                        .toEpochSecond())
                .toString();

        // you can get this instance via ctx.client() in a Bolt app
        final AsyncMethodsClient client = Slack.getInstance().methodsAsync();

        final SlackChannelResource channel = Try.of(() -> slackClient.findChannelId(
                        client,
                        parsedArgs.getAccessToken(),
                        parsedArgs.getChannel(),
                        parsedArgs.getApiDelay()))
                .getOrElseThrow(() -> new InternalFailure("Channel not found"));

        final Try<TrimResult> messagesTry = Try.of(() -> slackClient.conversationHistory(
                        client,
                        parsedArgs.getAccessToken(),
                        channel.channelId(),
                        oldest,
                        parsedArgs.getSearchTTL(),
                        parsedArgs.getApiDelay()))
                .map(document -> documentTrimmer.trimDocumentToKeywords(
                        document,
                        parsedArgs.getKeywords(),
                        parsedArgs.getKeywordWindow()))
                .map(trimResult -> validateString.throwIfEmpty(trimResult, TrimResult::document));

        final TrimResult messages = messagesTry
                .mapFailure(
                        API.Case(API.$(instanceOf(EmptyString.class)), throwable -> new InternalFailure("The Slack channel had no matching messages")),
                        API.Case(API.$(instanceOf(ExternalFailure.class)), ex -> ex),
                        API.Case(API.$(), ex -> new InternalFailure("Failed to get messages", ex))
                )
                .get();

        if (StringUtils.length(messages.document()) < MINIMUM_MESSAGE_LENGTH) {
            throw new InternalFailure("Not enough messages found in channel " + parsedArgs.getChannel()
                    + System.lineSeparator() + System.lineSeparator()
                    + "* [Slack Channel](https://app.slack.com/client/" + channel.teamId() + "/" + channel.channelId() + ")");
        }

        final TrimResult messagesWithUsersReplaced = replaceIds(client, parsedArgs.getAccessToken(), messages.document(), parsedArgs)
                .map(messages::replaceDocument)
                .getOrElseThrow(() -> new InternalFailure("The user and channel IDs could not be replaced"));

        return List.of(getDocumentContext(messagesWithUsersReplaced, channel, environmentSettings, parsedArgs));
    }

    @Override
    public RagMultiDocumentContext<Void> call(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final SlackChannelConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> getContext(environmentSettings, prompt, arguments))
                .map(ragDoc -> new RagMultiDocumentContext<>(prompt, INSTRUCTIONS, ragDoc))
                .map(ragDoc -> llmClient.callWithCache(
                        ragDoc,
                        environmentSettings,
                        getName()));

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(
                        API.Case(API.$(instanceOf(EmptyString.class)), throwable -> new InternalFailure("No slack messages found")),
                        API.Case(API.$(instanceOf(InternalFailure.class)), throwable -> throwable),
                        API.Case(API.$(instanceOf(FailedOllama.class)), throwable -> new InternalFailure(throwable.getMessage(), throwable)),
                        API.Case(API.$(), ex -> new ExternalFailure("Unexpected error", ex)))
                .get();
    }

    private RagDocumentContext<Void> getDocumentContext(final TrimResult trimResult, final SlackChannelResource channelDetails, final Map<String, String> environmentSettings, final SlackChannelConfig.LocalArguments parsedArgs) {
        return Try.of(() -> sentenceSplitter.splitDocument(trimResult.document(), 10))
                // Strip out any URLs from the sentences
                .map(sentences -> sentences.stream().map(sentence -> removeMarkdnUrls.sanitize(sentence)).toList())
                .map(sentences -> new RagDocumentContext<Void>(
                        getName(),
                        getContextLabel(),
                        trimResult.document(),
                        sentenceVectorizer.vectorize(sentences, parsedArgs.getEntity()),
                        channelDetails.channelName(),
                        null,
                        matchToUrl(channelDetails),
                        trimResult.keywordMatches()))
                .map(doc -> parsedArgs.getSummarizeDocument()
                        ? doc.updateDocument(getDocumentSummary(doc.document(), environmentSettings, parsedArgs))
                        : doc)
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                .get();
    }

    private Try<String> replaceIds(final AsyncMethodsClient client, final String token, final String messages, final SlackChannelConfig.LocalArguments parsedArgs) {
        final Pattern userPattern = Pattern.compile("<@(?<username>\\w+)>");
        final Pattern channelPattern = Pattern.compile("<#(?<channelname>\\w+)\\|?>");

        /*
            Start with a pairing of the original messages the results of the regex matching for user IDs.
         */
        return Try.of(() -> Tuple.of(messages, userPattern.matcher(messages).results()))
                /*
                We map the original message and the list of regex matches for user IDs to a string with
                the user IDs replaced with their usernames.
                 */
                .map(results ->
                        // We want to take each username ID match, replace it with a username, and reduce the results down to a single string.
                        results._2().reduce(
                                // The starting point is the original message.
                                results._1(),
                                // Each user id match is replaced with the username retrieved from the Slack API.
                                // The original message with the user IDs replaced is returned.
                                (m, match) -> m.replace(match.group(), slackClient.username(client, token, match.group("username"), parsedArgs.getApiDelay())),
                                // This is required to allow the reduce function to take a matcher but return a string.
                                (s, s2) -> s + s2))
                /*
                The string with user ids replaces is then mapped to a tuple with the original string and a list of regex
                matching channel IDs.
                 */
                .map(messagesWithUsersReplaced -> Tuple.of(messagesWithUsersReplaced, channelPattern.matcher(messagesWithUsersReplaced).results()))
                /*
                 The regex results for channel IDs are then reduced to a string with the channel IDs replaced with their
                 names.
                 */
                .map(results -> results._2().reduce(
                        results._1(),
                        (m, match) -> m.replace(match.group(), slackClient.channel(client, token, match.group("channelname"), parsedArgs.getApiDelay()).channelName()),
                        (s, s2) -> s + s2))
                /*
                 If any of the previous steps failed, we return the original messages.
                 */
                .recover(error -> messages);
    }

    private String getDocumentSummary(final String document, final Map<String, String> environmentSettings, final SlackChannelConfig.LocalArguments parsedArgs) {
        final RagDocumentContext<String> context = new RagDocumentContext<>(
                getName(),
                getContextLabel(),
                document,
                List.of()
        );

        return llmClient.callWithCache(
                new RagMultiDocumentContext<>(parsedArgs.getDocumentSummaryPrompt(),
                        "You are a helpful agent",
                        List.of(context)),
                environmentSettings,
                getName()
        ).getResponse();
    }

    private String matchToUrl(final SlackChannelResource channel) {
        return "[Slack " + channel.channelName() + "](https://app.slack.com/client/" + channel.teamId() + "/" + channel.channelId() + ")";
    }
}

@ApplicationScoped
class SlackChannelConfig {
    private static final String DEFAULT_TTL = (1000 * 60 * 60 * 24) + "";
    private static final int DEFAULT_API_DELAY = (1000 * 120);

    @Inject
    @ConfigProperty(name = "sb.slack.accesstoken")
    private Optional<String> configSlackAccessToken;

    @Inject
    @ConfigProperty(name = "sb.slack.channel")
    private Optional<String> configChannel;

    @Inject
    @ConfigProperty(name = "sb.slack.days")
    private Optional<String> configDays;

    @Inject
    @ConfigProperty(name = "sb.slack.historytto")
    private Optional<String> configHistoryttl;

    @Inject
    @ConfigProperty(name = "sb.slack.keywords")
    private Optional<String> configKeywords;

    @Inject
    @ConfigProperty(name = "sb.slack.keywordwindow")
    private Optional<String> configKeywordWindow;

    @Inject
    @ConfigProperty(name = "sb.slack.apidelay")
    private Optional<String> configApiDelay;


    @Inject
    @ConfigProperty(name = "sb.slack.summarizedocument", defaultValue = "false")
    private Optional<String> configSummarizeDocument;

    @Inject
    @ConfigProperty(name = "sb.slack.summarizedocumentprompt")
    private Optional<String> configSummarizeDocumentPrompt;

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private Encryptor textEncryptor;

    public Optional<String> getConfigSlackAccessToken() {
        return configSlackAccessToken;
    }

    public Optional<String> getConfigChannel() {
        return configChannel;
    }

    public Optional<String> getConfigDays() {
        return configDays;
    }

    public Optional<String> getConfigHistoryttl() {
        return configHistoryttl;
    }

    public Optional<String> getConfigKeywords() {
        return configKeywords;
    }

    public Optional<String> getConfigKeywordWindow() {
        return configKeywordWindow;
    }

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    public Encryptor getTextEncryptor() {
        return textEncryptor;
    }

    public Optional<String> getConfigApiDelay() {
        return configApiDelay;
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

        public String getChannel() {
            return getArgsAccessor().getArgument(
                            getConfigChannel()::get,
                            arguments,
                            context,
                            SlackChannel.SLACK_CHANEL_ARG,
                            "slack_channel",
                            "")
                    .value()
                    .replaceFirst("^#", "");
        }

        public int getDays() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigDays()::get,
                    arguments,
                    context,
                    SlackChannel.DAYS_ARG,
                    "slack_days",
                    "30");

            return Try.of(argument::value)
                    .map(i -> Math.max(0, Integer.parseInt(i)))
                    .get();
        }

        public String getAccessToken() {
            return Try.of(() -> getTextEncryptor().decrypt(context.get("slack_access_token")))
                    .recover(e -> context.get("slack_access_token"))
                    .mapTry(Objects::requireNonNull)
                    .recoverWith(e -> Try.of(() -> getConfigSlackAccessToken().get()))
                    .getOrElseThrow(() -> new InternalFailure("Slack access token not found"));
        }

        public int getSearchTTL() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigHistoryttl()::get,
                    arguments,
                    context,
                    "historyTtl",
                    "slack_historyttl",
                    DEFAULT_TTL);

            return Try.of(argument::value)
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

        public List<String> getKeywords() {
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
                    SlackChannel.SLACK_ENTITY_NAME_CONTEXT_ARG,
                    "").value();
        }

        public boolean getSummarizeDocument() {
            final String value = getArgsAccessor().getArgument(
                    getConfigSummarizeDocument()::get,
                    arguments,
                    context,
                    SlackChannel.SLACK_SUMMARIZE_DOCUMENT_ARG,
                    "slack_summarizedocument",
                    "").value();

            return BooleanUtils.toBoolean(value);
        }

        public String getDocumentSummaryPrompt() {
            return getArgsAccessor()
                    .getArgument(
                            getConfigSummarizeDocumentPrompt()::get,
                            arguments,
                            context,
                            SlackChannel.SLACK_SUMMARIZE_DOCUMENT_PROMPT_ARG,
                            "slack_summarizedocument_prompt",
                            "Summarise the document in three paragraphs")
                    .value();
        }
    }
}
