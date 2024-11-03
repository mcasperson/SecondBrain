package secondbrain.domain.tools.slack;

import com.slack.api.Slack;
import com.slack.api.model.MatchedItem;
import io.vavr.control.Try;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.lang3.StringUtils;
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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Dependent
public class SlackSearch implements Tool {
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
        return SlackSearch.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Searches Slack for messages that match the prompt";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return List.of(
                new ToolArguments("keywords", "The number of days worth of messages to return", "")
        );
    }

    @Override
    public String call(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {
        
        final String keywordsRaw = argsAccessor.getArgument(arguments, "keywords", "").trim();

        if (StringUtils.isBlank(keywordsRaw)) {
            return "No keywords provided";
        }

        final String[] keywords = keywordsRaw.split(",");

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

        var searchResult = Try.of(() -> client.searchAll(r -> r.token(accessToken.get()).query(String.join(" ", keywords))));

        if (searchResult.isFailure()) {
            return "Could not search messages";
        }

        var searchResults = searchResult.get()
                .getMessages()
                .getMatches()
                .stream()
                .map(MatchedItem::getText)
                .collect(Collectors.joining("\n"));

        final String messageContext = buildToolPrompt(searchResults, prompt);

        return Try.of(() -> callOllama(messageContext))
                .map(response -> response.response()
                        + System.lineSeparator() + System.lineSeparator()
                        + searchResult.get()
                        .getMessages()
                        .getMatches()
                        .stream()
                        .map(result -> "* [Slack Message](" + result.getPermalink() + ")")
                        .collect(Collectors.joining("\n")))
                .recover(throwable -> "Failed to call Ollama: " + throwable.getMessage())
                .get();

    }


    private String buildToolPrompt(final String context, final String prompt) {
        return """
                <|begin_of_text|>
                <|start_header_id|>system<|end_header_id|>
                You are professional agent that understands Slack conversations.
                You are given Slack search results and asked to answer questions based on the messages provided.
                Here are the messages:
                """
                + context.substring(0, Math.min(context.length(), NumberUtils.toInt(limit, Constants.MAX_CONTEXT_LENGTH)))
                + "<|eot_id|><|start_header_id|>user<|end_header_id|>"
                + prompt
                + "<|eot_id|><|start_header_id|>assistant<|end_header_id|>".stripLeading();
    }


    private OllamaResponse callOllama(final String llmPrompt) {
        return Try.withResources(ClientBuilder::newClient)
                .of(client -> ollamaClient.getTools(
                        client,
                        new OllamaGenerateBody(model, llmPrompt, false))).get();
    }
}
