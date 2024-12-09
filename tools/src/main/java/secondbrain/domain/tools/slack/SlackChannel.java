package secondbrain.domain.tools.slack;

import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.response.conversations.ConversationsHistoryResponse;
import com.slack.api.methods.response.conversations.ConversationsListResponse;
import com.slack.api.model.ConversationType;
import com.slack.api.model.Message;
import io.smallrye.common.annotation.Identifier;
import io.vavr.API;
import io.vavr.Tuple;
import io.vavr.control.Try;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.context.SentenceSplitter;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptions.FailedTool;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.sanitize.SanitizeDocument;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.infrastructure.ollama.OllamaClient;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Dependent
public class SlackChannel implements Tool<Void> {
    private static final int MINIMUM_MESSAGE_LENGTH = 300;
    private static final String INSTRUCTIONS = """
            You are professional agent that understands Slack conversations.
            You are given the history of a Slack channel and asked to answer questions based on the messages provided.
            The tokens "<!here>" and "<!channel>" are used to notify all members of the channel.
            You must consider any message with the tokens "<!here>" or "<!channel>" to be important.
            """;

    @Inject
    @ConfigProperty(name = "sb.ollama.model", defaultValue = "llama3.2")
    String model;

    @Inject
    @ConfigProperty(name = "sb.ollama.contentlength", defaultValue = "" + Constants.MAX_CONTEXT_LENGTH)
    String limit;

    @Inject
    @ConfigProperty(name = "sb.slack.accesstoken")
    Optional<String> slackAccessToken;

    @Inject
    private Encryptor textEncryptor;

    @Inject
    @Identifier("removeMarkdnUrls")
    private SanitizeDocument removeMarkdnUrls;

    @Inject
    private SentenceSplitter sentenceSplitter;

    @Inject
    private SentenceVectorizer sentenceVectorizer;

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private OllamaClient ollamaClient;

