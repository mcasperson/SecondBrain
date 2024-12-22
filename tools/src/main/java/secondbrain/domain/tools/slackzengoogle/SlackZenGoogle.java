package secondbrain.domain.tools.slackzengoogle;

import com.google.common.collect.ImmutableList;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.constants.Constants;
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
import secondbrain.domain.validate.ValidateString;
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
    @ConfigProperty(name = "sb.ollama.model", defaultValue = "llama3.2")
    private String model;

    @Inject
    @ConfigProperty(name = "sb.ollama.contextwindow")
    private Optional<String> contextWindow;

    @Inject
    private ArgsAccessor argsAccessor;

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
    private ValidateString validateString;

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

        final Arguments parsedArgs = Arguments.fromToolArgs(arguments, context, argsAccessor, validateString, model, contextWindow);

        final List<ToolArgs> slackArguments = arguments.stream()
                .filter(arg -> arg.argName().equals("slackChannel") || arg.argName().equals("days"))
                .collect(ImmutableList.toImmutableList());

        final List<RagDocumentContext<Void>> slackContext = StringUtils.isBlank(parsedArgs.slackChannel())
                ? List.of() : slackChannel.getContext(context, prompt, slackArguments);

        final List<ToolArgs> googleArguments = arguments.stream()
                .filter(arg -> arg.argName().equals("googleDocumentId"))
                .collect(ImmutableList.toImmutableList());

        final List<RagDocumentContext<Void>> googleContext = StringUtils.isBlank(parsedArgs.googleDocumentId())
                ? List.of() : googleDocs.getContext(context, prompt, googleArguments);

        final List<ToolArgs> zenArguments = arguments.stream()
                .filter(arg -> arg.argName().equals("zenDeskOrganization") || arg.argName().equals("days"))
                .collect(ImmutableList.toImmutableList());

        final List<RagDocumentContext<Void>> zenContext = StringUtils.isBlank(parsedArgs.zenDeskOrganization())
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

        final Arguments parsedArgs = Arguments.fromToolArgs(arguments, context, argsAccessor, validateString, model, contextWindow);

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> getContext(context, prompt, arguments))
                .map(ragDoc -> mergeContext(ragDoc, parsedArgs.customModel()))
                .map(ragContext -> ragContext.updateDocument(promptBuilderSelector
                        .getPromptBuilder(parsedArgs.customModel())
                        .buildFinalPrompt(
                                INSTRUCTIONS,
                                // I've opted to get the end of the document if it is larger than the context window.
                                // The end of the document is typically given more weight by LLMs, and so any long
                                // document being processed should place the most relevant content towards the end.
                                ragContext.getDocumentRight(parsedArgs.contextWindowChars()),
                                prompt)))
                .map(ragDoc -> ollamaClient.callOllama(ragDoc, parsedArgs.customModel(), parsedArgs.contextWindow()));

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

    record Arguments(String slackChannel, String googleDocumentId, String zenDeskOrganization, String customModel,
                     @Nullable Integer contextWindow, int contextWindowChars) {
        public static Arguments fromToolArgs(final List<ToolArgs> arguments, final Map<String, String> context, final ArgsAccessor argsAccessor, final ValidateString validateString, final String model, final Optional<String> contextWindow) {
            final String slackChannel = argsAccessor.getArgument(arguments, "slackChannel", "").trim();
            final String googleDocumentId = argsAccessor.getArgument(arguments, "googleDocumentId", "").trim();
            final String zenDeskOrganization = argsAccessor.getArgument(arguments, "zenDeskOrganization", "").trim();

            final String customModel = Try.of(() -> context.get("custom_model"))
                    .mapTry(validateString::throwIfEmpty)
                    .recover(e -> model)
                    .get();

            final Integer contextWindowValue = Try.of(contextWindow::get)
                    .map(Integer::parseInt)
                    .recover(e -> null)
                    .get();

            final int contextWindowChars = contextWindowValue == null
                    ? Constants.MAX_CONTEXT_LENGTH
                    : (int) (contextWindowValue * Constants.CONTENT_WINDOW_BUFFER * Constants.CHARACTERS_PER_TOKEN);

            return new Arguments(slackChannel, googleDocumentId, zenDeskOrganization, customModel, contextWindowValue, contextWindowChars);
        }
    }
}
