package secondbrain.domain.tools.slackzengoogle;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.collect.ImmutableList;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.config.ModelConfig;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptionhandling.ExceptionHandler;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.InsufficientContext;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.reader.FileReader;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.tools.googledocs.GoogleDocs;
import secondbrain.domain.tools.planhat.PlanHat;
import secondbrain.domain.tools.slack.SlackChannel;
import secondbrain.domain.tools.slack.SlackSearch;
import secondbrain.domain.tools.zendesk.ZenDeskOrganization;
import secondbrain.domain.yaml.YamlDeserializer;
import secondbrain.infrastructure.ollama.OllamaClient;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static com.google.common.base.Predicates.instanceOf;

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

    public static final String MULTI_SLACK_ZEN_GOOGLE_DISABLELINKS = "disableLinks";
    public static final String MULTI_SLACK_ZEN_KEYWORD_ARG = "keywords";
    public static final String MULTI_SLACK_ZEN_URL_ARG = "url";
    public static final String MULTI_SLACK_ZEN_DAYS_ARG = "days";
    public static final String MULTI_SLACK_ZEN_ENTITY_NAME_ARG = "entityName";
    public static final String MULTI_SLACK_ZEN_MAX_ENTITIES_ARG = "maxEntities";

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
    private SlackSearch slackSearch;

    @Inject
    private GoogleDocs googleDocs;

    @Inject
    private PlanHat planHat;

    @Inject
    private ZenDeskOrganization zenDeskOrganization;

    @Inject
    private OllamaClient ollamaClient;

    @Inject
    private PromptBuilderSelector promptBuilderSelector;

    @Inject
    private YamlDeserializer yamlDeserializer;

    @Inject
    private FileReader fileReader;

    @Inject
    private Arguments parsedArgs;

    @Inject
    private ExceptionHandler exceptionHandler;

    @Inject
    private Logger log;
    @Inject
    private Logger logger;

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
                new ToolArguments(MULTI_SLACK_ZEN_URL_ARG, "The entity directory URL", ""),
                new ToolArguments(MULTI_SLACK_ZEN_KEYWORD_ARG, "The keywords to limit the child context to", ""),
                new ToolArguments(MULTI_SLACK_ZEN_KEYWORD_ARG, "The window size around any matching keywords", ""),
                new ToolArguments(MULTI_SLACK_ZEN_ENTITY_NAME_ARG, "The optional name of the entity to query", ""),
                new ToolArguments(MULTI_SLACK_ZEN_MAX_ENTITIES_ARG, "The optional maximum number of entities to process", "0"),
                new ToolArguments(MULTI_SLACK_ZEN_DAYS_ARG, "The number of days to query", ""));
    }

    @Override
    public List<RagDocumentContext<Void>> getContext(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        parsedArgs.setInputs(arguments, prompt, context);

        final EntityDirectory entityDirectory = Try.of(() -> fileReader.read(parsedArgs.getUrl()))
                .map(file -> yamlDeserializer.deserialize(file, EntityDirectory.class))
                .getOrElseThrow(ex -> new ExternalFailure("Failed to download or parse the entity directory", ex));

        final List<RagDocumentContext<Void>> ragContext = entityDirectory.getEntities()
                .parallelStream()
                .filter(entity -> parsedArgs.getEntityName().isEmpty() || parsedArgs.getEntityName().contains(entity.name().toLowerCase()))
                .limit(parsedArgs.getMaxEntities() == 0 ? Long.MAX_VALUE : parsedArgs.getMaxEntities())
                .flatMap(entity -> getEntityContext(entity, context, prompt, parsedArgs.getDays()).stream())
                .toList();

        return validateSufficientContext(ragContext);
    }

    @Override
    public RagMultiDocumentContext<Void> call(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> getContext(context, prompt, arguments))
                .map(ragContext -> mergeContext(ragContext, modelConfig.getCalculatedModel(context)))
                .map(multiRagDoc -> multiRagDoc.updateDocument(
                        promptBuilderSelector
                                .getPromptBuilder(modelConfig.getCalculatedModel(context))
                                .buildFinalPrompt(getInstructions(multiRagDoc),
                                        // I've opted to get the end of the document if it is larger than the context window.
                                        // The end of the document is typically given more weight by LLMs, and so any long
                                        // document being processed should place the most relevant content towards the end.
                                        multiRagDoc.getDocumentRight(modelConfig.getCalculatedContextWindowChars()),
                                        prompt)))
                .map(ragDoc -> ollamaClient.callOllamaWithCache(
                        ragDoc,
                        modelConfig.getCalculatedModel(context),
                        getName(),
                        modelConfig.getCalculatedContextWindow()))
                /*
                    InsufficientContext is expected when there is not enough information to answer the prompt.
                    It is not passed up though, as it is not a failure, but rather a lack of information.
                 */
                .recover(InsufficientContext.class, e -> new RagMultiDocumentContext<>(
                        e.getClass().getSimpleName() + ": No ZenDesk tickets, Slack messages, or PlanHat activities found."));

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(
                        API.Case(API.$(instanceOf(EmptyString.class)), throwable -> new InternalFailure("Some content was empty (this is probably a bug...)")),
                        API.Case(API.$(instanceOf(InternalFailure.class)), throwable -> throwable),
                        API.Case(API.$(), ex -> new ExternalFailure(getName() + " failed to call Ollama", ex)))
                .get();
    }

    private String getInstructions(final RagMultiDocumentContext<Void> multiRagDoc) {
        return INSTRUCTIONS
                + getAdditionalSlackInstructions(multiRagDoc.individualContexts())
                + getAdditionalPlanHatInstructions(multiRagDoc.individualContexts())
                + getAdditionalGoogleDocsInstructions(multiRagDoc.individualContexts())
                + getAdditionalZenDeskInstructions(multiRagDoc.individualContexts());
    }

    private List<RagDocumentContext<Void>> validateSufficientContext(final List<RagDocumentContext<Void>> ragContext) {
        if (slackContextCount(ragContext)
                + zenDeskContextCount(ragContext)
                + planhatContextCount(ragContext)
                < parsedArgs.getMinTimeBasedContext()) {
            throw new InsufficientContext("No Slack messages, ZenDesk tickets, or PlanHat activities found.");
        }

        return ragContext;
    }

    /**
     * If there is no google doc to include in the prompt, make a note in the system prompt to ignore any reference to google docs.
     */
    private String getAdditionalGoogleDocsInstructions(final List<RagDocumentContext<Void>> ragContext) {
        if (googleDocsContextCount(ragContext) == 0) {
            return "No " + googleDocs.getContextLabel() + " are available. You will be penalized for referencing any " + googleDocs.getContextLabel() + " in the response.";
        }

        return "";
    }

    private long googleDocsContextCount(final List<RagDocumentContext<Void>> ragContext) {
        return ragContext.stream().filter(ragDoc -> ragDoc.contextLabel().contains(googleDocs.getContextLabel())).count();
    }

    /**
     * If there is no zen desk tickets to include in the prompt, make a note in the system prompt to ignore any reference to zen desk.
     */
    private String getAdditionalZenDeskInstructions(final List<RagDocumentContext<Void>> ragContext) {
        if (zenDeskContextCount(ragContext) == 0) {
            return "No " + zenDeskOrganization.getContextLabel() + " are available. You will be penalized for referencing any " + zenDeskOrganization.getContextLabel() + " in the response.";
        }

        return "";
    }

    private long zenDeskContextCount(final List<RagDocumentContext<Void>> ragContext) {
        return ragContext.stream().filter(ragDoc -> ragDoc.contextLabel().contains(zenDeskOrganization.getContextLabel())).count();
    }

    /**
     * If there are no slack messages to include in the prompt, make a note in the system prompt to ignore any reference to slack messages.
     */
    private String getAdditionalSlackInstructions(final List<RagDocumentContext<Void>> ragContext) {
        if (slackContextCount(ragContext) == 0) {
            return "No " + slackChannel.getContextLabel() + " are available. You will be penalized for referencing any " + slackChannel.getContextLabel() + " in the response.";
        }

        return "";
    }

    private long slackContextCount(final List<RagDocumentContext<Void>> ragContext) {
        return ragContext.stream().filter(ragDoc -> ragDoc.contextLabel().contains(slackChannel.getContextLabel())).count();
    }

    /**
     * If there are no planhat activities to include in the prompt, make a note in the system prompt to ignore any reference to planhat activities.
     */
    private String getAdditionalPlanHatInstructions(final List<RagDocumentContext<Void>> ragContext) {
        if (planhatContextCount(ragContext) == 0) {
            return "No " + planHat.getContextLabel() + " are available. You will be penalized for referencing any " + planHat.getContextLabel() + " in the response.";
        }

        return "";
    }

    private long planhatContextCount(final List<RagDocumentContext<Void>> ragContext) {
        return ragContext.stream().filter(ragDoc -> ragDoc.contextLabel().contains(planHat.getContextLabel())).count();
    }

    @Override
    public String getContextLabel() {
        return "Unused";
    }

    /**
     * Try and get all the downstream context. Note that this tool is a long-running operation that attempts to access a lot
     * of data. We silently fail for any downstream context that could not be retrieved rather than fail the entire operation.
     */
    private List<RagDocumentContext<Void>> getEntityContext(final Entity entity, final Map<String, String> context, final String prompt, final int days) {
        if (entity.disabled()) {
            return List.of();
        }

        logger.info("Processing " + entity.name());

        final List<RagDocumentContext<Void>> slackContext = entity.getSlack()
                .stream()
                .filter(StringUtils::isNotBlank)
                .map(id -> List.of(
                        new ToolArgs(SlackChannel.SLACK_CHANEL_ARG, id, true),
                        new ToolArgs(SlackChannel.SLACK_KEYWORD_ARG, parsedArgs.getKeywords(), true),
                        new ToolArgs(SlackChannel.DAYS_ARG, "" + days, true),
                        new ToolArgs(SlackChannel.SLACK_DISABLELINKS_ARG, parsedArgs.getDisableLinks().toString(), true)))
                // Some arguments require the value to be defined in the prompt to be considered valid, so we have to modify the prompt
                .flatMap(args -> Try.of(() -> slackChannel.getContext(context, prompt, args))
                        // We continue on even if one tool fails, so log and swallow the exception
                        .onFailure(ex -> log.info("Slack channel failed, ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .getOrElse(List::of)
                        .stream())
                // The context label is updated to include the entity name
                .map(ragDoc -> ragDoc.updateContextLabel(entity.name() + " " + ragDoc.contextLabel()))
                .toList();

        /*
            Search slack for any mention of the salesforce and planhat ids. This will pick up call summaries that are posted
            to slack by the salesforce integration.
         */
        final List<RagDocumentContext<Void>> slackKeywordSearch = Try
                // Combine all the keywords we are going to search for
                .of(() -> CollectionUtils.collate(entity.getSalesforce(), entity.getPlanHat()))
                // Get a list of arguments using the keywords
                .map(ids -> List.of(
                        new ToolArgs(SlackSearch.SLACK_SEARCH_KEYWORDS_ARG, String.join(",", ids), true),
                        new ToolArgs(SlackSearch.SLACK_SEARCH_FILTER_KEYWORDS_ARG, parsedArgs.getKeywords(), true),
                        new ToolArgs(SlackSearch.SLACK_SEARCH_DAYS_ARG, "" + days, true),
                        new ToolArgs(SlackSearch.SLACK_SEARCH_DISABLELINKS_ARG, parsedArgs.getDisableLinks().toString(), true)))
                // Search for the keywords
                .map(args -> slackSearch.getContext(context, prompt, args))
                // We continue on even if one tool fails, so log and swallow the exception
                .onFailure(ex -> log.info("Slack keyword search failed, ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                // If anything fails, get an empty list
                .getOrElse(List::of)
                // Post-process the rag context
                .stream()
                .map(ragDoc -> ragDoc.updateContextLabel(entity.name() + " " + ragDoc.contextLabel()))
                .map(RagDocumentContext::getRagDocumentContextVoid)
                .toList();

        final List<RagDocumentContext<Void>> googleContext = entity.getGoogleDcos()
                .stream()
                .filter(StringUtils::isNotBlank)
                .map(id -> List.of(
                        new ToolArgs(GoogleDocs.GOOGLE_DOC_ID_ARG, id, true),
                        new ToolArgs(GoogleDocs.GOOGLE_KEYWORD_ARG, parsedArgs.getKeywords(), true),
                        new ToolArgs(GoogleDocs.GOOGLE_DISABLE_LINKS_ARG, parsedArgs.getDisableLinks().toString(), true)))
                .flatMap(args -> Try.of(() -> googleDocs.getContext(context, prompt, args))
                        // We continue on even if one tool fails, so log and swallow the exception
                        .onFailure(ex -> log.info("Google doc failed, ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .getOrElse(List::of)
                        .stream())
                // The context label is updated to include the entity name
                .map(ragDoc -> ragDoc.updateContextLabel(entity.name() + " " + ragDoc.contextLabel()))
                .toList();

        final List<RagDocumentContext<Void>> zenContext = entity.getZenDesk()
                .stream()
                .filter(StringUtils::isNotBlank)
                .map(id -> List.of(
                        new ToolArgs(ZenDeskOrganization.ZENDESK_ORGANIZATION_ARG, id, true),
                        new ToolArgs(ZenDeskOrganization.ZENDESK_KEYWORD_ARG, parsedArgs.getKeywords(), true),
                        new ToolArgs(ZenDeskOrganization.DAYS_ARG, "" + days, true),
                        new ToolArgs(ZenDeskOrganization.ZENDESK_DISABLELINKS_ARG, parsedArgs.getDisableLinks().toString(), true)))
                .flatMap(args -> Try.of(() -> zenDeskOrganization.getContext(context, prompt, args))
                        // We continue on even if one tool fails, so log and swallow the exception
                        .onFailure(ex -> log.info("ZenDesk search failed ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .getOrElse(List::of)
                        .stream())
                // The context label is updated to include the entity name
                .map(ragDoc -> ragDoc.updateContextLabel(entity.name() + " " + ragDoc.contextLabel()))
                .map(RagDocumentContext::getRagDocumentContextVoid)
                .toList();

        final List<RagDocumentContext<Void>> planHatContext = entity.getPlanHat()
                .stream()
                .filter(StringUtils::isNotBlank)
                .map(id -> List.of(
                        new ToolArgs(PlanHat.DISABLE_LINKS_ARG, parsedArgs.getDisableLinks().toString(), true),
                        new ToolArgs(PlanHat.PLANHAT_KEYWORD_ARG, parsedArgs.getKeywords(), true),
                        new ToolArgs(PlanHat.COMPANY_ID_ARGS, id, true),
                        new ToolArgs(PlanHat.DAYS_ARG, parsedArgs.getDays() + "", true)))
                .flatMap(args -> Try.of(() -> planHat.getContext(context, prompt, args))
                        // We continue on even if one tool fails, so log and swallow the exception
                        .onFailure(ex -> log.info("Planhat search failed ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .getOrElse(List::of)
                        .stream())
                // The context label is updated to include the entity name
                .map(ragDoc -> ragDoc.updateContextLabel(entity.name() + " " + ragDoc.contextLabel()))
                .map(RagDocumentContext::getRagDocumentContextVoid)
                .toList();

        final List<RagDocumentContext<Void>> retValue = new ArrayList<>();
        retValue.addAll(slackKeywordSearch);
        retValue.addAll(slackContext);
        retValue.addAll(googleContext);
        retValue.addAll(zenContext);
        retValue.addAll(planHatContext);
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
        public List<Entity> getEntities() {
            return Objects.requireNonNullElse(entities, List.of());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Entity(String name, List<String> zendesk, List<String> slack, List<String> googledocs, List<String> planhat,
                  List<String> salesforce,
                  boolean disabled) {
        public List<String> getSlack() {
            return Objects.requireNonNullElse(slack, List.<String>of())
                    .stream()
                    .filter(StringUtils::isNotBlank)
                    .toList();
        }

        public List<String> getGoogleDcos() {
            return Objects.requireNonNullElse(googledocs, List.<String>of())
                    .stream()
                    .filter(StringUtils::isNotBlank)
                    .toList();
        }

        public List<String> getZenDesk() {
            return Objects.requireNonNullElse(zendesk, List.<String>of())
                    .stream()
                    .filter(StringUtils::isNotBlank)
                    .toList();
        }

        public List<String> getPlanHat() {
            return Objects.requireNonNullElse(planhat, List.<String>of())
                    .stream()
                    .filter(StringUtils::isNotBlank)
                    .toList();
        }

        public List<String> getSalesforce() {
            return Objects.requireNonNullElse(salesforce, List.<String>of())
                    .stream()
                    .filter(StringUtils::isNotBlank)
                    .toList();
        }
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
    @ConfigProperty(name = "sb.multislackzengoogle.maxentities")
    private Optional<String> maxEntities;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.days")
    private Optional<String> days;

    @Inject
    @ConfigProperty(name = "sb.slackzengoogle.minTimeBasedContext")
    private Optional<String> slackZenGoogleMinTimeBasedContext;

    @Inject
    @ConfigProperty(name = "sb.slackzengoogle.disablelinks")
    private Optional<String> disableLinks;

    @Inject
    @ConfigProperty(name = "sb.slackzengoogle.keywords")
    private Optional<String> keywords;

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
                MultiSlackZenGoogle.MULTI_SLACK_ZEN_URL_ARG,
                "multislackzengoogle_url",
                "").value();
    }

    public int getDays() {
        final String stringValue = argsAccessor.getArgument(
                days::get,
                arguments,
                context,
                MultiSlackZenGoogle.MULTI_SLACK_ZEN_DAYS_ARG,
                "multislackzengoogle_days",
                "0").value();

        return Try.of(() -> Integer.parseInt(stringValue))
                .recover(throwable -> 0)
                .map(i -> Math.max(0, i))
                .get();
    }

    public List<String> getEntityName() {
        return argsAccessor.getArgumentList(
                        entity::get,
                        arguments,
                        context,
                        MultiSlackZenGoogle.MULTI_SLACK_ZEN_ENTITY_NAME_ARG,
                        "multislackzengoogle_entity_name",
                        "")
                .stream()
                .map(Argument::value)
                .map(String::toLowerCase)
                .toList();
    }

    public int getMaxEntities() {
        final String stringValue = argsAccessor.getArgument(
                maxEntities::get,
                arguments,
                context,
                MultiSlackZenGoogle.MULTI_SLACK_ZEN_DAYS_ARG,
                "multislackzengoogle_max_entities",
                "0").value();

        return Try.of(() -> Integer.parseInt(stringValue))
                .recover(throwable -> 0)
                .map(i -> Math.max(0, i))
                .get();
    }

    public int getMinTimeBasedContext() {
        final String stringValue = argsAccessor.getArgument(
                slackZenGoogleMinTimeBasedContext::get,
                arguments,
                context,
                "multislackzengoogleMinTimeBasedContext",
                "multislackzengoogle_min_time_based_context",
                "1").value();

        return NumberUtils.toInt(stringValue, 1);
    }

    public Boolean getDisableLinks() {
        final String stringValue = argsAccessor.getArgument(
                disableLinks::get,
                arguments,
                context,
                MultiSlackZenGoogle.MULTI_SLACK_ZEN_GOOGLE_DISABLELINKS,
                "multislackzengoogle_disable_links",
                "false").value();

        return BooleanUtils.toBoolean(stringValue);
    }

    public String getKeywords() {
        return argsAccessor.getArgument(
                        keywords::get,
                        arguments,
                        context,
                        MultiSlackZenGoogle.MULTI_SLACK_ZEN_KEYWORD_ARG,
                        "multislackzengoogle_keywords",
                        "")
                .value();
    }
}
