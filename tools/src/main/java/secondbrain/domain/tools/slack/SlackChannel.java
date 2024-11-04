package secondbrain.domain.tools.slack;

import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.response.conversations.ConversationsHistoryResponse;
import com.slack.api.methods.response.conversations.ConversationsListResponse;
import com.slack.api.model.ConversationType;
import com.slack.api.model.Message;
import io.vavr.Tuple;
import io.vavr.control.Try;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jasypt.util.text.BasicTextEncryptor;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.ollama.OllamaGenerateBody;
import secondbrain.infrastructure.ollama.OllamaResponse;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

@Dependent
public class SlackChannel implements Tool {
    private static final int MINIMUM_MESSAGE_LENGTH = 300;

    @Inject
    @ConfigProperty(name = "sb.ollama.model", defaultValue = "llama3.2")
    String model;

    @Inject
    @ConfigProperty(name = "sb.ollama.contentlength", defaultValue = "" + Constants.MAX_CONTEXT_LENGTH)
    String limit;

    @Inject
    @ConfigProperty(name = "sb.encryption.password", defaultValue = "12345678")
    String encryptionPassword;

    @Inject
    @ConfigProperty(name = "sb.slack.accesstoken")
    Optional<String> slackAccessToken;

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private OllamaClient ollamaClient;

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
    public String call(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {
        final String channel = argsAccessor.getArgument(arguments, "channel", "").trim()
                .replaceFirst("^#", "");
        final int days = Try.of(() -> Integer.parseInt(argsAccessor.getArgument(arguments, "days", "7")))
                .recover(throwable -> 7)
                .get();

        final String oldest = Long.valueOf(LocalDateTime.now(ZoneId.systemDefault())
                        .minusDays(days)
                        .atZone(ZoneId.systemDefault())
                        .toEpochSecond())
                .toString();

        final BasicTextEncryptor textEncryptor = new BasicTextEncryptor();
        textEncryptor.setPassword(encryptionPassword);

        // Try to decrypt the value, otherwise assume it is a plain text value, and finally
        // fall back to the value defined in the local configuration.
        final Try<String> accessToken = Try.of(() -> textEncryptor.decrypt(context.get("slack_access_token")))
                .recover(e -> context.get("slack_access_token"))
                .mapTry(Objects::requireNonNull)
                .recoverWith(e -> Try.of(() -> slackAccessToken.get()));

        if (accessToken.isFailure()) {
            return "Slack access token not found";
        }

        // you can get this instance via ctx.client() in a Bolt app
        var client = Slack.getInstance().methods();

        final Try<ChannelDetails> id = findChannelId(client, accessToken.get(), channel, null)
                .onFailure(error -> System.out.println("Error: " + error));

        if (id.isFailure()) {
            return "Channel " + channel + " not found";
        }

        final Try<String> messages = id
                .mapTry(chanId -> client.conversationsHistory(r -> r
                        .token(accessToken.get())
                        .channel(chanId.channelId())
                        .oldest(oldest)))
                .map(this::conversationsToText)
                .onFailure(error -> System.out.println("Error: " + error));

        if (messages.isFailure()) {
            return "Messages could not be read";
        }

        if (messages.get().length() < MINIMUM_MESSAGE_LENGTH) {
            return "Not enough messages found in channel " + channel
                    + System.lineSeparator() + System.lineSeparator()
                    + "* [Slack Channel](https://app.slack.com/client/" + id.get().teamId() + "/" + id.get().channelId() + ")";
        }

        final Try<String> messagesWithUsersReplaced = messages
                .flatMap(m -> replaceIds(client, accessToken.get(), m));

        if (messagesWithUsersReplaced.isFailure()) {
            return "The user and channel IDs could not be replaced";
        }

        final String messageContext = buildToolPrompt(messagesWithUsersReplaced.get(), prompt);

        return Try.of(() -> callOllama(messageContext))
                .map(response -> response.response()
                        + System.lineSeparator() + System.lineSeparator()
                        + "* [Slack Channel](https://app.slack.com/client/" + id.get().teamId() + "/" + id.get().channelId() + ")")
                .recover(throwable -> "Failed to call Ollama: " + throwable.getMessage())
                .get();

    }


    private Optional<ChannelDetails> getChannelId(final ConversationsListResponse response, final String channel) {
        return response.getChannels()
                .stream()
                .filter(c -> c.getName().equals(channel))
                .map(c -> new ChannelDetails(c.getId(), c.getContextTeamId()))
                .findFirst();
    }


    private String buildToolPrompt(final String context, final String prompt) {
        return """
                <|begin_of_text|>
                <|start_header_id|>system<|end_header_id|>
                You are professional agent that understands Slack conversations.
                You are given the history of a Slack channel and asked to answer questions based on the messages provided.
                The token "<!here>" is used to notify all members of the channel.
                You must consider any message with the token "<!here>" to be important.
                Here are the messages:
                """
                + context.substring(0, Math.min(context.length(), NumberUtils.toInt(limit, Constants.MAX_CONTEXT_LENGTH)))
                + "<|eot_id|><|start_header_id|>user<|end_header_id|>"
                + prompt
                + "<|eot_id|><|start_header_id|>assistant<|end_header_id|>".stripLeading();
    }


    private String conversationsToText(final ConversationsHistoryResponse conversation) {
        return conversation.getMessages()
                .stream()
                .map(Message::getText)
                .reduce("", (a, b) -> a + "\n" + b);
    }


    private OllamaResponse callOllama(final String llmPrompt) {
        return Try.withResources(ClientBuilder::newClient)
                .of(client -> ollamaClient.getTools(
                        client,
                        new OllamaGenerateBody(model, llmPrompt, false))).get();
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
}
