package secondbrain.domain.tools.slackzengoogle;

import com.google.common.collect.ImmutableList;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientBuilder;
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
import secondbrain.domain.yaml.YamlDeserializer;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.publicweb.PublicWebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * This meta-tool calls the zendesk, slack, and google tools to answer a prompt against multiple
 * entities defined in an external directory. The directory YAM looks like this:
 * <p>
 * entities:
 * - name: Entity1
 * zendesk: [1235484986222]
 * slack: [account-entity1]
 * googledocs: [2eoub28oeyb2o8yevb82oev2e]
 * - name: Entity2
 * zendesk: [789858675]
 * slack: [account-entity2]
 * googledocs: [789752yoyf2eo86fe2o86ef982o6ef]
 */
@ApplicationScoped
public class MultiSlackZenGoogle implements Tool<Void> {

    private static final String INSTRUCTIONS = """
            You are helpful agent.
            You are given the contents of a multiple Slack channels, Google Documents, and the help desk tickets from ZenDesk.
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

    @Inject
    private YamlDeserializer yamlDeserializer;

    @Inject
    private PublicWebClient publicWebClient;

    @Override
    public String getName() {
        return MultiSlackZenGoogle.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return """
                Queries an entity directory stored in a URL.
                Example queries include:
                * Load the entity directory from "https://example.org/directory.yaml" and find all references to the person John Smith.
                * Given the directory from "https://mysebsite.org/whatever" write a story about the use of the astro framework.
                """.stripIndent();
    }

    @Override
    public List<ToolArguments> getArguments() {
        return ImmutableList.of(new ToolArguments("url", "The entity directory URL", ""),
                new ToolArguments("days", "The number of days to query", ""));
    }

    public List<RagDocumentContext<Void>> getContext(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        final Arguments parsedArgs = Arguments.fromToolArgs(arguments, context, argsAccessor, validateString, model, contextWindow);

        final EntityDirectory entityDirectory = Try.withResources(ClientBuilder::newClient)
                .of(client -> publicWebClient.getDocument(client, parsedArgs.url()))
                .map(file -> yamlDeserializer.deserialize(file, EntityDirectory.class))
                .getOrElseThrow(ex -> new FailedTool("Failed to download or parse the entity directory", ex));

        return entityDirectory.entities()
                .stream()
                .flatMap(entity -> getEntityContext(entity, context, prompt, parsedArgs.days()).stream())
                .toList();
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
                .map(ragDoc -> ollamaClient.callOllama(ragDoc, parsedArgs.customModel(), parsedArgs.contextWindowValue()));

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(API.Case(API.$(), ex -> new FailedTool("Failed to call Ollama", ex)))
                .get();
    }

    /**
     * Try and get all the downstream context. Note that this tool is a long-running operation that attempts to access a lot
     * of data. We silently fail for any downstream context that could not be retrieved rather than fail the entire operation.
     */
    private List<RagDocumentContext<Void>> getEntityContext(final Entity entity, final Map<String, String> context, final String prompt, final int days) {
        final List<RagDocumentContext<Void>> slackContext = entity.slack()
                .stream()
                .filter(StringUtils::isNotBlank)
                .map(id -> List.of(new ToolArgs("slackChannel", id), new ToolArgs("days", "" + days)))
                // Some arguments require the value to be defined in the prompt to be considered valid, so we have to modify the prompt
                .flatMap(args -> Try.of(() -> slackChannel.getContext(context, prompt + "\nChannel is " + args.getFirst().argValue(), args))
                        .getOrElse(List::of)
                        .stream())
                .toList();

        final List<RagDocumentContext<Void>> googleContext = entity.googledocs()
                .stream()
                .filter(StringUtils::isNotBlank)
                .map(id -> List.of(new ToolArgs("googleDocumentId", id)))
                .flatMap(args -> Try.of(() -> googleDocs.getContext(context, prompt + "\nDocument ID is " + args.getFirst().argValue(), args))
                        .getOrElse(List::of)
                        .stream())
                .toList();

        final List<RagDocumentContext<Void>> zenContext = entity.zendesk()
                .stream()
                .filter(StringUtils::isNotBlank)
                .map(id -> List.of(new ToolArgs("zenDeskOrganization", id), new ToolArgs("days", "" + days)))
                .flatMap(args -> Try.of(() -> zenDeskOrganization.getContext(context, prompt + "\nOrganization is " + args.getFirst().argValue(), args))
                        .getOrElse(List::of)
                        .stream())
                .map(RagDocumentContext::getRagDocumentContextVoid)
                .toList();

        final List<RagDocumentContext<Void>> retValue = new ArrayList<>();
        retValue.addAll(slackContext);
        retValue.addAll(googleContext);
        retValue.addAll(zenContext);
        return retValue;
    }

    private RagMultiDocumentContext<Void> mergeContext(final List<RagDocumentContext<Void>> context, final String customModel) {
        return new RagMultiDocumentContext<>(
                context.stream()
                        .map(RagDocumentContext::document)
                        .map(content -> promptBuilderSelector.getPromptBuilder(customModel).buildContextPrompt("External Data", content))
                        .collect(Collectors.joining("\n")),
                context);
    }

    record Arguments(String url, int days, String customModel, @Nullable Integer contextWindowValue,
                     int contextWindowChars) {
        public static Arguments fromToolArgs(final List<ToolArgs> arguments, final Map<String, String> context, final ArgsAccessor argsAccessor, final ValidateString validateString, final String model, final Optional<String> contextWindow) {
            final String url = argsAccessor.getArgument(arguments, "url", "").trim();

            final String customModel = Try.of(() -> context.get("custom_model"))
                    .mapTry(validateString::throwIfEmpty)
                    .recover(e -> model)
                    .get();

            final Integer contextWindowValue = Try.of(contextWindow::get)
                    .map(Integer::parseInt)
                    .recover(e -> Constants.DEFAULT_CONTENT_WINDOW)
                    .get();

            final int days = Try.of(() -> Integer.parseInt(argsAccessor.getArgument(arguments, "days", "0")))
                    .recover(throwable -> 0)
                    .map(i -> Math.max(0, i))
                    .get();

            final int contextWindowChars = contextWindowValue == null
                    ? Constants.DEFAULT_MAX_CONTEXT_LENGTH
                    : (int) (contextWindowValue * Constants.CONTENT_WINDOW_BUFFER * Constants.CHARACTERS_PER_TOKEN);

            return new Arguments(url, days, customModel, contextWindowValue, contextWindowChars);
        }
    }

    record EntityDirectory(List<Entity> entities) {
    }

    record Entity(String name, List<String> zendesk, List<String> slack, List<String> googledocs) {
    }
}
