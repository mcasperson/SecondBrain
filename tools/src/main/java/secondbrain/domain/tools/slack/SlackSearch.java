package secondbrain.domain.tools.slack;

import com.slack.api.Slack;
import com.slack.api.model.MatchedItem;
import io.vavr.control.Try;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jasypt.util.text.BasicTextEncryptor;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.keyword.KeywordExtractor;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.ollama.OllamaGenerateBody;
import secondbrain.infrastructure.ollama.OllamaResponse;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Dependent
public class SlackSearch implements Tool {
    private static final String INSTRUCTIONS = """
            You are professional agent that understands Slack conversations.
            You are given Slack search results and asked to answer questions based on the messages provided.
            """;

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
    private KeywordExtractor keywordExtractor;

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private OllamaClient ollamaClient;

    @Inject
    private PromptBuilderSelector promptBuilderSelector;

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
                new ToolArguments("keywords", "Optional comma separated list of keywords defined in the prompt", "")
        );
    }

    @Override
    public String call(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        final List<String> keywords = Stream.of(argsAccessor
                        .getArgument(arguments, "keywords", "")
                        .split(","))
                .map(String::trim)
                .toList();

        final List<String> keywordsGenerated = keywordExtractor.getKeywords(prompt);
        keywordsGenerated.addAll(keywords);

        final Set<String> combinedKeywords = new HashSet<>(keywordsGenerated);

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

        var searchResult = Try.of(() -> client.searchAll(r -> r.token(accessToken.get()).query(String.join(" ", combinedKeywords))));

        if (searchResult.isFailure()) {
            return "Could not search messages";
        }

        var searchResults = searchResult.get()
                .getMessages()
                .getMatches()
                .stream()
                .map(MatchedItem::getText)
                .map(message -> promptBuilderSelector.getPromptBuilder(model).buildContextPrompt("Slack Messages", message))
                .collect(Collectors.joining("\n"));

        return Try.of(() -> promptBuilderSelector.getPromptBuilder(model).buildFinalPrompt(
                        INSTRUCTIONS,
                        searchResults,
                        prompt))
                .map(this::callOllama)
                .map(response -> response.response()
                        + System.lineSeparator() + System.lineSeparator()
                        + searchResult.get()
                        .getMessages()
                        .getMatches()
                        .stream()
                        .map(result -> "* [" + StringUtils.substring(result.getText()
                                        .replaceAll(":.*?:", "")
                                        .replaceAll("[^A-Za-z0-9-._ ]", " "),
                                0, 75) + "](" + result.getPermalink() + ")")
                        .collect(Collectors.joining("\n")))
                .recover(throwable -> "Failed to call Ollama: " + throwable.getMessage())
                .get();

    }

    private OllamaResponse callOllama(final String llmPrompt) {
        return Try.withResources(ClientBuilder::newClient)
                .of(client -> ollamaClient.getTools(
                        client,
                        new OllamaGenerateBody(model, llmPrompt, false))).get();
    }
}