    @Inject
    private PromptBuilderSelector promptBuilderSelector;

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
                new ToolArguments("channel", "The Slack channel to read", "general"),
                new ToolArguments("days", "The number of days worth of messages to return", "7")
        );
    }

    @Override
    public List<RagDocumentContext<Void>> getContext(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        final Arguments parsedArgs = Arguments.fromToolArgs(arguments, context, argsAccessor, textEncryptor, slackAccessToken);

        final String oldest = Long.valueOf(LocalDateTime.now(ZoneId.systemDefault())
                        .minusDays(parsedArgs.days())
                        .atZone(ZoneId.systemDefault())
                        .toEpochSecond())
                .toString();

        // you can get this instance via ctx.client() in a Bolt app
        var client = Slack.getInstance().methods();

        final Try<ChannelDetails> channelDetails = findChannelId(client, parsedArgs.accessToken(), parsedArgs.channel(), null)
                .onFailure(error -> System.out.println("Error: " + error));

        if (channelDetails.isFailure()) {
            throw new FailedTool("Channel " + parsedArgs.channel() + " not found");
        }

        final Try<String> messages = channelDetails
                .mapTry(chanId -> client.conversationsHistory(r -> r
                        .token(parsedArgs.accessToken())
                        .channel(chanId.channelId())
                        .oldest(oldest)))
                .map(this::conversationsToText)
                .onFailure(error -> System.out.println("Error: " + error));

        if (messages.isFailure()) {
            throw new FailedTool("Messages could not be read");
        }

        if (messages.get().length() < MINIMUM_MESSAGE_LENGTH) {
            throw new FailedTool("Not enough messages found in channel " + parsedArgs.channel()
                    + System.lineSeparator() + System.lineSeparator()
                    + "* [Slack Channel](https://app.slack.com/client/" + channelDetails.get().teamId() + "/" + channelDetails.get().channelId() + ")");
        }

        final Try<String> messagesWithUsersReplaced = messages
                .flatMap(m -> replaceIds(client, parsedArgs.accessToken(), m));

        if (messagesWithUsersReplaced.isFailure()) {
            throw new FailedTool("The user and channel IDs could not be replaced");
        }

        return List.of(getDocumentContext(messagesWithUsersReplaced.get(), channelDetails.get()));
    }

    @Override
    public RagMultiDocumentContext<Void> call(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        final List<RagDocumentContext<Void>> contextList = getContext(context, prompt, arguments);

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> contextList)
                .map(ragDoc -> new RagMultiDocumentContext<>(
                        ragDoc.stream()
                                .map(RagDocumentContext::document)
                                .collect(Collectors.joining(System.lineSeparator())),
                        ragDoc))
                .map(ragContext -> ragContext.updateDocument(promptBuilderSelector
                        .getPromptBuilder(model)
                        .buildFinalPrompt(
                                INSTRUCTIONS,
                                ragContext.getDocumentLeft(NumberUtils.toInt(limit, Constants.MAX_CONTEXT_LENGTH)),
                                prompt)))
                .map(ragDoc -> ollamaClient.callOllama(ragDoc, model));

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result
                .mapFailure(API.Case(API.$(), ex -> new FailedTool("Failed to call Ollama", ex)))
                .get();
    }


    private Optional<ChannelDetails> getChannelId(final ConversationsListResponse response, final String channel) {
        return response.getChannels()
                .stream()
                .filter(c -> c.getName().equals(channel))
                .map(c -> new ChannelDetails(c.getId(), c.getContextTeamId()))
                .findFirst();
    }

    private RagDocumentContext<Void> getDocumentContext(final String document, final ChannelDetails channelDetails) {
        return Try.of(() -> sentenceSplitter.splitDocument(document, 10))
                // Strip out any URLs from the sentences
                .map(sentences -> sentences.stream().map(sentence -> removeMarkdnUrls.sanitize(sentence)).toList())
                .map(sentences -> new RagDocumentContext<Void>(
                        promptBuilderSelector.getPromptBuilder(model).buildContextPrompt("Message", document),
                        sentences.stream()
                                .map(sentenceVectorizer::vectorize)
                                .collect(Collectors.toList()),
                        null,
                        null,
                        matchToUrl(channelDetails)))
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                // If we can't vectorize the sentences, just return the document
                .recover(e -> new RagDocumentContext<Void>(document, List.of()))
                .get();
    }


    private String conversationsToText(final ConversationsHistoryResponse conversation) {
        return conversation.getMessages()
                .stream()
                .map(Message::getText)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private Try<ChannelDetails> findChannelId(
            final MethodsClient client,
            final String accessToken,
            final String channel,
            final String cursor) {

        final Try<ConversationsListResponse> response = Try.of(() -> client.conversationsList(r -> r
                .token(accessToken)
                .limit(1000)
                .types(List.of(ConversationType.PUBLIC_CHANNEL))
                .excludeArchived(true)
                .cursor(cursor)));

        if (response.isSuccess()) {
            final Optional<ChannelDetails> id = getChannelId(response.get(), channel);
            return id
                    .map(Try::success)
                    .orElseGet(() -> findChannelId(client, accessToken, channel, response.get().getResponseMetadata().getNextCursor()));
        }

        return Try.failure(new RuntimeException("Failed to get channels"));
    }


    private Try<String> replaceIds(final MethodsClient client, final String token, final String messages) {
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


    private Try<String> getUsername(final MethodsClient client, final String token, final String userId) {
        return Try.of(() -> client.usersInfo(r -> r.token(token).user(userId)))
                .map(response -> response.getUser().getName())
                /*
                    If the username could not be retrieved, we return a placeholder.
                    We could omit this to bubble the errors up, but mostly we want to apply a best effort
                    to get context and be tolerant of errors.
                 */
                .recover(error -> "Unknown user");
    }


    private Try<String> getChannel(final MethodsClient client, final String token, final String channelId) {
        return Try.of(() -> client.conversationsInfo(r -> r.token(token).channel(channelId)))
                .map(response -> "#" + response.getChannel().getName())
                /*
                    If the channel name could not be retrieved, we return a placeholder.
                    We could omit this to bubble the errors up, but mostly we want to apply a best effort
                    to get context and be tolerant of errors.
                 */
                .recover(error -> "Unknown channel");
    }

    private String matchToUrl(final ChannelDetails channel) {
        return "[Slack Channel](https://app.slack.com/client/" + channel.teamId() + "/" + channel.channelId() + ")";
    }

    /**
     * A record that hold the arguments used by the tool. This centralizes the logic for extracting, validating, and sanitizing
     * the various inputs to the tool.
     */
    record Arguments(String channel, int days, String accessToken) {
        public static Arguments fromToolArgs(final List<ToolArgs> arguments, final Map<String, String> context, final ArgsAccessor argsAccessor, final Encryptor textEncryptor, final Optional<String> slackAccessToken) {
            final String channel = argsAccessor.getArgument(arguments, "channel", "").trim()
                    .replaceFirst("^#", "");

            final int days = Try.of(() -> Integer.parseInt(argsAccessor.getArgument(arguments, "days", "7")))
                    .recover(throwable -> 7)
                    .get();

            // Try to decrypt the value, otherwise assume it is a plain text value, and finally
            // fall back to the value defined in the local configuration.
            final String accessToken = Try.of(() -> textEncryptor.decrypt(context.get("slack_access_token")))
                    .recover(e -> context.get("slack_access_token"))
                    .mapTry(Objects::requireNonNull)
                    .recoverWith(e -> Try.of(() -> slackAccessToken.get()))
                    .getOrElseThrow(() -> new FailedTool("Slack access token not found"));

            return new Arguments(channel, days, accessToken);
        }

    }
}
