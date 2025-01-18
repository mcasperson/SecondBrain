package secondbrain.domain.tools.slack;

import com.slack.api.Slack;
import com.slack.api.model.MatchedItem;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.config.ModelConfig;
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

@ApplicationScoped
public class SlackSearch implements Tool<MatchedItem> {
    private static final String INSTRUCTIONS = """
            You are professional agent that understands Slack conversations.
            You are given Slack search results and asked to answer questions based on the messages provided.
            """;

    @Inject
    private ModelConfig modelConfig;

    @Inject
    private SlackSearchArguments parsedArgs;

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

    public String getContextLabel() {
        return "Slack Messages";
    }

    @Override
    public List<RagDocumentContext<MatchedItem>> getContext(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        parsedArgs.setInputs(arguments, prompt, context);

        // you can get this instance via ctx.client() in a Bolt app
        var client = Slack.getInstance().methods();

        var searchResult = Try.of(() -> client.searchAll(r -> r.token(parsedArgs.getAccessToken()).query(String.join(" ", parsedArgs.getKeywords()))));

        if (searchResult.isFailure()) {
            throw new FailedTool("Could not search messages");
        }

        return searchResult.get()
                .getMessages()
                .getMatches()
                .stream()
                .map(this::getDocumentContext)
                .toList();

    }

    @Override
    public RagMultiDocumentContext<MatchedItem> call(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        final List<RagDocumentContext<MatchedItem>> contextList = getContext(context, prompt, arguments);

        final Try<RagMultiDocumentContext<MatchedItem>> result = Try.of(() -> mergeContext(contextList, context))
                .map(ragContext -> ragContext.updateDocument(
                        promptBuilderSelector.getPromptBuilder(modelConfig.getCalculatedModel(context)).buildFinalPrompt(
                                INSTRUCTIONS,
                                ragContext.getDocumentLeft(modelConfig.getCalculatedContextWindowChars()),
                                prompt)))
                .map(ragDoc -> ollamaClient.callOllama(
                        ragDoc,
                        modelConfig.getCalculatedModel(context),
                        modelConfig.getCalculatedContextWindow()));

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result
                .mapFailure(API.Case(API.$(), ex -> new FailedTool("Failed to call Ollama", ex)))
                .get();

    }

    private RagDocumentContext<MatchedItem> getDocumentContext(final MatchedItem meta) {
        return Try.of(() -> sentenceSplitter.splitDocument(meta.getText(), 10))
                .map(sentences -> new RagDocumentContext<MatchedItem>(
                        getContextLabel(),
                        meta.getText(),
                        sentences.stream()
                                .map(sentenceVectorizer::vectorize)
                                .collect(Collectors.toList()),
                        meta.getId(),
                        meta,
                        matchToUrl(meta)))
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                // If we can't vectorize the sentences, just return the document
                .recover(e -> new RagDocumentContext<>(getContextLabel(), meta.getText(), List.of(), meta.getId(), meta, null))
                .get();
    }

    private String matchToUrl(final MatchedItem matchedItem) {
        return "[" + StringUtils.substring(matchedItem.getText()
                        .replaceAll(":.*?:", "")
                        .replaceAll("[^A-Za-z0-9-._ ]", " "),
                0, 75) + "](" + matchedItem.getPermalink() + ")";
    }

    private RagMultiDocumentContext<MatchedItem> mergeContext(final List<RagDocumentContext<MatchedItem>> ragContext, Map<String, String> context) {
        return new RagMultiDocumentContext<>(
                ragContext.stream()
                        .map(ragDoc -> promptBuilderSelector.getPromptBuilder(modelConfig.getCalculatedModel(context)).buildContextPrompt("Slack Messages", ragDoc.document()))
                        .collect(Collectors.joining("\n")),
                ragContext);
    }
}

@ApplicationScoped
class SlackSearchArguments {
    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private Encryptor textEncryptor;

    @Inject
    private KeywordExtractor keywordExtractor;

    @Inject
    @ConfigProperty(name = "sb.slack.accesstoken")
    private Optional<String> slackAccessToken;

    private List<ToolArgs> arguments;

    private String prompt;

    private Map<String, String> context;

    public void setInputs(final List<ToolArgs> arguments, final String prompt, final Map<String, String> context) {
        this.arguments = arguments;
        this.prompt = prompt;
        this.context = context;
    }

    public Set<String> getKeywords() {
        final List<String> keywords = Stream.of(argsAccessor
                        .getArgument(arguments, "keywords", "")
                        .split(","))
                .map(String::trim)
                .toList();

        final List<String> keywordsGenerated = keywordExtractor.getKeywords(prompt);
        keywordsGenerated.addAll(keywords);

        return new HashSet<>(keywordsGenerated);
    }

    public String getAccessToken() {
        return Try.of(() -> textEncryptor.decrypt(context.get("slack_access_token")))
                .recover(e -> context.get("slack_access_token"))
                .mapTry(Objects::requireNonNull)
                .recoverWith(e -> Try.of(() -> slackAccessToken.get()))
                .getOrElseThrow(() -> new FailedTool("Slack access token not found"));
    }
}