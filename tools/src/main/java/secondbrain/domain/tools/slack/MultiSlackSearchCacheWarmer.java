package secondbrain.domain.tools.slack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.collect.ImmutableList;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptionhandling.ExceptionHandler;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.reader.FileReader;
import secondbrain.domain.tooldefs.MetaObjectResult;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.yaml.YamlDeserializer;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static com.pivovarit.collectors.ParallelCollectors.Batching.parallelToStream;


/**
 * This tool doesn't generate any output. Instead, it is expected to be run as a way to keep the
 * cache warm. Run this tool regularly before running tools like MultiSlackZenGoogle to ensure
 * Slack API calls can be executed quickly.
 */
@ApplicationScoped
public class MultiSlackSearchCacheWarmer implements Tool<Void> {

    public static final String MULTI_SLACK_SEARCH_CACHE_WARMER_URL_ARG = "url";
    public static final String MULTI_SLACK_SEARCH_CACHE_WARMER_MAX_ENTITIES_ARG = "maxEntities";
    private static final int BATCH_SIZE = 10;

    private static final AtomicInteger COUNTER = new AtomicInteger(0);

    @Inject
    private SlackSearch slackSearch;

    @Inject
    private YamlDeserializer yamlDeserializer;

    @Inject
    private FileReader fileReader;

    @Inject
    private MultiSlackSearchCacheWarmerConfig config;

    @Inject
    private ExceptionHandler exceptionHandler;

    @Inject
    private Logger log;

    @Inject
    private Logger logger;

    @Override
    public String getName() {
        return MultiSlackSearchCacheWarmer.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return """
                Warms the slack cache by searching for keywords in slack messages.
                """.stripIndent();
    }

    @Override
    public List<ToolArguments> getArguments() {
        return ImmutableList.of(
                new ToolArguments(MULTI_SLACK_SEARCH_CACHE_WARMER_URL_ARG, "The entity directory URL", ""),
                new ToolArguments(MULTI_SLACK_SEARCH_CACHE_WARMER_MAX_ENTITIES_ARG, "The optional maximum number of entities to process", "0"));
    }

    @Override
    public List<RagDocumentContext<Void>> getContext(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final MultiSlackSearchCacheWarmerConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final EntityDirectory entityDirectory = Try.of(() -> fileReader.read(parsedArgs.getUrl()))
                .map(file -> yamlDeserializer.deserialize(file, EntityDirectory.class))
                .getOrElseThrow(ex -> new ExternalFailure("Failed to download or parse the entity directory", ex));

        final Executor executor = Executors.newVirtualThreadPerTaskExecutor();

        return entityDirectory.getPositionalEntities()
                .stream()
                .limit(parsedArgs.getMaxEntities() == 0 ? Long.MAX_VALUE : parsedArgs.getMaxEntities())
                .collect(parallelToStream(entity -> getEntityContext(entity, environmentSettings, prompt, parsedArgs).stream(), executor, BATCH_SIZE))
                .flatMap(stream -> stream)
                .toList();
    }

    @Override
    public List<MetaObjectResult> getMetadata(Map<String, String> environmentSettings, String prompt, List<ToolArgs> arguments) {
        return List.of();
    }

    @Override
    public RagMultiDocumentContext<Void> call(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        return new RagMultiDocumentContext<>("Cache has been warmed", getContext(environmentSettings, prompt, arguments));
    }


    @Override
    public String getContextLabel() {
        return "Unused";
    }


    private List<RagDocumentContext<Void>> getEntityContext(final PositionalEntity positionalEntity, final Map<String, String> context, final String prompt, final MultiSlackSearchCacheWarmerConfig.LocalArguments parsedArgs) {
        final Entity entity = positionalEntity.entity();

        logger.info("Getting Slack keywords for " + positionalEntity.entity().name() + " " + positionalEntity.position + " of " + positionalEntity.total);
        logger.info("Percent complete: " + ((float) COUNTER.incrementAndGet() / positionalEntity.total * 100) + "%");

        if (entity.disabled()) {
            return List.of();
        }

        // Slack searches use AND logic. This means we need to search each of the IDs (i.e. salesforce and planhat) separately.
        return CollectionUtils.collate(positionalEntity.entity().getSalesforce(), positionalEntity.entity().getPlanHat())
                .stream()
                .flatMap(id -> getSlackKeywordContext(positionalEntity, parsedArgs, prompt, context, id).stream())
                .toList();
    }


    private List<RagDocumentContext<Void>> getSlackKeywordContext(final PositionalEntity positionalEntity, final MultiSlackSearchCacheWarmerConfig.LocalArguments parsedArgs, final String prompt, final Map<String, String> context, final String id) {
        return Try
                .of(() -> List.of(new ToolArgs(SlackSearch.SLACK_SEARCH_KEYWORDS_ARG, id, true)))
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
                .map(RagDocumentContext::getRagDocumentContextVoid)
                .toList();
    }

    private Map<String, String> addItemToMap(@Nullable final Map<String, String> map, final String key, final String value) {
        final Map<String, String> result = map == null ? new HashMap<>() : new HashMap<>(map);

        if (key != null) {
            result.put(key, value);
        }

        return result;
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
class MultiSlackSearchCacheWarmerConfig {

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    @ConfigProperty(name = "sb.multislacksearchcachewarmer.url")
    private Optional<String> configUrl;


    @Inject
    @ConfigProperty(name = "sb.multislacksearchcachewarmer.maxentities")
    private Optional<String> configMaxEntities;


    public Optional<String> getConfigUrl() {
        return configUrl;
    }

    public Optional<String> getConfigMaxEntities() {
        return configMaxEntities;
    }


    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
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
                    MultiSlackSearchCacheWarmer.MULTI_SLACK_SEARCH_CACHE_WARMER_URL_ARG,
                    "multislacksearchcachewarmer_url",
                    "").value();
        }


        public int getMaxEntities() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigMaxEntities()::get,
                    arguments,
                    context,
                    MultiSlackSearchCacheWarmer.MULTI_SLACK_SEARCH_CACHE_WARMER_MAX_ENTITIES_ARG,
                    "multislacksearchcachewarmer_max_entities",
                    "0").value();

            return Try.of(() -> Integer.parseInt(stringValue))
                    .recover(throwable -> 0)
                    .map(i -> Math.max(0, i))
                    .get();
        }
    }
}
