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
import org.jspecify.annotations.Nullable;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.config.ModelConfig;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptionhandling.ExceptionHandler;
import secondbrain.domain.exceptions.*;
import secondbrain.domain.json.JsonDeserializer;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.reader.FileReader;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.tools.gong.Gong;
import secondbrain.domain.tools.googledocs.GoogleDocs;
import secondbrain.domain.tools.planhat.PlanHat;
import secondbrain.domain.tools.planhat.PlanHatUsage;
import secondbrain.domain.tools.rating.RatingTool;
import secondbrain.domain.tools.slack.SlackChannel;
import secondbrain.domain.tools.slack.SlackSearch;
import secondbrain.domain.tools.zendesk.ZenDeskOrganization;
import secondbrain.domain.yaml.YamlDeserializer;
import secondbrain.infrastructure.ollama.OllamaClient;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static com.google.common.base.Predicates.instanceOf;
import static com.pivovarit.collectors.ParallelCollectors.Batching.parallelToStream;


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
    public static final String MULTI_SLACK_ZEN_WINDOW_ARG = "keywordWindow";
    public static final String MULTI_SLACK_ZEN_URL_ARG = "url";
    public static final String MULTI_SLACK_ZEN_DAYS_ARG = "days";
    public static final String MULTI_SLACK_ZEN_ENTITY_NAME_ARG = "entityName";
    public static final String MULTI_SLACK_ZEN_MAX_ENTITIES_ARG = "maxEntities";
    public static final String MULTI_SLACK_ZEN_CONTEXT_FILTER_QUESTION_ARG = "contextFilterQuestion";
    public static final String MULTI_SLACK_ZEN_META_REPORT_ARG = "metaReport";
    public static final String MULTI_SLACK_ZEN_CONTEXT_FILTER_MINIMUM_RATING_ARG = "contextFilterMinimumRating";
    private static final int BATCH_SIZE = 10;
    private static final String INSTRUCTIONS = """
            You are helpful agent.
            You are given the contents of a multiple Slack channels, Google Documents, PlanHat activities, Gong calls, and the help desk tickets from ZenDesk.
            You must answer the prompt based on the information provided.
            """;

    private static final AtomicInteger COUNTER = new AtomicInteger(0);

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
    private PlanHatUsage planHatUsage;

    @Inject
    private ZenDeskOrganization zenDeskOrganization;

    @Inject
    private Gong gong;

    @Inject
    private RatingTool ratingTool;

    @Inject
    private OllamaClient ollamaClient;

    @Inject
    private PromptBuilderSelector promptBuilderSelector;

    @Inject
    private YamlDeserializer yamlDeserializer;

    @Inject
    private FileReader fileReader;

    @Inject
    private MultiSlackZenGoogleConfig config;

    @Inject
    private ExceptionHandler exceptionHandler;

    @Inject
    private Logger log;

    @Inject
    private Logger logger;

    @Inject
    private JsonDeserializer jsonDeserializer;

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
                * Given the directory from "/home/user/data/entities.yml" write a story about the use of the astro framework.
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
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final MultiSlackZenGoogleConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final EntityDirectory entityDirectory = Try.of(() -> fileReader.read(parsedArgs.getUrl()))
                .map(file -> yamlDeserializer.deserialize(file, EntityDirectory.class))
                .getOrElseThrow(ex -> new ExternalFailure("Failed to download or parse the entity directory", ex));

        final Executor executor = Executors.newVirtualThreadPerTaskExecutor();

        /*
            We want to randomize the order of the entities to allow multiple concurrent instances of this tool
            to process different entities. If the list is processed in the same order, we risk each instance
            of the tool running into API limits for the same entities over and over again.
         */
        final List<PositionalEntity> shuffledList = new ArrayList<>(entityDirectory.getPositionalEntities().stream()
                .filter(entity -> parsedArgs.getEntityName().isEmpty() || parsedArgs.getEntityName().contains(entity.entity.name().toLowerCase()))
                .limit(parsedArgs.getMaxEntities() == 0 ? Long.MAX_VALUE : parsedArgs.getMaxEntities())
                .toList());
        Collections.shuffle(shuffledList);

        final List<RagDocumentContext<Void>> ragContext = shuffledList
                .stream()
                // This needs java 24 to be useful with HTTP clients like RESTEasy: https://github.com/orgs/resteasy/discussions/4300
                // We batch here to interleave API requests to the various external data sources
                .collect(parallelToStream(entity -> getEntityContext(entity, environmentSettings, prompt, parsedArgs).stream(), executor, BATCH_SIZE))
                .flatMap(stream -> stream)
                .toList();

        if (ragContext.isEmpty()) {
            throw new InsufficientContext("No ZenDesk tickets, Slack messages, or PlanHat activities found.");
        }

        return ragContext;
    }

    @Override
    public RagMultiDocumentContext<Void> call(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> getContext(environmentSettings, prompt, arguments))
                .map(ragContext -> mergeContext(ragContext, modelConfig.getCalculatedModel(environmentSettings)))
                .map(multiRagDoc -> multiRagDoc.updateDocument(
                        promptBuilderSelector
                                .getPromptBuilder(modelConfig.getCalculatedModel(environmentSettings))
                                .buildFinalPrompt(getInstructions(multiRagDoc),
                                        // I've opted to get the end of the document if it is larger than the context window.
                                        // The end of the document is typically given more weight by LLMs, and so any long
                                        // document being processed should place the most relevant content towards the end.
                                        multiRagDoc.getDocumentRight(modelConfig.getCalculatedContextWindow(environmentSettings)),
                                        prompt)))
                .map(ragDoc -> ollamaClient.callOllamaWithCache(
                        ragDoc,
                        modelConfig.getCalculatedModel(environmentSettings),
                        getName(),
                        modelConfig.getCalculatedContextWindow(environmentSettings)))
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
                        API.Case(API.$(instanceOf(FailedOllama.class)), throwable -> new InternalFailure(throwable.getMessage(), throwable)),
                        API.Case(API.$(), ex -> new ExternalFailure(getName() + " failed to call Ollama", ex)))
                .get();
    }

    private String getInstructions(final RagMultiDocumentContext<Void> multiRagDoc) {
        return INSTRUCTIONS
                + getAdditionalSlackInstructions(multiRagDoc.individualContexts())
                + getAdditionalPlanHatInstructions(multiRagDoc.individualContexts())
                + getAdditionalGoogleDocsInstructions(multiRagDoc.individualContexts())
                + getAdditionalGongInstructions(multiRagDoc.individualContexts())
                + getAdditionalZenDeskInstructions(multiRagDoc.individualContexts());
    }

    private List<RagDocumentContext<Void>> validateSufficientContext(final List<RagDocumentContext<Void>> ragContext, final MultiSlackZenGoogleConfig.LocalArguments parsedArgs) {
        if (slackContextCount(ragContext)
                + zenDeskContextCount(ragContext)
                + planhatContextCount(ragContext)
                + gongContextCount(ragContext)
                < parsedArgs.getMinTimeBasedContext()) {
            return List.of();
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

    /**
     * If there are no gong calls to include in the prompt, make a note in the system prompt to ignore any reference to planhat activities.
     */
    private String getAdditionalGongInstructions(final List<RagDocumentContext<Void>> ragContext) {
        if (gongContextCount(ragContext) == 0) {
            return "No " + gong.getContextLabel() + " are available. You will be penalized for referencing any " + gong.getContextLabel() + " in the response.";
        }

        return "";
    }

    private long planhatContextCount(final List<RagDocumentContext<Void>> ragContext) {
        return ragContext.stream().filter(ragDoc -> ragDoc.contextLabel().contains(planHat.getContextLabel())).count();
    }

    private long gongContextCount(final List<RagDocumentContext<Void>> ragContext) {
        return ragContext.stream().filter(ragDoc -> ragDoc.contextLabel().contains(gong.getContextLabel())).count();
    }

    @Override
    public String getContextLabel() {
        return "Unused";
    }

    /**
     * Try and get all the downstream context. Note that this tool is a long-running operation that attempts to access a lot
     * of data. We silently fail for any downstream context that could not be retrieved rather than fail the entire operation.
     */
    private List<RagDocumentContext<Void>> getEntityContext(final PositionalEntity positionalEntity, final Map<String, String> context, final String prompt, final MultiSlackZenGoogleConfig.LocalArguments parsedArgs) {
        final Entity entity = positionalEntity.entity();

        if (entity.disabled()) {
            return List.of();
        }

        logger.info("Percent complete: " + ((float) COUNTER.incrementAndGet() / positionalEntity.total * 100) + "%");

        final List<RagDocumentContext<Void>> zenContext = getZenContext(positionalEntity, parsedArgs, prompt, context);
        final List<RagDocumentContext<Void>> slackContext = getSlackContext(positionalEntity, parsedArgs, prompt, context);
        final List<RagDocumentContext<Void>> googleContext = getGoogleContext(positionalEntity, parsedArgs, prompt, context);
        final List<RagDocumentContext<Void>> planHatContext = getPlanhatContext(positionalEntity, parsedArgs, prompt, context);
        final List<RagDocumentContext<Void>> gongContext = getGongContext(positionalEntity, parsedArgs, prompt, context);

        // Slack searches use AND logic. This means we need to search each of the IDs (i.e. salesforce and planhat) separately.
        final List<RagDocumentContext<Void>> slackKeywordSearch = CollectionUtils.collate(positionalEntity.entity().getSalesforce(), positionalEntity.entity().getPlanHat())
                .stream()
                .flatMap(id -> getSlackKeywordContext(positionalEntity, parsedArgs, prompt, context, id).stream())
                // Remove duplicates
                .filter(keywordRagDoc -> slackContext.stream().noneMatch(ragDoc -> ragDoc.getLink().equals(keywordRagDoc.getLink())))
                .toList();

        final List<RagDocumentContext<Void>> retValue = new ArrayList<>();
        retValue.addAll(slackKeywordSearch);
        retValue.addAll(slackContext);
        retValue.addAll(googleContext);
        retValue.addAll(zenContext);
        retValue.addAll(planHatContext);
        retValue.addAll(gongContext);

        // Only proceed with these details if we have enough context to include this entity in the response.
        if (!validateSufficientContext(retValue, parsedArgs).isEmpty()) {
            final List<RagDocumentContext<Void>> planHatUsageContext = getPlanhatUsageContext(positionalEntity, parsedArgs, prompt, context);
            retValue.addAll(planHatUsageContext);
        }

        final List<RagDocumentContext<Void>> filteredContext = contextMeetsRating(validateSufficientContext(retValue, parsedArgs), entity.name(), parsedArgs);

        saveMetaResult(positionalEntity, filteredContext, parsedArgs);

        return filteredContext;
    }

    private void saveMetaResult(final PositionalEntity positionalEntity, final List<RagDocumentContext<Void>> ragContext, final MultiSlackZenGoogleConfig.LocalArguments parsedArgs) {
        if (StringUtils.isNotBlank(parsedArgs.getMetaReport())) {
            final MetaResult metaResult = new MetaResult(
                    positionalEntity.entity().name(),
                    ragContext.size());

            Try.run(() -> Files.write(Paths.get(parsedArgs.getMetaReport()),
                            jsonDeserializer.serialize(metaResult).getBytes(),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING))
                    .onFailure(ex -> logger.severe(exceptionHandler.getExceptionMessage(ex)));
        }
    }

    /**
     * Search slack for any mention of the salesforce and planhat ids. This will pick up call summaries that are posted
     * to slack by the salesforce integration.
     */
    private List<RagDocumentContext<Void>> getSlackKeywordContext(final PositionalEntity positionalEntity, final MultiSlackZenGoogleConfig.LocalArguments parsedArgs, final String prompt, final Map<String, String> context, final String id) {
        logger.info("Getting Slack keywords for " + positionalEntity.entity().name() + " " + positionalEntity.position + " of " + positionalEntity.total);
        return Try
                // Combine all the keywords we are going to search for
                .of(() -> List.of(
                        new ToolArgs(SlackSearch.SLACK_SEARCH_KEYWORDS_ARG, id, true),
                        new ToolArgs(SlackSearch.SLACK_SEARCH_FILTER_KEYWORDS_ARG, parsedArgs.getKeywords(), true),
                        new ToolArgs(SlackSearch.SLACK_SEARCH_DAYS_ARG, "" + parsedArgs.getDays(), true),
                        new ToolArgs(SlackSearch.SLACK_SEARCH_DISABLELINKS_ARG, parsedArgs.getDisableLinks().toString(), true)))
                // Search for the keywords
                .map(args -> slackSearch.getContext(
                        addItemToMap(context, SlackSearch.SLACK_ENTITY_NAME_CONTEXT_ARG, positionalEntity.entity().name()),
                        prompt,
                        args))
                // We continue on even if one tool fails, so log and swallow the exception
                .onFailure(InternalFailure.class, ex -> log.info("Slack keyword search failed, ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                .onFailure(ExternalFailure.class, ex -> log.warning("Slack keyword search failed, ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                // If anything fails, get an empty list
                .getOrElse(List::of)
                // Post-process the rag context
                .stream()
                .map(ragDoc -> ragDoc.updateContextLabel(positionalEntity.entity().name() + " " + ragDoc.contextLabel()))
                .map(ragDoc -> ragDoc.updateGroup(positionalEntity.entity().name()))
                .map(RagDocumentContext::getRagDocumentContextVoid)
                .toList();
    }

    private List<RagDocumentContext<Void>> getGongContext(final PositionalEntity positionalEntity, final MultiSlackZenGoogleConfig.LocalArguments parsedArgs, final String prompt, final Map<String, String> context) {
        logger.info("Getting Gong transcripts for " + positionalEntity.entity().name() + " " + positionalEntity.position + " of " + positionalEntity.total);
        return Objects.requireNonNullElse(positionalEntity.entity().salesforce(), List.<String>of())
                .stream()
                .filter(StringUtils::isNotBlank)
                .map(id -> List.of(
                        new ToolArgs(Gong.GONG_DISABLELINKS_ARG, parsedArgs.getDisableLinks().toString(), true),
                        new ToolArgs(Gong.GONG_KEYWORD_ARG, parsedArgs.getKeywords(), true),
                        new ToolArgs(Gong.GONG_KEYWORD_WINDOW_ARG, parsedArgs.getKeywordWindow().toString(), true),
                        new ToolArgs(Gong.COMPANY_ARG, id, true),
                        new ToolArgs(Gong.DAYS_ARG, parsedArgs.getDays() + "", true)))
                .flatMap(args -> Try.of(() -> gong.getContext(
                                addItemToMap(context, Gong.GONG_ENTITY_NAME_CONTEXT_ARG, positionalEntity.entity().name()),
                                prompt,
                                args))
                        // We continue on even if one tool fails, so log and swallow the exception
                        .onFailure(InternalFailure.class, ex -> log.info("Gong search failed ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .onFailure(ExternalFailure.class, ex -> log.warning("Gong search failed ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .getOrElse(List::of)
                        .stream())
                // The context label is updated to include the entity name
                .map(ragDoc -> ragDoc.updateContextLabel(positionalEntity.entity().name() + " " + ragDoc.contextLabel()))
                .map(ragDoc -> ragDoc.updateGroup(positionalEntity.entity().name()))
                .map(RagDocumentContext::getRagDocumentContextVoid)
                .toList();
    }

    private List<RagDocumentContext<Void>> getPlanhatContext(final PositionalEntity positionalEntity, final MultiSlackZenGoogleConfig.LocalArguments parsedArgs, final String prompt, final Map<String, String> context) {
        logger.info("Getting PlanHat activities for " + positionalEntity.entity().name() + " " + positionalEntity.position + " of " + positionalEntity.total);
        return Objects.requireNonNullElse(positionalEntity.entity().getPlanHat(), List.<String>of())
                .stream()
                .filter(StringUtils::isNotBlank)
                .map(id -> List.of(
                        new ToolArgs(PlanHat.DISABLE_LINKS_ARG, parsedArgs.getDisableLinks().toString(), true),
                        new ToolArgs(PlanHat.PLANHAT_KEYWORD_ARG, parsedArgs.getKeywords(), true),
                        new ToolArgs(PlanHat.PLANHAT_KEYWORD_WINDOW_ARG, parsedArgs.getKeywordWindow().toString(), true),
                        new ToolArgs(PlanHat.COMPANY_ID_ARGS, id, true),
                        new ToolArgs(PlanHat.DAYS_ARG, parsedArgs.getDays() + "", true)))
                .flatMap(args -> Try.of(() -> planHat.getContext(
                                addItemToMap(context, PlanHat.PLANHAT_ENTITY_NAME_CONTEXT_ARG, positionalEntity.entity().name()),
                                prompt,
                                args))
                        // We continue on even if one tool fails, so log and swallow the exception
                        .onFailure(InternalFailure.class, ex -> log.info("Planhat search failed ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .onFailure(ExternalFailure.class, ex -> log.warning("Planhat search failed ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .getOrElse(List::of)
                        .stream())
                // The context label is updated to include the entity name
                .map(ragDoc -> ragDoc.updateContextLabel(positionalEntity.entity().name() + " " + ragDoc.contextLabel()))
                .map(ragDoc -> ragDoc.updateGroup(positionalEntity.entity().name()))
                .map(RagDocumentContext::getRagDocumentContextVoid)
                .toList();
    }

    private List<RagDocumentContext<Void>> getPlanhatUsageContext(final PositionalEntity positionalEntity, final MultiSlackZenGoogleConfig.LocalArguments parsedArgs, final String prompt, final Map<String, String> context) {
        logger.info("Getting PlanHat usage for " + positionalEntity.entity().name() + " " + positionalEntity.position + " of " + positionalEntity.total);
        return Objects.requireNonNullElse(positionalEntity.entity().getPlanHat(), List.<String>of())
                .stream()
                .filter(StringUtils::isNotBlank)
                .map(id -> List.of(
                        new ToolArgs(PlanHatUsage.COMPANY_ID_ARGS, id, true)))
                .flatMap(args -> Try.of(() -> planHatUsage.getContext(
                                context,
                                prompt,
                                args))
                        // We continue on even if one tool fails, so log and swallow the exception
                        .onFailure(InternalFailure.class, ex -> log.info("Planhat usage failed ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .onFailure(ExternalFailure.class, ex -> log.warning("Planhat usage failed ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .getOrElse(List::of)
                        .stream())
                // The context label is updated to include the entity name
                .map(ragDoc -> ragDoc.updateContextLabel(positionalEntity.entity().name() + " " + ragDoc.contextLabel()))
                .map(ragDoc -> ragDoc.updateGroup(positionalEntity.entity().name()))
                .map(RagDocumentContext::getRagDocumentContextVoid)
                .toList();
    }

    private List<RagDocumentContext<Void>> getGoogleContext(final PositionalEntity positionalEntity, final MultiSlackZenGoogleConfig.LocalArguments parsedArgs, final String prompt, final Map<String, String> context) {
        logger.info("Getting Google Docs for " + positionalEntity.entity().name() + " " + positionalEntity.position + " of " + positionalEntity.total);
        return Objects.requireNonNullElse(positionalEntity.entity().getGoogleDcos(), List.<String>of())
                .stream()
                .filter(StringUtils::isNotBlank)
                .map(id -> List.of(
                        new ToolArgs(GoogleDocs.GOOGLE_DOC_ID_ARG, id, true),
                        new ToolArgs(GoogleDocs.GOOGLE_KEYWORD_ARG, parsedArgs.getKeywords(), true),
                        new ToolArgs(GoogleDocs.GOOGLE_KEYWORD_WINDOW_ARG, parsedArgs.getKeywordWindow().toString(), true),
                        new ToolArgs(GoogleDocs.GOOGLE_DISABLE_LINKS_ARG, parsedArgs.getDisableLinks().toString(), true)))
                .flatMap(args -> Try.of(() -> googleDocs.getContext(
                                addItemToMap(context, GoogleDocs.GOOGLE_ENTITY_NAME_CONTEXT_ARG, positionalEntity.entity().name()),
                                prompt,
                                args))
                        // We continue on even if one tool fails, so log and swallow the exception
                        .onFailure(InternalFailure.class, ex -> log.info("Google doc failed, ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .onFailure(ExternalFailure.class, ex -> log.warning("Google doc failed, ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .getOrElse(List::of)
                        .stream())
                // The context label is updated to include the entity name
                .map(ragDoc -> ragDoc.updateContextLabel(positionalEntity.entity().name() + " " + ragDoc.contextLabel()))
                .map(ragDoc -> ragDoc.updateGroup(positionalEntity.entity().name()))
                .toList();
    }

    private List<RagDocumentContext<Void>> getSlackContext(final PositionalEntity positionalEntity, final MultiSlackZenGoogleConfig.LocalArguments parsedArgs, final String prompt, final Map<String, String> context) {
        logger.info("Getting Slack channel for " + positionalEntity.entity().name() + " " + positionalEntity.position + " of " + positionalEntity.total);
        return Objects.requireNonNullElse(positionalEntity.entity().getSlack(), List.<String>of())
                .stream()
                .filter(StringUtils::isNotBlank)
                .map(id -> List.of(
                        new ToolArgs(SlackChannel.SLACK_CHANEL_ARG, id, true),
                        new ToolArgs(SlackChannel.SLACK_KEYWORD_ARG, parsedArgs.getKeywords(), true),
                        new ToolArgs(SlackChannel.SLACK_KEYWORD_WINDOW_ARG, parsedArgs.getKeywordWindow().toString(), true),
                        new ToolArgs(SlackChannel.DAYS_ARG, "" + parsedArgs.getDays(), true),
                        new ToolArgs(SlackChannel.SLACK_DISABLELINKS_ARG, parsedArgs.getDisableLinks().toString(), true)))
                // Some arguments require the value to be defined in the prompt to be considered valid, so we have to modify the prompt
                .flatMap(args -> Try.of(() -> slackChannel.getContext(
                                addItemToMap(context, SlackChannel.SLACK_ENTITY_NAME_CONTEXT_ARG, positionalEntity.entity().name()),
                                prompt,
                                args))
                        // We continue on even if one tool fails, so log and swallow the exception
                        .onFailure(InternalFailure.class, ex -> log.info("Slack channel failed, ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .onFailure(ExternalFailure.class, ex -> log.warning("Slack channel failed, ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .getOrElse(List::of)
                        .stream())
                // The context label is updated to include the entity name
                .map(ragDoc -> ragDoc.updateContextLabel(positionalEntity.entity().name() + " " + ragDoc.contextLabel()))
                .map(ragDoc -> ragDoc.updateGroup(positionalEntity.entity().name()))
                .toList();
    }

    private List<RagDocumentContext<Void>> getZenContext(final PositionalEntity positionalEntity, final MultiSlackZenGoogleConfig.LocalArguments parsedArgs, final String prompt, final Map<String, String> context) {
        logger.info("Getting ZenDesk tickets for " + positionalEntity.entity().name() + " " + positionalEntity.position + " of " + positionalEntity.total);

        return Objects.requireNonNullElse(positionalEntity.entity().getZenDesk(), List.<String>of())
                .stream()
                .filter(StringUtils::isNotBlank)
                .map(id -> List.of(
                        new ToolArgs(ZenDeskOrganization.ZENDESK_ORGANIZATION_ARG, id, true),
                        new ToolArgs(ZenDeskOrganization.ZENDESK_KEYWORD_ARG, parsedArgs.getKeywords(), true),
                        new ToolArgs(ZenDeskOrganization.ZENDESK_KEYWORD_WINDOW_ARG, parsedArgs.getKeywordWindow().toString(), true),
                        new ToolArgs(ZenDeskOrganization.DAYS_ARG, "" + parsedArgs.getDays(), true),
                        new ToolArgs(ZenDeskOrganization.ZENDESK_DISABLELINKS_ARG, parsedArgs.getDisableLinks().toString(), true)))
                .flatMap(args -> Try.of(() -> zenDeskOrganization.getContext(
                                addItemToMap(context, ZenDeskOrganization.ZENDESK_ENTITY_NAME_CONTEXT_ARG, positionalEntity.entity().name()),
                                prompt,
                                args))
                        // We continue on even if one tool fails, so log and swallow the exception
                        .onFailure(InternalFailure.class, ex -> log.info("ZenDesk search failed ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .onFailure(ExternalFailure.class, ex -> log.warning("ZenDesk search failed ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .getOrElse(List::of)
                        .stream())
                // The context label is updated to include the entity name
                .map(ragDoc -> ragDoc.updateContextLabel(positionalEntity.entity().name() + " " + ragDoc.contextLabel()))
                .map(ragDoc -> ragDoc.updateGroup(positionalEntity.entity().name()))
                .map(RagDocumentContext::getRagDocumentContextVoid)
                .toList();
    }

    private List<RagDocumentContext<Void>> contextMeetsRating(final List<RagDocumentContext<Void>> ragContext, final String entityName, final MultiSlackZenGoogleConfig.LocalArguments parsedArgs) {
        if (parsedArgs.getContextFilterMinimumRating() <= 0 || StringUtils.isBlank(parsedArgs.getContextFilterQuestion())) {
            return ragContext;
        }

        final Integer rating = getContextRating(ragContext, parsedArgs);

        if (rating >= parsedArgs.getContextFilterMinimumRating()) {
            logger.info("The context rating for entity " + entityName + " (" + rating + ") meets the rating threshold (" + parsedArgs.getContextFilterMinimumRating() + ")");
            return ragContext;
        }

        logger.info("The context rating for entity " + entityName + " (" + rating + ") did not meet the rating threshold (" + parsedArgs.getContextFilterMinimumRating() + ")");
        return List.of();
    }

    private Integer getContextRating(final List<RagDocumentContext<Void>> context, final MultiSlackZenGoogleConfig.LocalArguments parsedArgs) {
        if (parsedArgs.getContextFilterMinimumRating() <= 0 || StringUtils.isBlank(parsedArgs.getContextFilterQuestion())) {
            return 10;
        }

        final RagMultiDocumentContext<Void> multiRagDoc = new RagMultiDocumentContext<>(
                context.stream()
                        .map(ragDoc -> promptBuilderSelector
                                .getPromptBuilder("plain")
                                .buildContextPrompt(
                                        ragDoc.contextLabel(),
                                        ragDoc.document()))
                        .collect(Collectors.joining("\n")),
                context);

        return Try.of(() -> ratingTool.call(
                        Map.of(RatingTool.RATING_DOCUMENT_CONTEXT_ARG, multiRagDoc.combinedDocument()),
                        parsedArgs.getContextFilterQuestion(),
                        List.of()).combinedDocument())
                .map(rating -> org.apache.commons.lang3.math.NumberUtils.toInt(rating, 0))
                // Ratings are provided on a best effort basis, so we ignore any failures
                .recover(InternalFailure.class, ex -> 10)
                .get();

    }

    private Map<String, String> addItemToMap(@Nullable final Map<String, String> map, final String key, final String value) {
        final Map<String, String> result = map == null ? new HashMap<>() : new HashMap<>(map);

        if (key != null) {
            result.put(key, value);
        }

        return result;
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

    record PositionalEntity(Entity entity, int position, int total) {
    }

    record EntityDirectory(List<Entity> entities) {
        public List<Entity> getEntities() {
            return Objects.requireNonNullElse(entities, List.of());
        }

        public List<PositionalEntity> getPositionalEntities() {
            return getEntities()
                    .stream()
                    .map(entity -> new PositionalEntity(entity, getEntities().indexOf(entity) + 1, getEntities().size()))
                    .sorted((item1, item2) -> NumberUtils.compare(item1.position, item2.position))
                    .toList();
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
class MultiSlackZenGoogleConfig {

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.url")
    private Optional<String> configUrl;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metareport")
    private Optional<String> configMetaReport;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.entity")
    private Optional<String> configEntity;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.maxentities")
    private Optional<String> configMaxEntities;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.days")
    private Optional<String> configDays;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.minTimeBasedContext")
    private Optional<String> configSlackZenGoogleMinTimeBasedContext;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.disablelinks")
    private Optional<String> configDisableLinks;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.keywords")
    private Optional<String> configKeywords;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.keywordwindow")
    private Optional<String> configKeywordWindow;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.contextFilterQuestion")
    private Optional<String> configContextFilterQuestion;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.contextFilterMinimumRating")
    private Optional<String> configContextFilterMinimumRating;

    public Optional<String> getConfigUrl() {
        return configUrl;
    }

    public Optional<String> getConfigEntity() {
        return configEntity;
    }

    public Optional<String> getConfigMaxEntities() {
        return configMaxEntities;
    }

    public Optional<String> getConfigDays() {
        return configDays;
    }

    public Optional<String> getConfigSlackZenGoogleMinTimeBasedContext() {
        return configSlackZenGoogleMinTimeBasedContext;
    }

    public Optional<String> getConfigDisableLinks() {
        return configDisableLinks;
    }

    public Optional<String> getConfigKeywords() {
        return configKeywords;
    }

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    public Optional<String> getConfigKeywordWindow() {
        return configKeywordWindow;
    }

    public Optional<String> getConfigContextFilterQuestion() {
        return configContextFilterQuestion;
    }

    public Optional<String> getConfigContextFilterMinimumRating() {
        return configContextFilterMinimumRating;
    }

    public Optional<String> getConfigMetaReport() {
        return configMetaReport;
    }

    public class LocalArguments {
        private final List<ToolArgs> arguments;

        private final String prompt;

        private final Map<String, String> context;

        public LocalArguments(final List<ToolArgs> arguments, final String prompt, final Map<String, String> context) {
            this.arguments = arguments;
            this.prompt = prompt;
            this.context = context;
        }

        public String getUrl() {
            return getArgsAccessor().getArgument(
                    getConfigUrl()::get,
                    arguments,
                    context,
                    MultiSlackZenGoogle.MULTI_SLACK_ZEN_URL_ARG,
                    "multislackzengoogle_url",
                    "").value();
        }

        public int getDays() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigDays()::get,
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
            return getArgsAccessor().getArgumentList(
                            getConfigEntity()::get,
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
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigMaxEntities()::get,
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
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigSlackZenGoogleMinTimeBasedContext()::get,
                    arguments,
                    context,
                    "multislackzengoogleMinTimeBasedContext",
                    "multislackzengoogle_min_time_based_context",
                    "1").value();

            return NumberUtils.toInt(stringValue, 1);
        }

        public Boolean getDisableLinks() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigDisableLinks()::get,
                    arguments,
                    context,
                    MultiSlackZenGoogle.MULTI_SLACK_ZEN_GOOGLE_DISABLELINKS,
                    "multislackzengoogle_disable_links",
                    "false").value();

            return BooleanUtils.toBoolean(stringValue);
        }

        public String getKeywords() {
            return getArgsAccessor().getArgument(
                            getConfigKeywords()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_KEYWORD_ARG,
                            "multislackzengoogle_keywords",
                            "")
                    .value();
        }

        public Integer getKeywordWindow() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigKeywordWindow()::get,
                    arguments,
                    context,
                    MultiSlackZenGoogle.MULTI_SLACK_ZEN_WINDOW_ARG,
                    "multislackzengoogle_keyword_window",
                    Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH + "");

            return org.apache.commons.lang.math.NumberUtils.toInt(argument.value(), Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH);
        }

        public String getContextFilterQuestion() {
            return getArgsAccessor().getArgument(
                            getConfigContextFilterQuestion()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_CONTEXT_FILTER_QUESTION_ARG,
                            "multislackzengoogle_context_filter_question",
                            "")
                    .value();
        }

        public Integer getContextFilterMinimumRating() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigContextFilterMinimumRating()::get,
                    arguments,
                    context,
                    MultiSlackZenGoogle.MULTI_SLACK_ZEN_CONTEXT_FILTER_MINIMUM_RATING_ARG,
                    "multislackzengoogle_context_filter_minimum_rating",
                    "0");

            return org.apache.commons.lang.math.NumberUtils.toInt(argument.value(), 0);
        }

        public String getMetaReport() {
            return getArgsAccessor().getArgument(
                            getConfigMetaReport()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_REPORT_ARG,
                            "multislackzengoogle_meta_report",
                            "")
                    .value();
        }
    }
}
