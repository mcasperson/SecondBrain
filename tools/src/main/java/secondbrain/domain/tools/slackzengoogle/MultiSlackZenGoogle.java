package secondbrain.domain.tools.slackzengoogle;

import com.google.common.collect.ImmutableList;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientBuilder;
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
 *   zendesk: [1235484986222]
 *   slack: [account-entity1]
 *   googledocs: [2eoub28oeyb2o8yevb82oev2e]
 * - name: Entity2
 *   zendesk: [789858675]
 *   slack: [account-entity2]
 *   googledocs: [789752yoyf2eo86fe2o86ef982o6ef]
 */
@ApplicationScoped
public class MultiSlackZenGoogle implements Tool<Void> {

    private static final String INSTRUCTIONS = """
            You are helpful agent.
            You are given the contents of a multiple Slack channels, Google Documents, and the help desk tickets from ZenDesk.
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
    private YamlDeserializer yamlDeserializer;

    @Inject
    private PublicWebClient publicWebClient;

    @Inject
    private Arguments parsedArgs;

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
        return ImmutableList.of(
                new ToolArguments("url", "The entity directory URL", ""),
                new ToolArguments("entityName", "The optional name of the entity to query", ""),
                new ToolArguments("days", "The number of days to query", ""));
    }

    public List<RagDocumentContext<Void>> getContext(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        parsedArgs.setInputs(arguments, prompt, context);

        final EntityDirectory entityDirectory = Try.withResources(ClientBuilder::newClient)
                .of(client -> publicWebClient.getDocument(client, parsedArgs.getUrl()))
                .map(file -> yamlDeserializer.deserialize(file, EntityDirectory.class))
                .getOrElseThrow(ex -> new FailedTool("Failed to download or parse the entity directory", ex));

        return entityDirectory.entities()
                .stream()
                .filter(entity -> StringUtils.isBlank(parsedArgs.getEntityName()) || entity.name().equalsIgnoreCase(parsedArgs.getEntityName()))
                .flatMap(entity -> getEntityContext(entity, context, prompt, parsedArgs.getDays()).stream())
                .toList();
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
                // The context label is updated to include the entity name
                .map(ragDoc -> ragDoc.updateContextLabel(entity.name() + " " + ragDoc.contextLabel()))
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
                        .map(ragDoc ->
                                promptBuilderSelector.getPromptBuilder(customModel).buildContextPrompt(
                                        ragDoc.contextLabel(), ragDoc.document()))
                        .collect(Collectors.joining("\n")),
                context);
    }

    record EntityDirectory(List<Entity> entities) {
    }

    record Entity(String name, List<String> zendesk, List<String> slack, List<String> googledocs) {
    }
}

@ApplicationScoped
class Arguments {
    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.url")
    private Optional<String> url;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.entity")
    private Optional<String> entity;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.days")
    private Optional<String> days;

    private List<ToolArgs> arguments;

    private String prompt;

    private Map<String, String> context;

    public void setInputs(final List<ToolArgs> arguments, final String prompt, final Map<String, String> context) {
        this.arguments = arguments;
        this.prompt = prompt;
        this.context = context;
    }

    public String getUrl() {
        return argsAccessor.getArgument(
                url::get,
                arguments,
                context,
                "url",
                "multislackzengoogle_url",
                "");
    }

    public int getDays() {
        final String stringValue = argsAccessor.getArgument(
                days::get,
                arguments,
                context,
                "days",
                "multislackzengoogle_days",
                "0");

        return Try.of(() -> Integer.parseInt(stringValue))
                .recover(throwable -> 0)
                .map(i -> Math.max(0, i))
                .get();
    }

    public String getEntityName() {
        return argsAccessor.getArgument(
                entity::get,
                arguments,
                context,
                "entityName",
                "multislackzengoogle_entityname",
                "");
    }
}
