package secondbrain.domain.tools.slack;

import com.slack.api.Slack;
import com.slack.api.model.MatchedItem;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.context.SentenceSplitter;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptions.FailedTool;
import secondbrain.domain.keyword.KeywordExtractor;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.infrastructure.ollama.OllamaClient;

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
    @ConfigProperty(name = "sb.slack.accesstoken")
    Optional<String> slackAccessToken;

    @Inject
    private Encryptor textEncryptor;

    @Inject
    private KeywordExtractor keywordExtractor;

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private OllamaClient ollamaClient;

    @Inject
    private PromptBuilderSelector promptBuilderSelector;

    @Inject
    private SentenceSplitter sentenceSplitter;

    @Inject
    private SentenceVectorizer sentenceVectorizer;

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
    public RagMultiDocumentContext<?> call(
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

        // Try to decrypt the value, otherwise assume it is a plain text value, and finally
        // fall back to the value defined in the local configuration.
        final Try<String> accessToken = Try.of(() -> textEncryptor.decrypt(context.get("slack_access_token")))
                .recover(e -> context.get("slack_access_token"))
                .mapTry(Objects::requireNonNull)
                .recoverWith(e -> Try.of(() -> slackAccessToken.get()));

        if (accessToken.isFailure()) {
            throw new FailedTool("Slack access token not found");
        }

        // you can get this instance via ctx.client() in a Bolt app
        var client = Slack.getInstance().methods();

        var searchResult = Try.of(() -> client.searchAll(r -> r.token(accessToken.get()).query(String.join(" ", combinedKeywords))));

        if (searchResult.isFailure()) {
            throw new FailedTool("Could not search messages");
        }

        final List<RagDocumentContext<MatchedItem>> searchResultContext = searchResult.get()
                .getMessages()
                .getMatches()
                .stream()
                .map(this::getDocumentContext)
                .map(ragDoc -> ragDoc.updateDocument(promptBuilderSelector.getPromptBuilder(model).buildContextPrompt("Slack Messages", ragDoc.document())))
                .toList();

        final Try<RagMultiDocumentContext<MatchedItem>> result = Try.of(() -> mergeContext(searchResultContext))
                .map(ragContext -> ragContext.updateDocument(
                        promptBuilderSelector.getPromptBuilder(model).buildFinalPrompt(
                                INSTRUCTIONS,
                                ragContext.combinedDocument(),
                                prompt)))
                .map(ragDoc -> ollamaClient.callOllama(ragDoc, model));

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result
                .mapFailure(API.Case(API.$(), ex -> new FailedTool("Failed to call Ollama", ex)))
                .get();

    }

    private RagDocumentContext<MatchedItem> getDocumentContext(final MatchedItem meta) {
        return Try.of(() -> sentenceSplitter.splitDocument(meta.getText(), 10))
                .map(sentences -> new RagDocumentContext<MatchedItem>(
                        meta.getText(),
                        sentences.stream()
                                .map(sentenceVectorizer::vectorize)
                                .collect(Collectors.toList()),
                        meta.getId(),
                        meta,
                        matchToUrl(meta)))
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                // If we can't vectorize the sentences, just return the document
                .recover(e -> new RagDocumentContext<>(meta.getText(), List.of(), meta.getId(), meta, null))
                .get();
    }

    private String matchToUrl(final MatchedItem matchedItem) {
        return "* [" + StringUtils.substring(matchedItem.getText()
                        .replaceAll(":.*?:", "")
                        .replaceAll("[^A-Za-z0-9-._ ]", " "),
                0, 75) + "](" + matchedItem.getPermalink() + ")";
    }

    private RagMultiDocumentContext<MatchedItem> mergeContext(final List<RagDocumentContext<MatchedItem>> context) {
        return new RagMultiDocumentContext<>(
                context.stream()
                        .map(RagDocumentContext::document)
                        .collect(Collectors.joining("\n")),
                context);
    }
}
