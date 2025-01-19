package secondbrain.domain.tools.slackzengoogle;

import com.google.common.collect.ImmutableList;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.config.ModelConfig;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptions.EmptyContext;
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

import static com.google.common.base.Predicates.instanceOf;

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
            You will be penalized for referencing support tickets that were not explicitly provided.
            You will be penalized for referencing slack messages that were not explicitly provided.
            You will be penalized for referencing google documents that were not explicitly provided.
            You will be penalized for saying that you will monitor for tickets or messages in future.
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

        final List<ToolArgs> slackArguments = List.of(
                new ToolArgs("days", parsedArgs.getSlackDays()),
                new ToolArgs("slackChannel", parsedArgs.getSlackChannel())
        );

        final List<RagDocumentContext<Void>> slackContext = StringUtils.isBlank(parsedArgs.getSlackChannel())
                ? List.of()
                : Try.of(() -> slackChannel.getContext(context, prompt, slackArguments))
                    .recover(e -> List.of())
                    .get();

        final List<ToolArgs> googleArguments = List.of(
                new ToolArgs("googleDocumentId", parsedArgs.getGoogleDocumentId())
        );

        final List<RagDocumentContext<Void>> googleContext = StringUtils.isBlank(parsedArgs.getGoogleDocumentId())
                ? List.of()
                : Try.of(() -> googleDocs.getContext(context, prompt, googleArguments))
                    .recover(e -> List.of())
                    .get();

        final List<ToolArgs> zenArguments = List.of(
                new ToolArgs("zenDeskOrganization", parsedArgs.getGoogleDocumentId()),
                new ToolArgs("days", parsedArgs.getSlackDays())
        );

        final List<RagDocumentContext<Void>> zenContext = StringUtils.isBlank(parsedArgs.getZenDeskOrganization())
                ? List.of()
                : Try.of(() -> zenDeskOrganization.getContext(context, prompt, zenArguments))
                    .recover(e -> List.of())
                    .get()
                .stream()
                .map(RagDocumentContext::getRagDocumentContextVoid)
                .collect(ImmutableList.toImmutableList());

        if (slackContext.size() + zenContext.size() < parsedArgs.getMinSlackOrZen()) {
            throw new EmptyContext("No Slack message or ZenDesk tickets found");
        }

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
        return result.mapFailure(
                        API.Case(API.$(instanceOf(EmptyContext.class)),
                                throwable -> new FailedTool(throwable.getMessage())),
                        API.Case(API.$(),
                                throwable -> new FailedTool("Failed to process tickets, google doc, or slack messages: " + throwable.getMessage())))
                .get();
    }

    private RagMultiDocumentContext<Void> mergeContext(final List<RagDocumentContext<Void>> context, final String customModel) {
        return new RagMultiDocumentContext<>(
                context.stream()
                        .map(ragDoc ->
                                promptBuilderSelector.getPromptBuilder(customModel).buildContextPrompt(
                                    ragDoc.contextLabel(), ragDoc.document()))
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
    @ConfigProperty(name = "sb.slack.days")
    private Optional<String> slackDays;

    @Inject
    @ConfigProperty(name = "sb.google.doc")
    private Optional<String> googleDoc;

    @Inject
    @ConfigProperty(name = "sb.slackzengoogle.minslackorzen")
    private Optional<String> slackZenGoogleMinSlackOrZen;

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

    public int getMinSlackOrZen() {
        final String stringValue = argsAccessor.getArgument(
                slackZenGoogleMinSlackOrZen::get,
                arguments,
                context,
                "slackZenGoogleMinSlackOrZen",
                "slackzengoogle_minslackorzen",
                "");

        return NumberUtils.toInt(stringValue, 1);
    }

    public String getSlackDays() {
        return argsAccessor.getArgument(
                slackDays::get,
                arguments,
                context,
                "days",
                "slack_days",
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
