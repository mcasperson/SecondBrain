package secondbrain.domain.tools.slack;

import com.slack.api.Slack;
import com.slack.api.methods.AsyncMethodsClient;
import com.slack.api.methods.response.conversations.ConversationsHistoryResponse;
import com.slack.api.model.Message;
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
import secondbrain.domain.limit.DocumentTrimmer;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.sanitize.SanitizeDocument;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.slack.SlackClient;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.google.common.base.Predicates.instanceOf;

@ApplicationScoped
public class SlackChannel implements Tool<Void> {
    public static final String SLACK_CHANEL_ARG = "slackChannel";
    public static final String DAYS_ARG = "days";
    public static final String SLACK_DISABLELINKS_ARG = "disableLinks";
    public static final String SLACK_KEYWORD_ARG = "keywords";
    public static final String SLACK_KEYWORD_WINDOW_ARG = "keywordWindow";

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
    private OllamaClient ollamaClient;

    @Inject
    private PromptBuilderSelector promptBuilderSelector;

    @Inject
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
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        final SlackChannelConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, context);

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

        final ChannelDetails channelDetails = Try.of(() -> slackClient.findChannelId(
                        client,
                        parsedArgs.getAccessToken(),
                        parsedArgs.getChannel()))
                .getOrElseThrow(() -> new InternalFailure("Channel not found"));

        final Try<String> messagesTry = Try.of(() -> slackClient.conversationHistory(
                        client,
                        parsedArgs.getAccessToken(),
                        channelDetails.channelId(),
                        oldest,
                        parsedArgs.getSearchTTL()))
                .map(this::conversationsToText)
                .map(document -> documentTrimmer.trimDocumentToKeywords(
                        document,
                        parsedArgs.getKeywords(),
                        parsedArgs.getKeywordWindow()))
                .map(validateString::throwIfEmpty);

        final String messages = messagesTry
                .mapFailure(
                        API.Case(API.$(instanceOf(EmptyString.class)), throwable -> new InternalFailure("The Slack channel had no matching messages")),
                        API.Case(API.$(instanceOf(ExternalFailure.class)), ex -> ex),
                        API.Case(API.$(), ex -> new InternalFailure("Failed to get messages", ex))
                )
                .get();

        if (StringUtils.length(messages) < MINIMUM_MESSAGE_LENGTH) {
            throw new InternalFailure("Not enough messages found in channel " + parsedArgs.getChannel()
                    + System.lineSeparator() + System.lineSeparator()
                    + "* [Slack Channel](https://app.slack.com/client/" + channelDetails.teamId() + "/" + channelDetails.channelId() + ")");
        }

        final String messagesWithUsersReplaced = replaceIds(client, parsedArgs.getAccessToken(), messages)
                .getOrElseThrow(() -> new InternalFailure("The user and channel IDs could not be replaced"));

        return List.of(getDocumentContext(messagesWithUsersReplaced, channelDetails, parsedArgs));
    }

    @Override
    public RagMultiDocumentContext<Void> call(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        final SlackChannelConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, context);

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> getContext(context, prompt, arguments))
                .map(ragDoc -> new RagMultiDocumentContext<>(
                        ragDoc.stream()
                                .map(document -> promptBuilderSelector.getPromptBuilder(modelConfig.getCalculatedModel(context)).buildContextPrompt("Message", document.document()))
                                .collect(Collectors.joining(System.lineSeparator())),
                        ragDoc))
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
                        API.Case(API.$(instanceOf(EmptyString.class)), throwable -> new InternalFailure("No slack messages found")),
                        API.Case(API.$(instanceOf(InternalFailure.class)), throwable -> throwable),
                        API.Case(API.$(instanceOf(FailedOllama.class)), throwable -> throwable),
                        API.Case(API.$(), ex -> new ExternalFailure("Unexpected error", ex)))
                .get();
    }

    private RagDocumentContext<Void> getDocumentContext(final String document, final ChannelDetails channelDetails, final SlackChannelConfig.LocalArguments parsedArgs) {
        if (parsedArgs.getDisableLinks()) {
            return new RagDocumentContext<>(getContextLabel(), document, List.of());
        }

        return Try.of(() -> sentenceSplitter.splitDocument(document, 10))
                // Strip out any URLs from the sentences
                .map(sentences -> sentences.stream().map(sentence -> removeMarkdnUrls.sanitize(sentence)).toList())
                .map(sentences -> new RagDocumentContext<Void>(
                        getContextLabel(),
                        document,
                        sentences.stream()
                                .map(sentenceVectorizer::vectorize)
                                .collect(Collectors.toList()),
                        channelDetails.channelName(),
                        null,
                        matchToUrl(channelDetails)))
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                // If we can't vectorize the sentences, just return the document
                .recover(e -> new RagDocumentContext<Void>(getContextLabel(), document, List.of()))
                .get();
    }


    private String conversationsToText(final ConversationsHistoryResponse conversation) {
        return conversation.getMessages()
                .stream()
                .map(Message::getText)
                .reduce("", (a, b) -> a + "\n" + b);
    }


    private Try<String> replaceIds(final AsyncMethodsClient client, final String token, final String messages) {
        final Pattern userPattern = Pattern.compile("<@(?<username>\\w+)>");
        final Pattern channelPattern = Pattern.compile("<#(?<channelname>\\w+)\\|?>");

        return Try.of(() -> Tuple.of(messages, userPattern.matcher(messages).results()))
                /*
                We map the original message and the list of regex matches for user IDs to a string with
                the user IDs replaced with their usernames.
                 */
                .map(results -> results._2().reduce(
                        results._1(),
                        (m, match) -> m.replace(match.group(), getUsername(client, token, match.group("username")).get()),
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
                        (m, match) -> m.replace(match.group(), getChannel(client, token, match.group("channelname")).get()),
                        (s, s2) -> s + s2))
                /*
                 If any of the previous steps failed, we return the original messages.
                 */
                .recover(error -> messages);
    }


    private Try<String> getUsername(final AsyncMethodsClient client, final String token, final String userId) {
        return Try.of(() -> client.usersInfo(r -> r.token(token).user(userId)).get())
                .map(response -> response.getUser().getName())
                /*
                    If the username could not be retrieved, we return a placeholder.
                    We could omit this to bubble the errors up, but mostly we want to apply a best effort
                    to get context and be tolerant of errors.
                 */
                .recover(error -> "Unknown user");
    }


    private Try<String> getChannel(final AsyncMethodsClient client, final String token, final String channelId) {
        return Try.of(() -> client.conversationsInfo(r -> r.token(token).channel(channelId)).get())
                .map(response -> "#" + response.getChannel().getName())
                /*
                    If the channel name could not be retrieved, we return a placeholder.
                    We could omit this to bubble the errors up, but mostly we want to apply a best effort
                    to get context and be tolerant of errors.
                 */
                .recover(error -> "Unknown channel");
    }

    private String matchToUrl(final ChannelDetails channel) {
        return "[Slack " + channel.channelName() + "](https://app.slack.com/client/" + channel.teamId() + "/" + channel.channelId() + ")";
    }
}

