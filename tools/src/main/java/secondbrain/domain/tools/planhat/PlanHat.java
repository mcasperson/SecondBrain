package secondbrain.domain.tools.planhat;

import com.google.common.collect.ImmutableList;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.config.ModelConfig;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.context.SentenceSplitter;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.converter.HtmlToText;
import secondbrain.domain.exceptions.FailedTool;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.planhat.Conversation;
import secondbrain.infrastructure.planhat.PlanHatClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@ApplicationScoped
public class PlanHat implements Tool<Void> {
    private static final String INSTRUCTIONS = """
            You are a helpful assistant.
            You are given list of conversations and a question related to the conversations.
            You must assume the information required to answer the question is present in the conversations.
            You must answer the question based on the conversations provided.
            You will be tipped $1000 for answering the question directly from the conversation.
            When the user asks a question indicating that they want to know about conversation, you must generate the answer based on the conversation.
            You will be penalized for answering that the conversation can not be accessed.
            """.stripLeading();

    @Inject
    private Arguments parsedArgs;

    @Inject
    private PlanHatClient planHatClient;

    @Inject
    private SentenceSplitter sentenceSplitter;

    @Inject
    private SentenceVectorizer sentenceVectorizer;

    @Inject
    private ModelConfig modelConfig;

    @Inject
    private PromptBuilderSelector promptBuilderSelector;

    @Inject
    private OllamaClient ollamaClient;

    @Inject
    private HtmlToText htmlToText;

    @Override
    public String getName() {
        return PlanHat.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Queries PlanHat for customer information, activities, emails, and conversations";
    }

    public String getContextLabel() {
        return "PlanHat Activity";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return ImmutableList.of(new ToolArguments(
                        "companyId",
                        "The company ID to query",
                        ""),
                new ToolArguments(
                        "from",
                        "The date to query from",
                        ""),
                new ToolArguments(
                        "to",
                        "The date to query to",
                        ""));
    }

    @Override
    public List<RagDocumentContext<Void>> getContext(Map<String, String> context, String prompt, List<ToolArgs> arguments) {
        parsedArgs.setInputs(arguments, prompt, context);

        if (StringUtils.isBlank(parsedArgs.getCompany())) {
            throw new FailedTool("You must provide a company ID to query");
        }

        final List<Conversation> conversations = Try.withResources(ClientBuilder::newClient)
                .of(client -> planHatClient.getConversations(
                        client,
                        parsedArgs.getCompany(),
                        parsedArgs.getFrom(),
                        parsedArgs.getTo(),
                        parsedArgs.getToken()))
                .get();

        return conversations.stream()
                .map(this::getDocumentContext)
                .collect(Collectors.toList());
    }

    @Override
    public RagMultiDocumentContext<Void> call(Map<String, String> context, String prompt, List<ToolArgs> arguments) {
        final List<RagDocumentContext<Void>> contextList = getContext(context, prompt, arguments);

        parsedArgs.setInputs(arguments, prompt, context);

        if (StringUtils.isBlank(parsedArgs.getCompany())) {
            throw new FailedTool("You must provide a company to query");
        }

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> contextList)
                .map(ragDoc -> mergeContext(ragDoc, modelConfig.getCalculatedModel(context)))
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
        return result.mapFailure(API.Case(API.$(), ex -> new FailedTool("Failed to call Ollama", ex)))
                .get();
    }

    private RagDocumentContext<Void> getDocumentContext(final Conversation conversation) {
        return Try.of(() -> htmlToText.getText(conversation.getContent()))
                .map(text -> sentenceSplitter.splitDocument(text, 10))
                .map(sentences -> new RagDocumentContext<Void>(
                        getContextLabel(),
                        conversation.getContent(),
                        sentences.stream()
                                .map(sentenceVectorizer::vectorize)
                                .collect(Collectors.toList())))
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                // If we can't vectorize the sentences, just return the document
                .recover(e -> new RagDocumentContext<>(getContextLabel(), conversation.getContent(), List.of()))
                .get();
    }

    private RagMultiDocumentContext<Void> mergeContext(final List<RagDocumentContext<Void>> context, final String customModel) {
        return new RagMultiDocumentContext<>(
                context.stream()
                        .map(ragDoc -> promptBuilderSelector
                                .getPromptBuilder(customModel)
                                .buildContextPrompt(
                                        ragDoc.contextLabel(),
                                        ragDoc.document()))
                        .collect(Collectors.joining("\n")),
                context);
    }
}

@ApplicationScoped
class Arguments {
    @Inject
    @ConfigProperty(name = "sb.planhat.company")
    private Optional<String> company;

    @Inject
    @ConfigProperty(name = "sb.planhat.from")
    private Optional<String> from;

    @Inject
    @ConfigProperty(name = "sb.planhat.to")
    private Optional<String> to;

    @Inject
    @ConfigProperty(name = "sb.planhat.accesstoken")
    private Optional<String> token;

    @Inject
    private ValidateString validateString;

    @Inject
    private ArgsAccessor argsAccessor;

    private List<ToolArgs> arguments;

    private String prompt;

    private Map<String, String> context;

    public void setInputs(final List<ToolArgs> arguments, final String prompt, final Map<String, String> context) {
        this.arguments = arguments;
        this.prompt = prompt;
        this.context = context;
    }

    public String getCompany() {
        return Try.of(company::get)
                .mapTry(validateString::throwIfEmpty)
                .recover(e -> argsAccessor.getArgument(arguments, "companyId", ""))
                .mapTry(validateString::throwIfEmpty)
                .recover(e -> context.get("planhat_company"))
                .mapTry(validateString::throwIfEmpty)
                .recover(e -> "")
                .get();
    }

    public String getFrom() {
        return Try.of(from::get)
                .mapTry(validateString::throwIfEmpty)
                .recover(e -> argsAccessor.getArgument(arguments, "from", ""))
                .mapTry(validateString::throwIfEmpty)
                .recover(e -> context.get("planhat_from"))
                .mapTry(validateString::throwIfEmpty)
                .recover(e -> "")
                .get();
    }

    public String getTo() {
        return Try.of(to::get)
                .mapTry(validateString::throwIfEmpty)
                .recover(e -> argsAccessor.getArgument(arguments, "to", ""))
                .mapTry(validateString::throwIfEmpty)
                .recover(e -> context.get("planhat_to"))
                .mapTry(validateString::throwIfEmpty)
                .recover(e -> "")
                .get();
    }

    public String getToken() {
        return Try.of(token::get)
                .mapTry(validateString::throwIfEmpty)
                .recover(e -> context.get("planhat_token"))
                .mapTry(validateString::throwIfEmpty)
                .recover(e -> "")
                .get();
    }
}
