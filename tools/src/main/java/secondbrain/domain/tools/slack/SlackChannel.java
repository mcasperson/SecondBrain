package secondbrain.domain.tools.slack;

import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.response.conversations.ConversationsHistoryResponse;
import com.slack.api.methods.response.conversations.ConversationsListResponse;
import com.slack.api.model.ConversationType;
import com.slack.api.model.Message;
import io.smallrye.common.annotation.Identifier;
import io.vavr.Tuple;
import io.vavr.control.Try;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.SentenceSplitter;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.context.SimilarityCalculator;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.sanitize.SanitizeArgument;
import secondbrain.domain.sanitize.SanitizeDocument;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.ollama.OllamaGenerateBody;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Dependent
public class SlackChannel implements Tool {
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
    @ConfigProperty(name = "sb.annotation.minsimilarity", defaultValue = "0.5")
    String minSimilarity;

    @Inject
    private Encryptor textEncryptor;

    @Inject
    @Identifier("removeMarkdnUrls")
    private SanitizeDocument removeMarkdnUrls;

    @Inject
    private SentenceSplitter sentenceSplitter;

    @Inject
    private SimilarityCalculator similarityCalculator;

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
    public String call(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {
        final String channel = argsAccessor.getArgument(arguments, "channel", "").trim()
                .replaceFirst("^#", "");
        final int days = Try.of(() -> Integer.parseInt(argsAccessor.getArgument(arguments, "days", "7")))
                .recover(throwable -> 7)
                .get();

        final float parsedMinSimilarity = Try.of(() -> Float.parseFloat(minSimilarity))
                .recover(throwable -> 0.5f)
                .get();

        final String oldest = Long.valueOf(LocalDateTime.now(ZoneId.systemDefault())
                        .minusDays(days)
                        .atZone(ZoneId.systemDefault())
                        .toEpochSecond())
                .toString();

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


        return Try.of(() -> getDocumentContext(messagesWithUsersReplaced.get(), prompt))
                .map(ragContext -> ragContext.updateDocument(promptBuilderSelector
                        .getPromptBuilder(model)
                        .buildFinalPrompt(
                                INSTRUCTIONS,
                                ragContext.getDocumentLeft(NumberUtils.toInt(limit, Constants.MAX_CONTEXT_LENGTH)),
                                prompt)))
                .map(this::callOllama)
                .map(result -> result.annotateDocumentContext(
                        parsedMinSimilarity,
                        10,
                        sentenceSplitter,
                        similarityCalculator,
                        sentenceVectorizer))
                .map(response -> response
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

    private RagDocumentContext<Void> getDocumentContext(final String document, final String prompt) {
        return Try.of(() -> sentenceSplitter.splitDocument(document, 10))
                // Strip out any URLs from the sentences
                .map(sentences -> sentences.stream().map(sentence -> removeMarkdnUrls.sanitize(sentence)).toList())
                .map(sentences -> new RagDocumentContext<Void>(
                        promptBuilderSelector.getPromptBuilder(model).buildContextPrompt("Message", document),
                        sentences.stream()
                                .map(sentenceVectorizer::vectorize)
                                .collect(Collectors.toList())))
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


    private RagDocumentContext<Void> callOllama(final RagDocumentContext<Void> llmPrompt) {
        return Try.withResources(ClientBuilder::newClient)
                .of(client -> ollamaClient.getTools(
                        client,
                        new OllamaGenerateBody(model, llmPrompt.document(), false)))
                .map(response -> new RagDocumentContext<Void>(response.response(), llmPrompt.sentences()))
                .get();
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
