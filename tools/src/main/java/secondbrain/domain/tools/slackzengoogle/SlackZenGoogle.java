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
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.InsufficientContext;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.tools.googledocs.GoogleDocs;
import secondbrain.domain.tools.planhat.PlanHat;
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
    private PlanHat planHat;

    @Inject
    private OllamaClient ollamaClient;

    @Inject
    private PromptBuilderSelector promptBuilderSelector;

    @Inject
    private SlackZenGoogleConfig config;

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
                new ToolArguments("planHatCompanyId", "The optional PlanHat company to query", ""),
                new ToolArguments("googleDocumentId", "The optional Google Document to query", ""),
                new ToolArguments("zenDeskOrganization", "The optional Zen Desk organization channel to query", ""));
    }

    @Override
    public List<RagDocumentContext<Void>> getContext(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final SlackZenGoogleConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final List<ToolArgs> planHatArguments = List.of(
                new ToolArgs("companyId", parsedArgs.getPlanHatCompany(), true),
                new ToolArgs("days", parsedArgs.getSlackDays(), true)
        );

        final List<RagDocumentContext<Void>> planHatContext = StringUtils.isBlank(parsedArgs.getPlanHatCompany())
                ? List.of()
                : Try.of(() -> planHat.getContext(environmentSettings, prompt, planHatArguments))
                .recover(e -> List.of())
                .get()
                .stream()
                .map(RagDocumentContext::getRagDocumentContextVoid)
                .toList();

        final List<ToolArgs> slackArguments = List.of(
                new ToolArgs("days", parsedArgs.getSlackDays(), true),
                new ToolArgs("slackChannel", parsedArgs.getSlackChannel(), true)
        );

        final List<RagDocumentContext<Void>> slackContext = StringUtils.isBlank(parsedArgs.getSlackChannel())
                ? List.of()
                : Try.of(() -> slackChannel.getContext(environmentSettings, prompt, slackArguments))
                .recover(e -> List.of())
                .get();

        final List<ToolArgs> googleArguments = List.of(
                new ToolArgs("googleDocumentId", parsedArgs.getGoogleDocumentId(), true)
        );

        final List<RagDocumentContext<Void>> googleContext = StringUtils.isBlank(parsedArgs.getGoogleDocumentId())
                ? List.of()
                : Try.of(() -> googleDocs.getContext(environmentSettings, prompt, googleArguments))
                .recover(e -> List.of())
                .get();

        final List<ToolArgs> zenArguments = List.of(
                new ToolArgs("zenDeskOrganization", parsedArgs.getGoogleDocumentId(), true),
                new ToolArgs("days", parsedArgs.getSlackDays(), true)
        );

        final List<RagDocumentContext<Void>> zenContext = StringUtils.isBlank(parsedArgs.getZenDeskOrganization())
                ? List.of()
                : Try.of(() -> zenDeskOrganization.getContext(environmentSettings, prompt, zenArguments))
                .recover(e -> List.of())
                .get()
                .stream()
                .map(RagDocumentContext::getRagDocumentContextVoid)
                .collect(ImmutableList.toImmutableList());

        if (slackContext.size() + zenContext.size() + planHatContext.size() < parsedArgs.getMinSlackOrZen()) {
            throw new InsufficientContext("No Slack messages, ZenDesk tickets, or PlanHat activities found");
        }

        final List<RagDocumentContext<Void>> retValue = new ArrayList<>();
        retValue.addAll(slackContext);
        retValue.addAll(googleContext);
        retValue.addAll(zenContext);
        retValue.addAll(planHatContext);
        return retValue;
    }

    @Override
    public RagMultiDocumentContext<Void> call(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> getContext(environmentSettings, prompt, arguments))
                .map(ragDoc -> mergeContext(ragDoc, modelConfig.getCalculatedModel(environmentSettings)))
                .map(ragContext -> ragContext.updateDocument(promptBuilderSelector
                        .getPromptBuilder(modelConfig.getCalculatedModel(environmentSettings))
                        .buildFinalPrompt(
                                INSTRUCTIONS,
                                // I've opted to get the end of the document if it is larger than the context window.
                                // The end of the document is typically given more weight by LLMs, and so any long
                                // document being processed should place the most relevant content towards the end.
                                ragContext.getDocumentRight(modelConfig.getCalculatedContextWindowChars()),
                                prompt)))
                .map(ragDoc -> ollamaClient.callOllamaWithCache(
                        ragDoc,
                        modelConfig.getCalculatedModel(environmentSettings),
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
                        API.Case(API.$(instanceOf(EmptyString.class)), throwable -> new InternalFailure("Some string was empty (this is probably a bug...)")),
                        API.Case(API.$(instanceOf(InternalFailure.class)), throwable -> throwable),
                        API.Case(API.$(), throwable -> new ExternalFailure("Failed to process tickets, google doc, or slack messages: " + throwable.getMessage())))
                .get();
    }

    @Override
    public String getContextLabel() {
        return "Unused";
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
class SlackZenGoogleConfig {
    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    @ConfigProperty(name = "sb.slack.channel")
    private Optional<String> configSlackChannel;

    @Inject
    @ConfigProperty(name = "sb.slack.days")
    private Optional<String> configSlackDays;

    @Inject
    @ConfigProperty(name = "sb.google.doc")
    private Optional<String> configGoogleDoc;

    @Inject
    @ConfigProperty(name = "sb.slackzengoogle.minTimeBasedContext")
    private Optional<String> configSlackZenGoogleMinTimeBasedContext;

    @Inject
    @ConfigProperty(name = "sb.zendesk.organization")
    private Optional<String> configZenDeskOrganization;

    @Inject
    @ConfigProperty(name = "sb.planhat.company")
    private Optional<String> configPlanHatCompany;

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    public Optional<String> getConfigSlackChannel() {
        return configSlackChannel;
    }

    public Optional<String> getConfigSlackDays() {
        return configSlackDays;
    }

    public Optional<String> getConfigGoogleDoc() {
        return configGoogleDoc;
    }

    public Optional<String> getConfigSlackZenGoogleMinTimeBasedContext() {
        return configSlackZenGoogleMinTimeBasedContext;
    }

    public Optional<String> getConfigZenDeskOrganization() {
        return configZenDeskOrganization;
    }

    public Optional<String> getConfigPlanHatCompany() {
        return configPlanHatCompany;
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


        public String getSlackChannel() {
            return getArgsAccessor().getArgument(
                    getConfigSlackChannel()::get,
                    arguments,
                    context,
                    "slackChannel",
                    "slack_channel",
                    "").value();
        }

        public int getMinSlackOrZen() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigSlackZenGoogleMinTimeBasedContext()::get,
                    arguments,
                    context,
                    "slackZenGoogleMinTimeBasedContext",
                    "slackzengoogle_mintimebasedcontext",
                    "1").value();

            return NumberUtils.toInt(stringValue, 1);
        }

        public String getSlackDays() {
            return getArgsAccessor().getArgument(
                    getConfigSlackDays()::get,
                    arguments,
                    context,
                    "days",
                    "slack_days",
                    "").value();
        }

        public String getGoogleDocumentId() {
            return getArgsAccessor().getArgument(
                    getConfigGoogleDoc()::get,
                    arguments,
                    context,
                    "googleDocumentId",
                    "google_document_id",
                    "").value();
        }

        public String getZenDeskOrganization() {
            return getArgsAccessor().getArgument(
                    getConfigZenDeskOrganization()::get,
                    arguments,
                    context,
                    "zenDeskOrganization",
                    "zendesk_organization",
                    "").value();
        }

        public String getPlanHatCompany() {
            return getArgsAccessor().getArgument(
                    getConfigPlanHatCompany()::get,
                    arguments,
                    context,
                    "planHatCompanyId",
                    "planhat_companyid",
                    "").value();
        }
    }
}
