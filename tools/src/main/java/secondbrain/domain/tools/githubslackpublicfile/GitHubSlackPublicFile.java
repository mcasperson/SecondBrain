package secondbrain.domain.tools.githubslackpublicfile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptionhandling.ExceptionHandler;
import secondbrain.domain.exceptions.*;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.reader.FileReader;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.tools.github.GitHubDiffs;
import secondbrain.domain.tools.github.GitHubIssues;
import secondbrain.domain.tools.slack.SlackChannel;
import secondbrain.domain.yaml.YamlDeserializer;
import secondbrain.infrastructure.llm.LlmClient;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Predicates.instanceOf;
import static com.pivovarit.collectors.ParallelCollectors.Batching.parallelToStream;

@ApplicationScoped
public class GitHubSlackPublicFile implements Tool<Void> {

    public static final String GITHUB_SLACK_PUBLICFILE_ENTITY_NAME_ARG = "entityName";
    public static final String GITHUB_SLACK_PUBLICFILE_URL_ARG = "url";
    public static final String GITHUB_SLACK_PUBLICFILE_DAYS_ARG = "days";
    public static final String GITHUB_SLACK_PUBLICFILE_CONTEXT_FILTER_QUESTION_ARG = "individualContextFilterQuestion";
    public static final String GITHUB_SLACK_PUBLICFILE_CONTEXT_FILTER_MINIMUM_RATING_ARG = "individualContextFilterMinimumRating";
    public static final String GITHUB_SLACK_PUBLICFILE_CONTEXT_SUMMARY_PROMPT_ARG = "individualContextSummaryPrompt";
    public static final String GITHUB_SLACK_PUBLICFILE_ADDITIONAL_SYSTEM_PROMPT = "additionalSystemPrompt";
    public static final String GITHUB_SLACK_PUBLICFILE_STRIP_MARKDOWN_CODE_BLOCK = "stripMarkdownCodeBlock";
    public static final String GITHUB_SLACK_PUBLICFILE_ANNOTATION_PREFIX_ARG = "annotationPrefix";
    public static final String GITHUB_SLACK_PUBLICFILE_META_REPORT_ARG = "metaReport";
    private static final int BATCH_SIZE = 10;
    private static final String INSTRUCTIONS = """
            You are helpful agent.
            You are given the contents of a multiple Slack channels, GitHub issues, and public web pages.
            You must answer the prompt based on the information provided.
            """;

    @Inject
    private GitHubIssues gitHubIssues;

    @Inject
    private GitHubDiffs gitHubDiffs;

    @Inject
    private SlackChannel slackChannel;

    @Inject
    private GitHubSlackPublicFileConfig config;

    @Inject
    private Logger logger;

    @Inject
    private ExceptionHandler exceptionHandler;

    @Inject
    private YamlDeserializer yamlDeserializer;

    @Inject
    private FileReader fileReader;

    @Inject
    @Preferred
    private LlmClient llmClient;

    @Override
    public String getName() {
        return GitHubSlackPublicFile.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Queries GitHub Issues, Slack channels, and public files to find relevant information.";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return List.of();
    }

    @Override
    public List<RagDocumentContext<Void>> getContext(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        final GitHubSlackPublicFileConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final EntityDirectory entityDirectory = Try.of(() -> fileReader.read(parsedArgs.getUrl()))
                .map(file -> yamlDeserializer.deserialize(file, EntityDirectory.class))
                .getOrElseThrow(ex -> new ExternalFailure("Failed to download or parse the entity directory", ex));

        final Executor executor = Executors.newVirtualThreadPerTaskExecutor();

        final List<RagDocumentContext<Void>> ragContext = entityDirectory.getEntities()
                .stream()
                .filter(entity -> parsedArgs.getEntityName().isEmpty() || parsedArgs.getEntityName().contains(entity.name.toLowerCase()))
                // This needs java 24 to be useful with HTTP clients like RESTEasy: https://github.com/orgs/resteasy/discussions/4300
                // We batch here to interleave API requests to the various external data sources
                .collect(parallelToStream(entity -> getEntityContext(entity, environmentSettings, prompt, parsedArgs).stream(), executor, BATCH_SIZE))
                .flatMap(stream -> stream)
                // We want the context sorted back into a predictable order to avoid a cache miss due to the contents of the system prompt changing
                .sorted(Comparator.comparing(RagDocumentContext::tool))
                .toList();

        if (ragContext.isEmpty()) {
            throw new InsufficientContext("No GitHub issues, GitHub diffs, or Slack messages found.");
        }

        return ragContext;
    }

