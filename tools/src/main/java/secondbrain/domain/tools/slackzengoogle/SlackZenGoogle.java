package secondbrain.domain.tools.slackzengoogle;

import com.google.common.collect.ImmutableList;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.config.ModelConfig;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptions.FailedTool;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.tools.googledocs.GoogleDocs;
import secondbrain.domain.tools.slack.SlackChannel;
import secondbrain.domain.tools.zendesk.ZenDeskOrganization;
import secondbrain.infrastructure.ollama.OllamaClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * This is an example of a meta-tool that calls multiple child tools to get an answer. In this case, it
 * queries a Slack channel, a Google Document, and a Zen Desk organization channel to answer a prompt.
 */
@ApplicationScoped
public class SlackZenGoogle implements Tool<Void> {

    private static final String INSTRUCTIONS = """
            You are helpful agent.
            You are given the contents of a Slack channel, a Google Document, and the help desk tickets from ZenDesk.
            You must answer the prompt based on the information provided.
            """;

    @Inject
    private ModelConfig modelConfig;

    @Inject
    private SlackChannel slackChannel;

    @Inject
    private GoogleDocs googleDocs;

    @Inject
    private ZenDeskOrganization zenDeskOrganization;

    @Inject
    private OllamaClient ollamaClient;

    @Inject
    private PromptBuilderSelector promptBuilderSelector;

    @Inject
    private SlackZenGoogleArguments parsedArgs;

    @Override
    public String getName() {
        return SlackZenGoogle.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return """
                Queries a Slack channel, a Google Document, and a Zen Desk organization channel.
                Example queries include:
                * Given a slack channel "#team-acme", a Google Document "1234567890", and a Zen Desk organization "908636412", find all references to the person John Smith.
                * Given a slack channel "#project-astro" and a Zen Desk organization "23863247863", write a story about the use of the astro framework.
                """.stripIndent();
    }

    @Override
    public List<ToolArguments> getArguments() {
        return ImmutableList.of(new ToolArguments("days", "The optional number of days to include in the searches", ""),
                new ToolArguments("slackChannel", "The optional Slack channel to query", ""),
                new ToolArguments("googleDocumentId", "The optional Google Document to query", ""),
                new ToolArguments("zenDeskOrganization", "The optional Zen Desk organization channel to query", ""));
    }

    public List<RagDocumentContext<Void>> getContext(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        parsedArgs.setInputs(arguments, prompt, context);

        final List<ToolArgs> slackArguments = arguments.stream()
                .filter(arg -> arg.argName().equals("slackChannel") || arg.argName().equals("days"))
                .collect(ImmutableList.toImmutableList());

        final List<RagDocumentContext<Void>> slackContext = StringUtils.isBlank(parsedArgs.getSlackChannel())
                ? List.of() : slackChannel.getContext(context, prompt, slackArguments);

        final List<ToolArgs> googleArguments = arguments.stream()
                .filter(arg -> arg.argName().equals("googleDocumentId"))
                .collect(ImmutableList.toImmutableList());

        final List<RagDocumentContext<Void>> googleContext = StringUtils.isBlank(parsedArgs.getGoogleDocumentId())
                ? List.of() : googleDocs.getContext(context, prompt, googleArguments);

        final List<ToolArgs> zenArguments = arguments.stream()
                .filter(arg -> arg.argName().equals("zenDeskOrganization") || arg.argName().equals("days"))
                .collect(ImmutableList.toImmutableList());

        final List<RagDocumentContext<Void>> zenContext = StringUtils.isBlank(parsedArgs.getZenDeskOrganization())
                ? List.of() : zenDeskOrganization.getContext(context, prompt, zenArguments)
                .stream()
                .map(RagDocumentContext::getRagDocumentContextVoid)
                .collect(ImmutableList.toImmutableList());


        final List<RagDocumentContext<Void>> retValue = new ArrayList<>();
        retValue.addAll(slackContext);
        retValue.addAll(googleContext);
        retValue.addAll(zenContext);
        return retValue;
    }

    @Override
    public RagMultiDocumentContext<Void> call(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> getContext(context, prompt, arguments))
                .map(ragDoc -> mergeContext(ragDoc, modelConfig.getCalculatedModel(context)))
                .map(ragContext -> ragContext.updateDocument(promptBuilderSelector
                        .getPromptBuilder(modelConfig.getCalculatedModel(context))
                        .buildFinalPrompt(
                                INSTRUCTIONS,
                                // I've opted to get the end of the document if it is larger than the context window.
                                // The end of the document is typically given more weight by LLMs, and so any long
                                // document being processed should place the most relevant content towards the end.
                                ragContext.getDocumentRight(modelConfig.getCalculatedContextWindowChars()),
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

    private RagMultiDocumentContext<Void> mergeContext(final List<RagDocumentContext<Void>> context, final String customModel) {
        return new RagMultiDocumentContext<>(
                context.stream()
                        .map(RagDocumentContext::document)
                        .map(content -> promptBuilderSelector.getPromptBuilder(customModel).buildContextPrompt("External Data", content))
                        .collect(Collectors.joining("\n")),
                context);
    }
}

@ApplicationScoped
class SlackZenGoogleArguments {
    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    @ConfigProperty(name = "sb.slack.channel")
    private Optional<String> slackChannel;

    @Inject
    @ConfigProperty(name = "sb.google.doc")
    private Optional<String> googleDoc;

    @Inject
    @ConfigProperty(name = "sb.zendesk.organization")
    private Optional<String> zenDeskOrganization;

    private List<ToolArgs> arguments;

    private String prompt;

    private Map<String, String> context;

    public void setInputs(final List<ToolArgs> arguments, final String prompt, final Map<String, String> context) {
        this.arguments = arguments;
        this.prompt = prompt;
        this.context = context;
    }

    public String getSlackChannel() {
        return argsAccessor.getArgument(
                slackChannel::get,
                arguments,
                context,
                "slackChannel",
                "slack_channel",
                "");
    }

    public String getGoogleDocumentId() {
        return argsAccessor.getArgument(
                googleDoc::get,
                arguments,
                context,
                "googleDocumentId",
                "google_document_id",
                "");
    }

    public String getZenDeskOrganization() {
        return argsAccessor.getArgument(
                zenDeskOrganization::get,
                arguments,
                context,
                "zenDeskOrganization",
                "zendesk_organization",
                "");
    }

}