@ApplicationScoped
class SlackChannelConfig {
    private static final String DEFAULT_TTL = "3600";

    @Inject
    @ConfigProperty(name = "sb.slack.accesstoken")
    private Optional<String> slackAccessToken;

    @Inject
    @ConfigProperty(name = "sb.slack.channel")
    private Optional<String> channel;

    @Inject
    @ConfigProperty(name = "sb.slack.days")
    private Optional<String> days;

    @Inject
    @ConfigProperty(name = "sb.slack.historytto")
    private Optional<String> historyttl;

    @Inject
    @ConfigProperty(name = "sb.slack.disablelinks")
    private Optional<String> disableLinks;

    @Inject
    @ConfigProperty(name = "sb.slack.keywords")
    private Optional<String> keywords;

    @Inject
    @ConfigProperty(name = "sb.slack.keywordwindow")
    private Optional<String> keywordWindow;

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private Encryptor textEncryptor;

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
            return argsAccessor.getArgument(
                            channel::get,
                            arguments,
                            context,
                            SlackChannel.SLACK_CHANEL_ARG,
                            "slack_channel",
                            "")
                    .value()
                    .replaceFirst("^#", "");
        }

        public int getDays() {
            final Argument argument = argsAccessor.getArgument(
                    days::get,
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
            return Try.of(() -> textEncryptor.decrypt(context.get("slack_access_token")))
                    .recover(e -> context.get("slack_access_token"))
                    .mapTry(Objects::requireNonNull)
                    .recoverWith(e -> Try.of(() -> slackAccessToken.get()))
                    .getOrElseThrow(() -> new InternalFailure("Slack access token not found"));
        }

        public int getSearchTTL() {
            final Argument argument = argsAccessor.getArgument(
                    historyttl::get,
                    arguments,
                    context,
                    "historyTtl",
                    "slack_historyttl",
                    DEFAULT_TTL);

            return Try.of(argument::value)
                    .map(i -> Math.max(0, Integer.parseInt(i)))
                    .get();
        }

        public boolean getDisableLinks() {
            final Argument argument = argsAccessor.getArgument(
                    disableLinks::get,
                    arguments,
                    context,
                    SlackChannel.SLACK_DISABLELINKS_ARG,
                    "slack_disable_links",
                    "false");

            return BooleanUtils.toBoolean(argument.value());
        }

        public List<String> getKeywords() {
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
}
