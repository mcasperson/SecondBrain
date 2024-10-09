package secondbrain.tools;

import com.slack.api.Slack;
import com.slack.api.methods.response.conversations.ConversationsHistoryResponse;
import com.slack.api.model.Conversation;
import com.slack.api.model.Message;
import io.vavr.control.Try;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.client.ClientBuilder;
import org.jasypt.util.text.BasicTextEncryptor;
import org.jspecify.annotations.NonNull;
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
import java.util.Optional;

@Dependent
public class SlackChannel implements Tool {
    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private OllamaClient ollamaClient;

    @Override
    @NotNull
    public String getName() {
        return SlackChannel.class.getSimpleName();
    }

    @Override
    @NotNull
    public String getDescription() {
        return "Returns messages from a Slack channel";
    }

    @Override
    @NotNull
    public List<ToolArguments> getArguments() {
        return List.of(
                new ToolArguments("channel", "The Slack channel to read", "general"),
                new ToolArguments("days", "The number of days worth of messages to return", "7")
        );
    }

    @Override
    @NotNull
    public String call(
            @NotNull final Map<String, String> context,
            @NotNull final String prompt,
            @NotNull final List<ToolArgs> arguments) {
        final String channel = argsAccessor.getArgument(arguments, "channel", "").trim()
                .replaceFirst("^#", "");
        final int days = Try.of(() -> Integer.parseInt(argsAccessor.getArgument(arguments, "days", "7")))
                .recover(throwable -> 7)
                .get();

        final String oldest = Long.valueOf(LocalDateTime.now().minusDays(7).atZone(ZoneId.systemDefault()).toEpochSecond()).toString();

        final BasicTextEncryptor textEncryptor = new BasicTextEncryptor();
        textEncryptor.setPassword(System.getenv("ENCRYPTION_PASSWORD"));
        final String accessToken = textEncryptor.decrypt(context.get("access_token"));

        // you can get this instance via ctx.client() in a Bolt app
        var client = Slack.getInstance().methods();

        final Try<String> id = Try.of(() -> client.conversationsList(r -> r.token(accessToken)).getChannels())
                .map(channels -> channels.stream().filter(c -> c.getName().equals(channel)).map(Conversation::getId).findFirst())
                .map(Optional::get)
                .onFailure(error -> System.out.println("Error: " + error));

        if (id.isFailure()) {
            return "Channel " + channel + " not found";
        }


        final Try<String> messages = id
                .mapTry(chanId -> client.conversationsHistory(r -> r.token(accessToken).channel(chanId).oldest(oldest)))
                .map(this::conversationsToText)
                .onFailure(error -> System.out.println("Error: " + error));

        if (messages.isFailure()) {
            return "Messages could not be read";
        }

        final String messageContext = buildToolPrompt(messages.get(), prompt);

        return Try.of(() -> callOllama(messageContext))
                .map(OllamaResponse::response)
                .recover(throwable -> "Failed to call Ollama: " + throwable.getMessage())
                .get();

    }

    @NotNull
    public String buildToolPrompt(@NotNull final String context, @NonNull final String prompt) {
        return """
                <|begin_of_text|>
                <|start_header_id|>system<|end_header_id|>
                You are professional agent that understands Slack conversations.
                You are given the history of a Slack channel and asked to answer questions based on the messages provided.
                Here are the messages:
                """
                + context.substring(0, Math.min(context.length(), Constants.MAX_CONTEXT_LENGTH))
                + "<|eot_id|><|start_header_id|>user<|end_header_id|>"
                + prompt
                + "<|eot_id|><|start_header_id|>assistant<|end_header_id|>".stripLeading();
    }

    private String conversationsToText(@NotNull final ConversationsHistoryResponse conversation) {
        return conversation.getMessages()
                .stream()
                .map(Message::getText)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private OllamaResponse callOllama(@NotNull final String llmPrompt) {
        return ollamaClient.getTools(
                ClientBuilder.newClient(),
                new OllamaGenerateBody("llama3.2", llmPrompt, false));
    }
}
