package secondbrain.domain.tools.slack;

import com.slack.api.Slack;
import com.slack.api.methods.AsyncMethodsClient;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.response.conversations.ConversationsHistoryResponse;
import com.slack.api.methods.response.conversations.ConversationsListResponse;
import com.slack.api.model.ConversationType;
import com.slack.api.model.Message;
import io.smallrye.common.annotation.Identifier;
import io.vavr.API;
import io.vavr.Tuple;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.tika.utils.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.config.ModelConfig;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.context.SentenceSplitter;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptions.FailedOllama;
import secondbrain.domain.exceptions.FailedTool;
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
    private Arguments parsedArgs;

    @Inject
    @ConfigProperty(name = "sb.slack.accesstoken")
    private Optional<String> slackAccessToken;

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

    @Inject
    private ValidateString validateString;

    @Inject
    private SlackClient slackClient;

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
                new ToolArguments("slackChannel", "The Slack channel to read", "general"),
                new ToolArguments("days", "The number of days worth of messages to return", "7")
        );
    }

    public String getContextLabel() {
        return "Slack Messages";
    }

    @Override
    public List<RagDocumentContext<Void>> getContext(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        parsedArgs.setInputs(arguments, prompt, context);

        final String oldest = Long.valueOf(LocalDateTime.now(ZoneId.systemDefault())
                        .minusDays(parsedArgs.getDays())
                        .atZone(ZoneId.systemDefault())
                        .toEpochSecond())
                .toString();

        // you can get this instance via ctx.client() in a Bolt app
        var client = Slack.getInstance().methodsAsync();

        final Try<ChannelDetails> channelDetails = slackClient.findChannelId(client, parsedArgs.getAccessToken(), parsedArgs.getChannel(), null);

        if (channelDetails.isFailure()) {
            throw new FailedTool("Channel " + parsedArgs.getChannel() + " not found");
        }

        final Try<String> messages = channelDetails
                .mapTry(chanId -> client.conversationsHistory(r -> r
                        .token(parsedArgs.getAccessToken())
                        .channel(chanId.channelId())
                        .oldest(oldest)).get())
                .map(this::conversationsToText)
                .onFailure(error -> System.out.println("Error: " + error));

        if (messages.isFailure()) {
            throw new FailedTool("Messages could not be read");
        }

        if (messages.get().length() < MINIMUM_MESSAGE_LENGTH) {
            throw new FailedTool("Not enough messages found in channel " + parsedArgs.getChannel()
                    + System.lineSeparator() + System.lineSeparator()
                    + "* [Slack Channel](https://app.slack.com/client/" + channelDetails.get().teamId() + "/" + channelDetails.get().channelId() + ")");
        }

        final Try<String> messagesWithUsersReplaced = messages
                .flatMap(m -> replaceIds(client, parsedArgs.getAccessToken(), m));

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

        parsedArgs.setInputs(arguments, prompt, context);

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
                .map(ragDoc -> ollamaClient.callOllama(
                        ragDoc,
                        modelConfig.getCalculatedModel(context),
                        modelConfig.getCalculatedContextWindow()));

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(
                        API.Case(API.$(instanceOf(FailedTool.class)), throwable -> throwable),
                        API.Case(API.$(instanceOf(FailedOllama.class)), throwable -> throwable),
                        API.Case(API.$(), ex -> new FailedTool("Unexpected error", ex)))
                .get();
    }

    private RagDocumentContext<Void> getDocumentContext(final String document, final ChannelDetails channelDetails) {
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
class Arguments {

    @Inject
    @ConfigProperty(name = "sb.slack.accesstoken")
    private Optional<String> slackAccessToken;

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private Encryptor textEncryptor;

    private List<ToolArgs> arguments;

    private String prompt;

    private Map<String, String> context;

    public void setInputs(final List<ToolArgs> arguments, final String prompt, final Map<String, String> context) {
        this.arguments = arguments;
        this.prompt = prompt;
        this.context = context;
    }

    public String getChannel() {
        return argsAccessor.getArgument(arguments, "slackChannel", "").trim()
                .replaceFirst("^#", "");
    }

    public int getDays() {
        return Try.of(() -> Integer.parseInt(argsAccessor.getArgument(arguments, "days", "7")))
                .recover(throwable -> 7)
                .get();
    }

    public String getAccessToken() {
        return Try.of(() -> textEncryptor.decrypt(context.get("slack_access_token")))
                .recover(e -> context.get("slack_access_token"))
                .mapTry(Objects::requireNonNull)
                .recoverWith(e -> Try.of(() -> slackAccessToken.get()))
                .getOrElseThrow(() -> new FailedTool("Slack access token not found"));
    }
}