    @Override
    public RagMultiDocumentContext<Void> call(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        logger.log(Level.INFO, "Calling " + getName());

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> getContext(environmentSettings, prompt, arguments))
                .map(ragContext -> new RagMultiDocumentContext<>(
                        prompt,
                        INSTRUCTIONS,
                        ragContext))
                .map(ragDoc -> llmClient.callWithCache(
                        ragDoc,
                        environmentSettings,
                        getName()));

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

    @Override
    public String getContextLabel() {
        return "Unused";
    }

    private List<RagDocumentContext<Void>> getEntityContext(
            final Entity entity,
            final Map<String, String> context,
            final String prompt,
            final GitHubSlackPublicFileConfig.LocalArguments parsedArgs) {

        final String promptOverride = StringUtils.isNotBlank(entity.prompt) ? entity.prompt : prompt;

        final List<RagDocumentContext<Void>> contexts = new ArrayList<>();
        contexts.addAll(getSlackContext(entity, parsedArgs, promptOverride, context));
        contexts.addAll(getGitHubContext(entity, parsedArgs, promptOverride, context));
        contexts.addAll(getGitHubDiffContext(entity, parsedArgs, promptOverride, context));
        return contexts;
    }

    private List<RagDocumentContext<Void>> getSlackContext(final Entity entity, final GitHubSlackPublicFileConfig.LocalArguments parsedArgs, final String prompt, final Map<String, String> context) {
        logger.log(Level.INFO, "Getting Slack channels for " + String.join(", "), entity.getSlack());
        return entity.getSlack()
                .stream()
                .filter(StringUtils::isNotBlank)
                .map(id -> List.of(
                        new ToolArgs(SlackChannel.SLACK_CHANNEL_FILTER_QUESTION_ARG, parsedArgs.getIndividualContextFilterQuestion(), true),
                        new ToolArgs(SlackChannel.SLACK_CHANNEL_FILTER_MINIMUM_RATING_ARG, parsedArgs.getIndividualContextFilterMinimumRating() + "", true),
                        new ToolArgs(SlackChannel.SLACK_SUMMARIZE_DOCUMENT_ARG, "" + !parsedArgs.getIndividualContextSummaryPrompt().isBlank(), true),
                        new ToolArgs(SlackChannel.SLACK_SUMMARIZE_DOCUMENT_PROMPT_ARG, parsedArgs.getIndividualContextSummaryPrompt(), true),
                        new ToolArgs(SlackChannel.SLACK_CHANEL_ARG, id, true),
                        new ToolArgs(SlackChannel.DAYS_ARG, "" + parsedArgs.getDays(), true)))
                .flatMap(args -> Try.of(() -> slackChannel.getContext(
                                context,
                                prompt,
                                args))
                        .onFailure(InternalFailure.class, ex -> logger.warning("Slack channel failed, ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .onFailure(ExternalFailure.class, ex -> logger.warning("Slack channel failed, ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .getOrElse(List::of)
                        .stream())
                .toList();
    }

    private List<RagDocumentContext<Void>> getGitHubContext(final Entity entity, final GitHubSlackPublicFileConfig.LocalArguments parsedArgs, final String prompt, final Map<String, String> context) {
        logger.log(Level.INFO, "Getting GitHub issues for " + entity.getIssues());
        return entity.getIssues()
                .stream()
                .map(id -> List.of(
                        new ToolArgs(GitHubIssues.GITHUB_ISSUE_FILTER_QUESTION_ARG, parsedArgs.getIndividualContextFilterQuestion(), true),
                        new ToolArgs(GitHubIssues.GITHUB_ISSUE_FILTER_MINIMUM_RATING_ARG, parsedArgs.getIndividualContextFilterMinimumRating() + "", true),
                        new ToolArgs(GitHubIssues.GITHUB_SUMMARIZE_ISSUE_ARG, "" + !id.getIndividualPromptOrDefault(parsedArgs.getIndividualContextSummaryPrompt()).isBlank(), true),
                        new ToolArgs(GitHubIssues.GITHUB_ISSUE_SUMMARY_PROMPT_ARG, id.getIndividualPromptOrDefault(parsedArgs.getIndividualContextSummaryPrompt()), true),
                        new ToolArgs(GitHubIssues.GITHUB_ORGANIZATION_ARG, id.organization(), true),
                        new ToolArgs(GitHubIssues.GITHUB_REPO_ARG, id.repository(), true),
                        new ToolArgs(GitHubIssues.GITHUB_DAYS_ARG, "" + parsedArgs.getDays(), true))

                )
                .flatMap(args -> Try.of(() -> gitHubIssues.getContext(context, prompt, args))
                        .onFailure(InternalFailure.class, ex -> logger.warning("GitHub issues failed, ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .onFailure(ExternalFailure.class, ex -> logger.warning("GitHub issues failed, ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .getOrElse(List::of)
                        .stream())
                .map(RagDocumentContext::getRagDocumentContextVoid)
                .toList();
    }

    private List<RagDocumentContext<Void>> getGitHubDiffContext(final Entity entity, final GitHubSlackPublicFileConfig.LocalArguments parsedArgs, final String prompt, final Map<String, String> context) {
        logger.log(Level.INFO, "Getting GitHub diffs for " + entity.getRepos());
        return entity.getRepos()
                .stream()
                .map(id ->
                        List.of(
                                new ToolArgs(GitHubDiffs.GITHUB_DIFF_SUMMARIZE_ARG, !id.getIndividualPromptOrDefault(parsedArgs.getIndividualContextSummaryPrompt()).isBlank() + "", true),
                                new ToolArgs(GitHubDiffs.GITHUB_DIFF_SUMMARY_PROMPT_ARG, id.getIndividualPromptOrDefault(parsedArgs.getIndividualContextSummaryPrompt()), true),
                                new ToolArgs("owner", id.organization(), true),
                                new ToolArgs("repo", id.repository(), true),
                                new ToolArgs("days", "" + parsedArgs.getDays(), true)))
                .flatMap(args -> Try.of(() -> gitHubDiffs.getContext(
                                context,
                                prompt,
                                args))
                        .onFailure(InternalFailure.class, ex -> logger.warning("GitHub diffs failed, ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .onFailure(ExternalFailure.class, ex -> logger.warning("GitHub diffs failed, ignoring: " + exceptionHandler.getExceptionMessage(ex)))
                        .getOrElse(List::of)
                        .stream())
                .map(RagDocumentContext::getRagDocumentContextVoid)
                .toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EntityDirectory(List<Entity> entities) {
        public List<Entity> getEntities() {
            return Objects.requireNonNullElse(entities, List.of());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Entity(String name, String prompt, List<String> slack, List<GitHubRepo> repos, List<GitHubRepo> issues,
                  boolean disabled) {
        public List<String> getSlack() {
            return Objects.requireNonNullElse(slack, List.<String>of())
                    .stream()
                    .filter(StringUtils::isNotBlank)
                    .toList();
        }

        public List<GitHubRepo> getRepos() {
            return Objects.requireNonNullElse(repos, List.<GitHubRepo>of())
                    .stream()
                    .filter(GitHubRepo::isValid)
                    .toList();
        }

        public List<GitHubRepo> getIssues() {
            return Objects.requireNonNullElse(issues, List.<GitHubRepo>of())
                    .stream()
                    .filter(GitHubRepo::isValid)
                    .toList();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GitHubRepo(String organization, String repository, String individualPrompt) {
        public boolean isValid() {
            return StringUtils.isNotBlank(organization) && StringUtils.isNotBlank(repository);
        }

        public String getIndividualPromptOrDefault(final String defaultPrompt) {
            if (StringUtils.isBlank(individualPrompt)) {
                return defaultPrompt;
            }
            return individualPrompt;
        }
    }
}

@ApplicationScoped
class GitHubSlackPublicFileConfig {

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    @ConfigProperty(name = "sb.githubslackpublicfile.url")
    private Optional<String> configUrl;

    @Inject
    @ConfigProperty(name = "sb.githubslackpublicfile.days")
    private Optional<String> configDays;

    @Inject
    @ConfigProperty(name = "sb.githubslackpublicfile.individualContextFilterQuestion")
    private Optional<String> configIndividualContextFilterQuestion;

    @Inject
    @ConfigProperty(name = "sb.githubslackpublicfile.individualContextFilterMinimumRating")
    private Optional<String> configIndividualContextFilterMinimumRating;

    @Inject
    @ConfigProperty(name = "sb.githubslackpublicfile.individualContextSummaryPrompt")
    private Optional<String> configIndividualContextSummaryPrompt;

    @Inject
    @ConfigProperty(name = "sb.githubslackpublicfile.additionalSystemPrompt")
    private Optional<String> configAdditionalSystemPrompt;

    @Inject
    @ConfigProperty(name = "sb.githubslackpublicfile.stripMarkdownCodeBlock")
    private Optional<String> configStripMarkdownCodeBlock;

    @Inject
    @ConfigProperty(name = "sb.githubslackpublicfile.annotationPrefix")
    private Optional<String> configAnnotationPrefix;

    @Inject
    @ConfigProperty(name = "sb.githubslackpublicfile.metaReport")
    private Optional<String> configMetaReport;

    @Inject
    @ConfigProperty(name = "sb.githubslackpublicfile.entity")
    private Optional<String> configEntity;

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    public Optional<String> getConfigUrl() {
        return configUrl;
    }

    public Optional<String> getConfigDays() {
        return configDays;
    }

    public Optional<String> getConfigIndividualContextFilterQuestion() {
        return configIndividualContextFilterQuestion;
    }

    public Optional<String> getConfigIndividualContextFilterMinimumRating() {
        return configIndividualContextFilterMinimumRating;
    }

    public Optional<String> getConfigIndividualContextSummaryPrompt() {
        return configIndividualContextSummaryPrompt;
    }

    public Optional<String> getConfigAdditionalSystemPrompt() {
        return configAdditionalSystemPrompt;
    }

    public Optional<String> getConfigStripMarkdownCodeBlock() {
        return configStripMarkdownCodeBlock;
    }

    public Optional<String> getConfigAnnotationPrefix() {
        return configAnnotationPrefix;
    }

    public Optional<String> getConfigMetaReport() {
        return configMetaReport;
    }


    public Optional<String> getConfigEntity() {
        return configEntity;
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
                    GitHubSlackPublicFile.GITHUB_SLACK_PUBLICFILE_URL_ARG,
                    "multislackzengoogle_url",
                    "").value();
        }

        public int getDays() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigDays()::get,
                    arguments,
                    context,
                    GitHubSlackPublicFile.GITHUB_SLACK_PUBLICFILE_DAYS_ARG,
                    "githubslackpublicfile_days",
                    "0").value();

            return Try.of(() -> Integer.parseInt(stringValue))
                    .recover(throwable -> 0)
                    .map(i -> Math.max(0, i))
                    .get();
        }

        public String getIndividualContextFilterQuestion() {
            return getArgsAccessor().getArgument(
                            getConfigIndividualContextFilterQuestion()::get,
                            arguments,
                            context,
                            GitHubSlackPublicFile.GITHUB_SLACK_PUBLICFILE_CONTEXT_FILTER_QUESTION_ARG,
                            "githubslackpublicfile_context_filter_question",
                            "")
                    .value();
        }

        public Integer getIndividualContextFilterMinimumRating() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigIndividualContextFilterMinimumRating()::get,
                    arguments,
                    context,
                    GitHubSlackPublicFile.GITHUB_SLACK_PUBLICFILE_CONTEXT_FILTER_MINIMUM_RATING_ARG,
                    "githubslackpublicfile_context_filter_minimum_rating",
                    "0");

            return org.apache.commons.lang.math.NumberUtils.toInt(argument.value(), 0);
        }

        public String getIndividualContextSummaryPrompt() {
            return getArgsAccessor().getArgument(
                            getConfigIndividualContextSummaryPrompt()::get,
                            arguments,
                            context,
                            GitHubSlackPublicFile.GITHUB_SLACK_PUBLICFILE_CONTEXT_SUMMARY_PROMPT_ARG,
                            "githubslackpublicfile_context_summary_prompt",
                            "")
                    .value();
        }

        public String getAdditionalSystemPrompt() {
            return getArgsAccessor().getArgument(
                            getConfigAdditionalSystemPrompt()::get,
                            arguments,
                            context,
                            GitHubSlackPublicFile.GITHUB_SLACK_PUBLICFILE_ADDITIONAL_SYSTEM_PROMPT,
                            "githubslackpublicfile_additional_system_prompt",
                            "")
                    .value();
        }

        public Boolean getStripMarkdownCodeBlock() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigStripMarkdownCodeBlock()::get,
                    arguments,
                    context,
                    GitHubSlackPublicFile.GITHUB_SLACK_PUBLICFILE_STRIP_MARKDOWN_CODE_BLOCK,
                    "githubslackpublicfile_strip_markdown_code_block",
                    "false");

            return BooleanUtils.toBoolean(argument.value());
        }

        public String getAnnotationPrefix() {
            return getArgsAccessor().getArgument(
                            getConfigAnnotationPrefix()::get,
                            arguments,
                            context,
                            GitHubSlackPublicFile.GITHUB_SLACK_PUBLICFILE_ANNOTATION_PREFIX_ARG,
                            "githubslackpublicfile_annotation_prefix",
                            "")
                    .value();
        }

        public String getMetaReport() {
            return getArgsAccessor().getArgument(
                            getConfigMetaReport()::get,
                            arguments,
                            context,
                            GitHubSlackPublicFile.GITHUB_SLACK_PUBLICFILE_META_REPORT_ARG,
                            "githubslackpublicfile_meta_report",
                            "")
                    .value();
        }

        public List<String> getEntityName() {
            return getArgsAccessor().getArgumentList(
                            getConfigEntity()::get,
                            arguments,
                            context,
                            GitHubSlackPublicFile.GITHUB_SLACK_PUBLICFILE_ENTITY_NAME_ARG,
                            "githubslackpublicfile_entity_name",
                            "")
                    .stream()
                    .map(Argument::value)
                    .map(String::toLowerCase)
                    .toList();
        }
    }
}
