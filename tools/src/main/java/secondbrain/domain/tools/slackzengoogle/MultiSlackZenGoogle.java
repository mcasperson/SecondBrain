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
import org.apache.commons.lang3.tuple.Pair;
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
import secondbrain.domain.tooldefs.MetaObjectResult;
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
 * This source-tool calls the zendesk, slack, and google tools to answer a prompt against multiple
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
    public static final String MULTI_SLACK_ZEN_MAX_ANNOTATION_PREFIX_ARG = "annotationPrefix";
    public static final String MULTI_SLACK_ZEN_CONTEXT_FILTER_QUESTION_ARG = "contextFilterQuestion";
    public static final String MULTI_SLACK_ZEN_CONTEXT_FILTER_MINIMUM_RATING_ARG = "contextFilterMinimumRating";
    public static final String MULTI_SLACK_ZEN_INDIVIDUAL_CONTEXT_FILTER_QUESTION_ARG = "individualContextFilterQuestion";
    public static final String MULTI_SLACK_ZEN_INDIVIDUAL_CONTEXT_FILTER_MINIMUM_RATING_ARG = "individualContextFilterMinimumRating";
    public static final String MULTI_SLACK_ZEN_META_REPORT_ARG = "metaReport";
    public static final String MULTI_SLACK_ZEN_META_FIELD_1_ARG = "contextMetaField1";
    public static final String MULTI_SLACK_ZEN_META_PROMPT_1_ARG = "contextMetaPrompt1";
    public static final String MULTI_SLACK_ZEN_META_FIELD_2_ARG = "contextMetaField2";
    public static final String MULTI_SLACK_ZEN_META_PROMPT_2_ARG = "contextMetaPrompt2";
    public static final String MULTI_SLACK_ZEN_META_FIELD_3_ARG = "contextMetaField3";
    public static final String MULTI_SLACK_ZEN_META_PROMPT_3_ARG = "contextMetaPrompt3";
    public static final String MULTI_SLACK_ZEN_META_FIELD_4_ARG = "contextMetaField4";
    public static final String MULTI_SLACK_ZEN_META_PROMPT_4_ARG = "contextMetaPrompt4";
    public static final String MULTI_SLACK_ZEN_META_FIELD_5_ARG = "contextMetaField5";
    public static final String MULTI_SLACK_ZEN_META_PROMPT_5_ARG = "contextMetaPrompt5";
    public static final String MULTI_SLACK_ZEN_META_FIELD_6_ARG = "contextMetaField6";
    public static final String MULTI_SLACK_ZEN_META_PROMPT_6_ARG = "contextMetaPrompt6";
    public static final String MULTI_SLACK_ZEN_META_FIELD_7_ARG = "contextMetaField7";
    public static final String MULTI_SLACK_ZEN_META_PROMPT_7_ARG = "contextMetaPrompt7";
    public static final String MULTI_SLACK_ZEN_META_FIELD_8_ARG = "contextMetaField8";
    public static final String MULTI_SLACK_ZEN_META_PROMPT_8_ARG = "contextMetaPrompt8";
    public static final String MULTI_SLACK_ZEN_META_FIELD_9_ARG = "contextMetaField9";
    public static final String MULTI_SLACK_ZEN_META_PROMPT_9_ARG = "contextMetaPrompt9";
    public static final String MULTI_SLACK_ZEN_META_FIELD_10_ARG = "contextMetaField10";
    public static final String MULTI_SLACK_ZEN_META_PROMPT_10_ARG = "contextMetaPrompt10";
    public static final String MULTI_SLACK_ZEN_META_FIELD_11_ARG = "contextMetaField11";
    public static final String MULTI_SLACK_ZEN_META_PROMPT_11_ARG = "contextMetaPrompt11";
    public static final String MULTI_SLACK_ZEN_META_FIELD_12_ARG = "contextMetaField12";
    public static final String MULTI_SLACK_ZEN_META_PROMPT_12_ARG = "contextMetaPrompt12";
    public static final String MULTI_SLACK_ZEN_META_FIELD_13_ARG = "contextMetaField13";
    public static final String MULTI_SLACK_ZEN_META_PROMPT_13_ARG = "contextMetaPrompt13";
    public static final String MULTI_SLACK_ZEN_META_FIELD_14_ARG = "contextMetaField14";
    public static final String MULTI_SLACK_ZEN_META_PROMPT_14_ARG = "contextMetaPrompt14";
    public static final String MULTI_SLACK_ZEN_META_FIELD_15_ARG = "contextMetaField15";
    public static final String MULTI_SLACK_ZEN_META_PROMPT_15_ARG = "contextMetaPrompt15";
    public static final String MULTI_SLACK_ZEN_META_FIELD_16_ARG = "contextMetaField16";
    public static final String MULTI_SLACK_ZEN_META_PROMPT_16_ARG = "contextMetaPrompt16";
    public static final String MULTI_SLACK_ZEN_META_FIELD_17_ARG = "contextMetaField17";
    public static final String MULTI_SLACK_ZEN_META_PROMPT_17_ARG = "contextMetaPrompt17";
    public static final String MULTI_SLACK_ZEN_META_FIELD_18_ARG = "contextMetaField18";
    public static final String MULTI_SLACK_ZEN_META_PROMPT_18_ARG = "contextMetaPrompt18";
    public static final String MULTI_SLACK_ZEN_META_FIELD_19_ARG = "contextMetaField19";
    public static final String MULTI_SLACK_ZEN_META_PROMPT_19_ARG = "contextMetaPrompt19";
    public static final String MULTI_SLACK_ZEN_META_FIELD_20_ARG = "contextMetaField20";
    public static final String MULTI_SLACK_ZEN_META_PROMPT_20_ARG = "contextMetaPrompt20";
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

        final MultiSlackZenGoogleConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> getContext(environmentSettings, prompt, arguments))
                .map(ragContext -> mergeContext(ragContext, modelConfig.getCalculatedModel(environmentSettings), parsedArgs))
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
                        API.Case(API.$(instanceOf(MissingResponse.class)), throwable -> new InternalFailure(throwable)),
                        API.Case(API.$(instanceOf(InvalidResponse.class)), throwable -> new ExternalFailure(throwable)),
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
    private List<RagDocumentContext<Void>> getEntityContext(
            final PositionalEntity positionalEntity,
            final Map<String, String> context,
            final String prompt,
            final MultiSlackZenGoogleConfig.LocalArguments parsedArgs) {
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

        final List<RagDocumentContext<Void>> planHatUsageContext = getPlanhatUsageContext(positionalEntity, parsedArgs, prompt, context);

        final List<RagDocumentContext<Void>> retValue = new ArrayList<>();
        retValue.addAll(slackKeywordSearch);
        retValue.addAll(slackContext);
        retValue.addAll(googleContext);
        retValue.addAll(zenContext);
        retValue.addAll(planHatContext);
        retValue.addAll(gongContext);
        retValue.addAll(planHatUsageContext);

        final List<RagDocumentContext<Void>> filteredContext = contextMeetsRating(validateSufficientContext(retValue, parsedArgs), entity.name(), parsedArgs);

        // Merge all the metadata from the context into a single list.
        final List<MetaObjectResult> metadata = retValue
                .stream()
                .flatMap(ragDoc -> ragDoc.getMetadata().stream())
                .toList();

        saveMetaResult(filteredContext, parsedArgs, metadata);

        return filteredContext;
    }

    private MetaObjectResult getContextCount(final List<RagDocumentContext<Void>> ragContext) {
        return new MetaObjectResult(
                "ContextCount",
                ragContext.size());
    }

    private List<MetaObjectResult> getMetaResults(final List<RagDocumentContext<Void>> ragContext, final MultiSlackZenGoogleConfig.LocalArguments parsedArgs) {
        final List<MetaObjectResult> results = new ArrayList<MetaObjectResult>();

        final List<Pair<String, String>> metaFields = List.of(
                Pair.of(parsedArgs.getMetaField1(), parsedArgs.getMetaPrompt1()),
                Pair.of(parsedArgs.getMetaField2(), parsedArgs.getMetaPrompt2()),
                Pair.of(parsedArgs.getMetaField3(), parsedArgs.getMetaPrompt3()),
                Pair.of(parsedArgs.getMetaField4(), parsedArgs.getMetaPrompt4()),
                Pair.of(parsedArgs.getMetaField5(), parsedArgs.getMetaPrompt5()),
                Pair.of(parsedArgs.getMetaField6(), parsedArgs.getMetaPrompt6()),
                Pair.of(parsedArgs.getMetaField7(), parsedArgs.getMetaPrompt7()),
                Pair.of(parsedArgs.getMetaField8(), parsedArgs.getMetaPrompt8()),
                Pair.of(parsedArgs.getMetaField9(), parsedArgs.getMetaPrompt9()),
                Pair.of(parsedArgs.getMetaField10(), parsedArgs.getMetaPrompt10()),
                Pair.of(parsedArgs.getMetaField11(), parsedArgs.getMetaPrompt11()),
                Pair.of(parsedArgs.getMetaField12(), parsedArgs.getMetaPrompt12()),
                Pair.of(parsedArgs.getMetaField13(), parsedArgs.getMetaPrompt13()),
                Pair.of(parsedArgs.getMetaField14(), parsedArgs.getMetaPrompt14()),
                Pair.of(parsedArgs.getMetaField15(), parsedArgs.getMetaPrompt15()),
                Pair.of(parsedArgs.getMetaField16(), parsedArgs.getMetaPrompt16()),
                Pair.of(parsedArgs.getMetaField17(), parsedArgs.getMetaPrompt17()),
                Pair.of(parsedArgs.getMetaField18(), parsedArgs.getMetaPrompt18()),
                Pair.of(parsedArgs.getMetaField19(), parsedArgs.getMetaPrompt19()),
                Pair.of(parsedArgs.getMetaField20(), parsedArgs.getMetaPrompt20())
        );

        for (final Pair<String, String> metaField : metaFields) {

            if (StringUtils.isNotBlank(metaField.getLeft()) && StringUtils.isNotBlank(metaField.getRight()) && !ragContext.isEmpty()) {
                final RagMultiDocumentContext<Void> multiRagDoc = new RagMultiDocumentContext<>(
                        ragContext.stream()
                                .map(ragDoc -> promptBuilderSelector
                                        .getPromptBuilder("plain")
                                        .buildContextPrompt(
                                                ragDoc.contextLabel(),
                                                ragDoc.document()))
                                .collect(Collectors.joining("\n")),
                        ragContext);

                final int value = Try.of(() -> ratingTool.call(
                                Map.of(RatingTool.RATING_DOCUMENT_CONTEXT_ARG, multiRagDoc.combinedDocument()),
                                metaField.getRight(),
                                List.of()).combinedDocument())
                        .map(rating -> org.apache.commons.lang3.math.NumberUtils.toInt(rating, 0))
                        .onFailure(ex -> logger.warning("Rating tool failed for " + metaField.getLeft() + ": " + exceptionHandler.getExceptionMessage(ex)))
                        // Meta-fields are a best effort, and we default to 0
                        .recover(ex -> 0)
                        .get();

                results.add(new MetaObjectResult(metaField.getLeft(), value));
            }
        }

        return results;
    }

    private void saveMetaResult(
            final List<RagDocumentContext<Void>> ragContext,
            final MultiSlackZenGoogleConfig.LocalArguments parsedArgs,
            final List<MetaObjectResult> additionalMetadata) {
        final List<MetaObjectResult> results = new ArrayList<MetaObjectResult>();

        if (StringUtils.isNotBlank(parsedArgs.getMetaReport())) {
            results.add(getContextCount(ragContext));
            results.addAll(getMetaResults(ragContext, parsedArgs));
            results.addAll(additionalMetadata);

            Try.run(() -> Files.write(Paths.get(parsedArgs.getMetaReport()),
                            jsonDeserializer.serialize(results).getBytes(),
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
                .onFailure(InternalFailure.class, ex -> logger.info("Slack keyword search failed, ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                .onFailure(ExternalFailure.class, ex -> logger.warning("Slack keyword search failed, ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                // If anything fails, get an empty list
                .getOrElse(List::of)
                // Post-process the rag context
                .stream()
                .map(ragDoc -> ragDoc.updateContextLabel(positionalEntity.entity().name() + " " + ragDoc.contextLabel()))
                .map(ragDoc -> ragDoc.updateGroup(positionalEntity.entity().name()))
                .map(RagDocumentContext::getRagDocumentContextVoid)
                // We filter here if there is a rating each individual content source must meet
                .filter(doc -> getContextRating(doc, parsedArgs) >= parsedArgs.getIndividualContextFilterMinimumRating())
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
                        .onFailure(InternalFailure.class, ex -> logger.info("Gong search failed ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .onFailure(ExternalFailure.class, ex -> logger.warning("Gong search failed ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .getOrElse(List::of)
                        .stream())
                // The context label is updated to include the entity name
                .map(ragDoc -> ragDoc.updateContextLabel(positionalEntity.entity().name() + " " + ragDoc.contextLabel()))
                .map(ragDoc -> ragDoc.updateGroup(positionalEntity.entity().name()))
                .map(RagDocumentContext::getRagDocumentContextVoid)
                // We filter here if there is a rating each individual content source must meet
                .filter(doc -> getContextRating(doc, parsedArgs) >= parsedArgs.getIndividualContextFilterMinimumRating())
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
                        .onFailure(InternalFailure.class, ex -> logger.info("Planhat search failed ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .onFailure(ExternalFailure.class, ex -> logger.warning("Planhat search failed ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .getOrElse(List::of)
                        .stream())
                // The context label is updated to include the entity name
                .map(ragDoc -> ragDoc.updateContextLabel(positionalEntity.entity().name() + " " + ragDoc.contextLabel()))
                .map(ragDoc -> ragDoc.updateGroup(positionalEntity.entity().name()))
                .map(RagDocumentContext::getRagDocumentContextVoid)
                // We filter here if there is a rating each individual content source must meet
                .filter(doc -> getContextRating(doc, parsedArgs) >= parsedArgs.getIndividualContextFilterMinimumRating())
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
                        .onFailure(ex -> logger.warning("Planhat usage failed ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .getOrElse(List::of)
                        .stream())
                // The context label is updated to include the entity name
                .map(ragDoc -> ragDoc.updateContextLabel(positionalEntity.entity().name() + " " + ragDoc.contextLabel()))
                .map(ragDoc -> ragDoc.updateGroup(positionalEntity.entity().name()))
                .map(RagDocumentContext::getRagDocumentContextVoid)
                // We filter here if there is a rating each individual content source must meet
                .filter(doc -> getContextRating(doc, parsedArgs) >= parsedArgs.getIndividualContextFilterMinimumRating())
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
                        .onFailure(InternalFailure.class, ex -> logger.info("Google doc failed, ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .onFailure(ExternalFailure.class, ex -> logger.warning("Google doc failed, ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .getOrElse(List::of)
                        .stream())
                // The context label is updated to include the entity name
                .map(ragDoc -> ragDoc.updateContextLabel(positionalEntity.entity().name() + " " + ragDoc.contextLabel()))
                .map(ragDoc -> ragDoc.updateGroup(positionalEntity.entity().name()))
                // We filter here if there is a rating each individual content source must meet
                .filter(doc -> getContextRating(doc, parsedArgs) >= parsedArgs.getIndividualContextFilterMinimumRating())
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
                        .onFailure(InternalFailure.class, ex -> logger.info("Slack channel failed, ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .onFailure(ExternalFailure.class, ex -> logger.warning("Slack channel failed, ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .getOrElse(List::of)
                        .stream())
                // The context label is updated to include the entity name
                .map(ragDoc -> ragDoc.updateContextLabel(positionalEntity.entity().name() + " " + ragDoc.contextLabel()))
                .map(ragDoc -> ragDoc.updateGroup(positionalEntity.entity().name()))
                // We filter here if there is a rating each individual content source must meet
                .filter(doc -> getContextRating(doc, parsedArgs) >= parsedArgs.getIndividualContextFilterMinimumRating())
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
                        .onFailure(InternalFailure.class, ex -> logger.info("ZenDesk search failed ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .onFailure(ExternalFailure.class, ex -> logger.warning("ZenDesk search failed ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .getOrElse(List::of)
                        .stream())
                // The context label is updated to include the entity name
                .map(ragDoc -> ragDoc.updateContextLabel(positionalEntity.entity().name() + " " + ragDoc.contextLabel()))
                .map(ragDoc -> ragDoc.updateGroup(positionalEntity.entity().name()))
                .map(RagDocumentContext::getRagDocumentContextVoid)
                // We filter here if there is a rating each individual content source must meet
                .filter(doc -> getContextRating(doc, parsedArgs) >= parsedArgs.getIndividualContextFilterMinimumRating())
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

    private Integer getContextRating(final RagDocumentContext<Void> context, final MultiSlackZenGoogleConfig.LocalArguments parsedArgs) {
        if (parsedArgs.getIndividualContextFilterMinimumRating() <= 0 || StringUtils.isBlank(parsedArgs.getIndividualContextFilterQuestion())) {
            return 10;
        }

        return Try.of(() -> ratingTool.call(
                        Map.of(RatingTool.RATING_DOCUMENT_CONTEXT_ARG, context.document()),
                        parsedArgs.getIndividualContextFilterQuestion(),
                        List.of()).combinedDocument())
                .map(rating -> org.apache.commons.lang3.math.NumberUtils.toInt(rating, 0))
                // Ratings are provided on a best effort basis, so we ignore any failures
                .recover(InternalFailure.class, ex -> 10)
                .get();

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

    private RagMultiDocumentContext<Void> mergeContext(final List<RagDocumentContext<Void>> context, final String customModel, final MultiSlackZenGoogleConfig.LocalArguments parsedArgs) {
        return new RagMultiDocumentContext<>(
                context.stream()
                        .map(ragDoc ->
                                promptBuilderSelector.getPromptBuilder(customModel).buildContextPrompt(
                                        ragDoc.contextLabel(), ragDoc.document()))
                        .collect(Collectors.joining("\n")),
                context,
                null,
                parsedArgs.getAnnotationPrefix());
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
    @ConfigProperty(name = "sb.multislackzengoogle.annotationPrefix")
    private Optional<String> configAnnotationPrefix;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.contextFilterQuestion")
    private Optional<String> configContextFilterQuestion;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.contextFilterMinimumRating")
    private Optional<String> configContextFilterMinimumRating;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.individualContextFilterQuestion")
    private Optional<String> configIndividualContextFilterQuestion;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.individualContextFilterMinimumRating")
    private Optional<String> configIndividualContextFilterMinimumRating;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaPrompt1")
    private Optional<String> configMetaPrompt1;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaField1")
    private Optional<String> configMetaField1;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaPrompt2")
    private Optional<String> configMetaPrompt2;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaField2")
    private Optional<String> configMetaField2;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaPrompt3")
    private Optional<String> configMetaPrompt3;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaField3")
    private Optional<String> configMetaField3;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaPrompt4")
    private Optional<String> configMetaPrompt4;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaField4")
    private Optional<String> configMetaField4;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaPrompt5")
    private Optional<String> configMetaPrompt5;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaField5")
    private Optional<String> configMetaField5;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaPrompt6")
    private Optional<String> configMetaPrompt6;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaField6")
    private Optional<String> configMetaField6;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaPrompt7")
    private Optional<String> configMetaPrompt7;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaField7")
    private Optional<String> configMetaField7;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaPrompt8")
    private Optional<String> configMetaPrompt8;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaField8")
    private Optional<String> configMetaField8;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaPrompt9")
    private Optional<String> configMetaPrompt9;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaField9")
    private Optional<String> configMetaField9;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaPrompt10")
    private Optional<String> configMetaPrompt10;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaField10")
    private Optional<String> configMetaField10;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaPrompt11")
    private Optional<String> configMetaPrompt11;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaField11")
    private Optional<String> configMetaField11;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaPrompt12")
    private Optional<String> configMetaPrompt12;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaField12")
    private Optional<String> configMetaField12;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaPrompt13")
    private Optional<String> configMetaPrompt13;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaField13")
    private Optional<String> configMetaField13;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaPrompt14")
    private Optional<String> configMetaPrompt14;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaField14")
    private Optional<String> configMetaField14;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaPrompt15")
    private Optional<String> configMetaPrompt15;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaField15")
    private Optional<String> configMetaField15;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaPrompt16")
    private Optional<String> configMetaPrompt16;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaField16")
    private Optional<String> configMetaField16;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaPrompt17")
    private Optional<String> configMetaPrompt17;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaField17")
    private Optional<String> configMetaField17;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaPrompt18")
    private Optional<String> configMetaPrompt18;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaField18")
    private Optional<String> configMetaField18;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaPrompt19")
    private Optional<String> configMetaPrompt19;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaField19")
    private Optional<String> configMetaField19;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaPrompt20")
    private Optional<String> configMetaPrompt20;

    @Inject
    @ConfigProperty(name = "sb.multislackzengoogle.metaField20")
    private Optional<String> configMetaField20;

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

    public Optional<String> getConfigMetaPrompt1() {
        return configMetaPrompt1;
    }

    public Optional<String> getConfigMetaField1() {
        return configMetaField1;
    }

    public Optional<String> getConfigMetaPrompt2() {
        return configMetaPrompt2;
    }

    public Optional<String> getConfigMetaField2() {
        return configMetaField2;
    }

    public Optional<String> getConfigMetaPrompt3() {
        return configMetaPrompt3;
    }

    public Optional<String> getConfigMetaField3() {
        return configMetaField3;
    }

    public Optional<String> getConfigMetaPrompt4() {
        return configMetaPrompt4;
    }

    public Optional<String> getConfigMetaField4() {
        return configMetaField4;
    }

    public Optional<String> getConfigMetaPrompt5() {
        return configMetaPrompt5;
    }

    public Optional<String> getConfigMetaField5() {
        return configMetaField5;
    }

    public Optional<String> getConfigMetaPrompt6() {
        return configMetaPrompt6;
    }

    public Optional<String> getConfigMetaField6() {
        return configMetaField6;
    }

    public Optional<String> getConfigMetaPrompt7() {
        return configMetaPrompt7;
    }

    public Optional<String> getConfigMetaField7() {
        return configMetaField7;
    }

    public Optional<String> getConfigMetaPrompt8() {
        return configMetaPrompt8;
    }

    public Optional<String> getConfigMetaField8() {
        return configMetaField8;
    }

    public Optional<String> getConfigMetaPrompt9() {
        return configMetaPrompt9;
    }

    public Optional<String> getConfigMetaField9() {
        return configMetaField9;
    }

    public Optional<String> getConfigMetaPrompt10() {
        return configMetaPrompt10;
    }

    public Optional<String> getConfigMetaField10() {
        return configMetaField10;
    }

    public Optional<String> getConfigMetaPrompt11() {
        return configMetaPrompt11;
    }

    public Optional<String> getConfigMetaField11() {
        return configMetaField11;
    }

    public Optional<String> getConfigMetaPrompt12() {
        return configMetaPrompt12;
    }

    public Optional<String> getConfigMetaField12() {
        return configMetaField12;
    }

    public Optional<String> getConfigMetaPrompt13() {
        return configMetaPrompt13;
    }

    public Optional<String> getConfigMetaField13() {
        return configMetaField13;
    }

    public Optional<String> getConfigMetaPrompt14() {
        return configMetaPrompt14;
    }

    public Optional<String> getConfigMetaField14() {
        return configMetaField14;
    }

    public Optional<String> getConfigMetaPrompt15() {
        return configMetaPrompt15;
    }

    public Optional<String> getConfigMetaField15() {
        return configMetaField15;
    }

    public Optional<String> getConfigMetaPrompt16() {
        return configMetaPrompt16;
    }

    public Optional<String> getConfigMetaField16() {
        return configMetaField16;
    }

    public Optional<String> getConfigMetaPrompt17() {
        return configMetaPrompt17;
    }

    public Optional<String> getConfigMetaField17() {
        return configMetaField17;
    }

    public Optional<String> getConfigMetaPrompt18() {
        return configMetaPrompt18;
    }

    public Optional<String> getConfigMetaField18() {
        return configMetaField18;
    }

    public Optional<String> getConfigMetaPrompt19() {
        return configMetaPrompt19;
    }

    public Optional<String> getConfigMetaField19() {
        return configMetaField19;
    }

    public Optional<String> getConfigMetaPrompt20() {
        return configMetaPrompt20;
    }

    public Optional<String> getConfigMetaField20() {
        return configMetaField20;
    }

    public Optional<String> getConfigIndividualContextFilterQuestion() {
        return configIndividualContextFilterQuestion;
    }

    public Optional<String> getConfigIndividualContextFilterMinimumRating() {
        return configIndividualContextFilterMinimumRating;
    }

    public Optional<String> getConfigAnnotationPrefix() {
        return configAnnotationPrefix;
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

        public String getAnnotationPrefix() {
            return getArgsAccessor().getArgument(
                    getConfigAnnotationPrefix()::get,
                    arguments,
                    context,
                    MultiSlackZenGoogle.MULTI_SLACK_ZEN_MAX_ANNOTATION_PREFIX_ARG,
                    "multislackzengoogle_annotation_prefix",
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

        public String getIndividualContextFilterQuestion() {
            return getArgsAccessor().getArgument(
                            getConfigIndividualContextFilterQuestion()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_INDIVIDUAL_CONTEXT_FILTER_QUESTION_ARG,
                            "multislackzengoogle_context_filter_question",
                            "")
                    .value();
        }

        public Integer getIndividualContextFilterMinimumRating() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigIndividualContextFilterMinimumRating()::get,
                    arguments,
                    context,
                    MultiSlackZenGoogle.MULTI_SLACK_ZEN_INDIVIDUAL_CONTEXT_FILTER_MINIMUM_RATING_ARG,
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

        public String getMetaField1() {
            return getArgsAccessor().getArgument(
                            getConfigMetaField1()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_FIELD_1_ARG,
                            "multislackzengoogle_meta_field_1",
                            "")
                    .value();
        }

        public String getMetaPrompt1() {
            return getArgsAccessor().getArgument(
                            getConfigMetaPrompt1()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_PROMPT_1_ARG,
                            "multislackzengoogle_meta_prompt_1",
                            "")
                    .value();
        }

        public String getMetaField2() {
            return getArgsAccessor().getArgument(
                            getConfigMetaField2()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_FIELD_2_ARG,
                            "multislackzengoogle_meta_field_2",
                            "")
                    .value();
        }

        public String getMetaPrompt2() {
            return getArgsAccessor().getArgument(
                            getConfigMetaPrompt2()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_PROMPT_2_ARG,
                            "multislackzengoogle_meta_prompt_2",
                            "")
                    .value();
        }

        public String getMetaField3() {
            return getArgsAccessor().getArgument(
                            getConfigMetaField3()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_FIELD_3_ARG,
                            "multislackzengoogle_meta_field_3",
                            "")
                    .value();
        }

        public String getMetaPrompt3() {
            return getArgsAccessor().getArgument(
                            getConfigMetaPrompt3()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_PROMPT_3_ARG,
                            "multislackzengoogle_meta_prompt_3",
                            "")
                    .value();
        }

        public String getMetaField4() {
            return getArgsAccessor().getArgument(
                            getConfigMetaField4()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_FIELD_4_ARG,
                            "multislackzengoogle_meta_field_4",
                            "")
                    .value();
        }

        public String getMetaPrompt4() {
            return getArgsAccessor().getArgument(
                            getConfigMetaPrompt4()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_PROMPT_4_ARG,
                            "multislackzengoogle_meta_prompt_4",
                            "")
                    .value();
        }

        public String getMetaField5() {
            return getArgsAccessor().getArgument(
                            getConfigMetaField5()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_FIELD_5_ARG,
                            "multislackzengoogle_meta_field_5",
                            "")
                    .value();
        }

        public String getMetaPrompt5() {
            return getArgsAccessor().getArgument(
                            getConfigMetaPrompt5()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_PROMPT_5_ARG,
                            "multislackzengoogle_meta_prompt_5",
                            "")
                    .value();
        }

        public String getMetaField6() {
            return getArgsAccessor().getArgument(
                            getConfigMetaField6()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_FIELD_6_ARG,
                            "multislackzengoogle_meta_field_6",
                            "")
                    .value();
        }

        public String getMetaPrompt6() {
            return getArgsAccessor().getArgument(
                            getConfigMetaPrompt6()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_PROMPT_6_ARG,
                            "multislackzengoogle_meta_prompt_6",
                            "")
                    .value();
        }

        public String getMetaField7() {
            return getArgsAccessor().getArgument(
                            getConfigMetaField7()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_FIELD_7_ARG,
                            "multislackzengoogle_meta_field_7",
                            "")
                    .value();
        }

        public String getMetaPrompt7() {
            return getArgsAccessor().getArgument(
                            getConfigMetaPrompt7()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_PROMPT_7_ARG,
                            "multislackzengoogle_meta_prompt_7",
                            "")
                    .value();
        }

        public String getMetaField8() {
            return getArgsAccessor().getArgument(
                            getConfigMetaField8()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_FIELD_8_ARG,
                            "multislackzengoogle_meta_field_8",
                            "")
                    .value();
        }

        public String getMetaPrompt8() {
            return getArgsAccessor().getArgument(
                            getConfigMetaPrompt8()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_PROMPT_8_ARG,
                            "multislackzengoogle_meta_prompt_8",
                            "")
                    .value();
        }

        public String getMetaField9() {
            return getArgsAccessor().getArgument(
                            getConfigMetaField9()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_FIELD_9_ARG,
                            "multislackzengoogle_meta_field_9",
                            "")
                    .value();
        }

        public String getMetaPrompt9() {
            return getArgsAccessor().getArgument(
                            getConfigMetaPrompt9()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_PROMPT_9_ARG,
                            "multislackzengoogle_meta_prompt_9",
                            "")
                    .value();
        }

        public String getMetaField10() {
            return getArgsAccessor().getArgument(
                            getConfigMetaField10()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_FIELD_10_ARG,
                            "multislackzengoogle_meta_field_10",
                            "")
                    .value();
        }

        public String getMetaPrompt10() {
            return getArgsAccessor().getArgument(
                            getConfigMetaPrompt10()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_PROMPT_10_ARG,
                            "multislackzengoogle_meta_prompt_10",
                            "")
                    .value();
        }

        public String getMetaField11() {
            return getArgsAccessor().getArgument(
                            getConfigMetaField11()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_FIELD_11_ARG,
                            "multislackzengoogle_meta_field_11",
                            "")
                    .value();
        }

        public String getMetaPrompt11() {
            return getArgsAccessor().getArgument(
                            getConfigMetaPrompt11()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_PROMPT_11_ARG,
                            "multislackzengoogle_meta_prompt_11",
                            "")
                    .value();
        }

        public String getMetaField12() {
            return getArgsAccessor().getArgument(
                            getConfigMetaField12()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_FIELD_12_ARG,
                            "multislackzengoogle_meta_field_12",
                            "")
                    .value();
        }

        public String getMetaPrompt12() {
            return getArgsAccessor().getArgument(
                            getConfigMetaPrompt12()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_PROMPT_12_ARG,
                            "multislackzengoogle_meta_prompt_12",
                            "")
                    .value();
        }

        public String getMetaField13() {
            return getArgsAccessor().getArgument(
                            getConfigMetaField13()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_FIELD_13_ARG,
                            "multislackzengoogle_meta_field_13",
                            "")
                    .value();
        }

        public String getMetaPrompt13() {
            return getArgsAccessor().getArgument(
                            getConfigMetaPrompt13()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_PROMPT_13_ARG,
                            "multislackzengoogle_meta_prompt_13",
                            "")
                    .value();
        }

        public String getMetaField14() {
            return getArgsAccessor().getArgument(
                            getConfigMetaField14()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_FIELD_14_ARG,
                            "multislackzengoogle_meta_field_14",
                            "")
                    .value();
        }

        public String getMetaPrompt14() {
            return getArgsAccessor().getArgument(
                            getConfigMetaPrompt14()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_PROMPT_14_ARG,
                            "multislackzengoogle_meta_prompt_14",
                            "")
                    .value();
        }

        public String getMetaField15() {
            return getArgsAccessor().getArgument(
                            getConfigMetaField15()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_FIELD_15_ARG,
                            "multislackzengoogle_meta_field_15",
                            "")
                    .value();
        }

        public String getMetaPrompt15() {
            return getArgsAccessor().getArgument(
                            getConfigMetaPrompt15()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_PROMPT_15_ARG,
                            "multislackzengoogle_meta_prompt_15",
                            "")
                    .value();
        }

        public String getMetaField16() {
            return getArgsAccessor().getArgument(
                            getConfigMetaField16()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_FIELD_16_ARG,
                            "multislackzengoogle_meta_field_16",
                            "")
                    .value();
        }

        public String getMetaPrompt16() {
            return getArgsAccessor().getArgument(
                            getConfigMetaPrompt16()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_PROMPT_16_ARG,
                            "multislackzengoogle_meta_prompt_16",
                            "")
                    .value();
        }

        public String getMetaField17() {
            return getArgsAccessor().getArgument(
                            getConfigMetaField17()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_FIELD_17_ARG,
                            "multislackzengoogle_meta_field_17",
                            "")
                    .value();
        }

        public String getMetaPrompt17() {
            return getArgsAccessor().getArgument(
                            getConfigMetaPrompt17()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_PROMPT_17_ARG,
                            "multislackzengoogle_meta_prompt_17",
                            "")
                    .value();
        }

        public String getMetaField18() {
            return getArgsAccessor().getArgument(
                            getConfigMetaField18()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_FIELD_18_ARG,
                            "multislackzengoogle_meta_field_18",
                            "")
                    .value();
        }

        public String getMetaPrompt18() {
            return getArgsAccessor().getArgument(
                            getConfigMetaPrompt18()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_PROMPT_18_ARG,
                            "multislackzengoogle_meta_prompt_18",
                            "")
                    .value();
        }

        public String getMetaField19() {
            return getArgsAccessor().getArgument(
                            getConfigMetaField19()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_FIELD_19_ARG,
                            "multislackzengoogle_meta_field_19",
                            "")
                    .value();
        }

        public String getMetaPrompt19() {
            return getArgsAccessor().getArgument(
                            getConfigMetaPrompt19()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_PROMPT_19_ARG,
                            "multislackzengoogle_meta_prompt_19",
                            "")
                    .value();
        }

        public String getMetaField20() {
            return getArgsAccessor().getArgument(
                            getConfigMetaField20()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_FIELD_20_ARG,
                            "multislackzengoogle_meta_field_20",
                            "")
                    .value();
        }

        public String getMetaPrompt20() {
            return getArgsAccessor().getArgument(
                            getConfigMetaPrompt20()::get,
                            arguments,
                            context,
                            MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_PROMPT_20_ARG,
                            "multislackzengoogle_meta_prompt_20",
                            "")
                    .value();
        }
    }
}

