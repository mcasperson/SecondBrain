package secondbrain.domain.tools.planhat;

import com.google.common.collect.ImmutableList;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptionhandling.ExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateString;
import secondbrain.domain.web.ClientConstructor;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.planhat.PlanHatClient;
import secondbrain.infrastructure.planhat.api.Objective;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A tool that returns PlanHat objectives for a given company ID.
 */
@ApplicationScoped
public class PlanhatObjectives implements Tool<Void> {

    public static final String COMPANY_ID_ARG = "companyId";
    public static final String SEARCH_TTL_ARG = "searchTtl";

    private static final String INSTRUCTIONS = """
            You are a helpful assistant.
            You are given PlanHat objective details associated with a company.
            You must assume the information required to answer the question is present in the objectives.
            You must answer the question based on the objectives provided.
            You will be penalized for answering that the objectives can not be accessed.
            """.stripLeading();

    @Inject
    private PlanhatObjectivesConfig config;

    @Inject
    @Preferred
    private PlanHatClient planHatClient;

    @Inject
    @Preferred
    private LlmClient llmClient;

    @Inject
    private ExceptionMapping exceptionMapping;

    @Inject
    private ClientConstructor clientConstructor;

    @Override
    public String getName() {
        return PlanhatObjectives.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Returns PlanHat objectives for a given PlanHat company ID.";
    }

    @Override
    public String getContextLabel() {
        return "PlanHat Objectives";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return ImmutableList.of(
                new ToolArguments(COMPANY_ID_ARG, "The PlanHat company ID to retrieve objectives for", ""));
    }

    @Override
    public List<RagDocumentContext<Void>> getContext(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final PlanhatObjectivesConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        if (StringUtils.isBlank(parsedArgs.getCompanyId())) {
            throw new InternalFailure("You must provide a company ID to query PlanHat objectives");
        }

        final String url = parsedArgs.getUrl();
        final String token = parsedArgs.getSecretToken();

        if (StringUtils.isBlank(url) || StringUtils.isBlank(token)) {
            throw new InternalFailure("PlanHat URL and access token must be configured");
        }

        final List<Objective> objectives = Try.withResources(clientConstructor::getClient)
                .of(client -> planHatClient.getObjectives(
                        client,
                        parsedArgs.getCompanyId(),
                        url,
                        token,
                        parsedArgs.getSearchTTL()))
                .get();

        return objectives.stream()
                .map(objective -> new RagDocumentContext<>(
                        getName(),
                        getContextLabel(),
                        toDocument(objective),
                        List.of(),
                        objective.id(),
                        objective,
                        null,
                        List.of()))
                .map(RagDocumentContext::convertToRagDocumentContextVoid)
                .toList();
    }

    @Override
    public RagMultiDocumentContext<Void> call(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final List<RagDocumentContext<Void>> contextList = getContext(environmentSettings, prompt, arguments);

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> contextList)
                .map(ragDoc -> new RagMultiDocumentContext<>(prompt, INSTRUCTIONS, ragDoc))
                .map(ragDoc -> llmClient.callWithCache(ragDoc, environmentSettings, getName()));

        return exceptionMapping.map(result).get();
    }

    private String toDocument(final Objective objective) {
        final StringBuilder sb = new StringBuilder();
        sb.append("PlanHat Objective:\n");
        sb.append("- ID: ").append(Objects.requireNonNullElse(objective.id(), "")).append("\n");
        sb.append("- Name: ").append(Objects.requireNonNullElse(objective.name(), "")).append("\n");
        sb.append("- Company ID: ").append(Objects.requireNonNullElse(objective.companyId(), "")).append("\n");
        sb.append("- Company Name: ").append(Objects.requireNonNullElse(objective.companyName(), "")).append("\n");
        sb.append("- Health: ").append(Objects.requireNonNullElse(objective.health(), "")).append("\n");
        sb.append("- Shared In Portal: ").append(Objects.requireNonNullElse(objective.sharedInPortal(), "")).append("\n");
        sb.append("- Created At: ").append(Objects.requireNonNullElse(objective.createdAt(), "")).append("\n");
        sb.append("- Updated At: ").append(Objects.requireNonNullElse(objective.updatedAt(), "")).append("\n");

        if (objective.custom() != null && !objective.custom().isEmpty()) {
            sb.append("- Custom Fields:\n");
            objective.custom().forEach((key, value) ->
                    sb.append("  - ").append(key).append(": ").append(Objects.requireNonNullElse(value, "")).append("\n"));
        }

        return sb.toString();
    }
}

@ApplicationScoped
class PlanhatObjectivesConfig {

    private static final String DEFAULT_TTL = (60 * 60 * 24 * 7) + "";

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private ToStringGenerator toStringGenerator;

    @Inject
    private ValidateString validateString;

    @Inject
    @ConfigProperty(name = "sb.planhat.company")
    private Optional<String> configCompany;

    @Inject
    @ConfigProperty(name = "sb.planhat.url", defaultValue = "https://api-us4.planhat.com")
    private Optional<String> configUrl;

    @Inject
    @ConfigProperty(name = "sb.planhat.accesstoken")
    private Optional<String> configToken;

    @Inject
    @ConfigProperty(name = "sb.planhat.searchttl")
    private Optional<String> configSearchTtl;

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    public ToStringGenerator getToStringGenerator() {
        return toStringGenerator;
    }

    public ValidateString getValidateString() {
        return validateString;
    }

    public Optional<String> getConfigCompany() {
        return configCompany;
    }

    public Optional<String> getConfigUrl() {
        return configUrl;
    }

    public Optional<String> getConfigToken() {
        return configToken;
    }

    public Optional<String> getConfigSearchTtl() {
        return configSearchTtl;
    }

    public class LocalArguments {
        private final List<ToolArgs> arguments;
        private final String prompt;
        private final Map<String, String> context;

        public LocalArguments(
                final List<ToolArgs> arguments,
                final String prompt,
                final Map<String, String> context) {
            this.arguments = arguments;
            this.prompt = prompt;
            this.context = context;
        }

        @Override
        public String toString() {
            return getToStringGenerator().generateGetterConfig(this);
        }

        public String getCompanyId() {
            return getArgsAccessor().getArgument(
                    getConfigCompany()::get,
                    arguments,
                    context,
                    PlanhatObjectives.COMPANY_ID_ARG,
                    PlanhatObjectives.COMPANY_ID_ARG,
                    "").getSafeValue();
        }

        public String getUrl() {
            return Try.of(getConfigUrl()::get)
                    .mapTry(getValidateString()::throwIfBlank)
                    .recover(e -> context.get("planhat_url"))
                    .mapTry(getValidateString()::throwIfBlank)
                    .recover(e -> "")
                    .get();
        }

        public String getSecretToken() {
            return Try.of(getConfigToken()::get)
                    .mapTry(getValidateString()::throwIfBlank)
                    .recover(e -> context.get("planhat_token"))
                    .mapTry(getValidateString()::throwIfBlank)
                    .recover(e -> "")
                    .get();
        }

        public int getSearchTTL() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigSearchTtl()::get,
                    arguments,
                    context,
                    PlanhatObjectives.SEARCH_TTL_ARG,
                    PlanhatObjectives.SEARCH_TTL_ARG,
                    DEFAULT_TTL);

            return Try.of(argument::value)
                    .map(i -> Math.max(0, Integer.parseInt(i)))
                    .getOrElse(Integer.parseInt(DEFAULT_TTL));
        }
    }
}
