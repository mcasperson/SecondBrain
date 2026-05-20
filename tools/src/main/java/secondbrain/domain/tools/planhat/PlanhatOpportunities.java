package secondbrain.domain.tools.planhat;

import com.google.common.collect.ImmutableList;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
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
import secondbrain.infrastructure.planhat.api.Opportunity;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A tool that returns PlanHat opportunities for a given company ID.
 */
@ApplicationScoped
public class PlanhatOpportunities implements Tool<Void> {

    public static final String COMPANY_ID_ARG = "companyId";
    public static final String SEARCH_TTL_ARG = "searchTtl";

    private static final String INSTRUCTIONS = """
            You are a helpful assistant.
            You are given PlanHat opportunity details associated with a company.
            You must assume the information required to answer the question is present in the opportunities.
            You must answer the question based on the opportunities provided.
            You will be penalized for answering that the opportunities can not be accessed.
            """.stripLeading();

    @Inject
    private PlanhatOpportunitiesConfig config;

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
        return PlanhatOpportunities.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Returns PlanHat opportunities for a given PlanHat company ID.";
    }

    @Override
    public int contextHashCode(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        final PlanhatOpportunitiesConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);
        return parsedArgs.hashCode();
    }

    @Override
    public String getContextLabel() {
        return "PlanHat Opportunities";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return ImmutableList.of(
                new ToolArguments(COMPANY_ID_ARG, "The PlanHat company ID to retrieve opportunities for", ""));
    }

    @Override
    public List<RagDocumentContext<Void>> getContext(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {
        return Try.of(() -> getContextPrivate(environmentSettings, prompt, arguments))
                .onFailure(ex -> java.util.logging.Logger.getLogger(getClass().getName()).warning("Failed to get context for " + getName() + ": " + ExceptionUtils.getRootCauseMessage(ex)))
                .getOrElse(List::of);
    }

    private List<RagDocumentContext<Void>> getContextPrivate(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final PlanhatOpportunitiesConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        if (StringUtils.isBlank(parsedArgs.getCompanyId())) {
            throw new InternalFailure("You must provide a company ID to query PlanHat opportunities");
        }

        final String url = parsedArgs.getUrl();
        final String token = parsedArgs.getSecretToken();

        if (StringUtils.isBlank(url) || StringUtils.isBlank(token)) {
            throw new InternalFailure("PlanHat URL and access token must be configured");
        }

        final List<Opportunity> opportunities = Try.withResources(clientConstructor::getClient)
                .of(client -> planHatClient.getOpportunities(
                        client,
                        parsedArgs.getCompanyId(),
                        url,
                        token,
                        parsedArgs.getSearchTTL()))
                .get();

        return opportunities.stream()
                .map(opportunity -> new RagDocumentContext<>(
                        getName(),
                        getContextLabel(),
                        toDocument(opportunity),
                        List.of(),
                        opportunity.id(),
                        opportunity,
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

    private String toDocument(final Opportunity opportunity) {
        final StringBuilder sb = new StringBuilder();
        sb.append("PlanHat Opportunity:\n");
        sb.append("- ID: ").append(Objects.requireNonNullElse(opportunity.id(), "")).append("\n");
        sb.append("- Title: ").append(opportunity.getTitle()).append("\n");
        sb.append("- Status: ").append(opportunity.getStatus()).append("\n");
        sb.append("- Sales Stage: ").append(opportunity.getSalesStage()).append("\n");
        sb.append("- Company ID: ").append(opportunity.getCompanyId()).append("\n");
        sb.append("- Company Name: ").append(opportunity.getCompanyName()).append("\n");
        sb.append("- MRR: ").append(opportunity.getMrr()).append("\n");
        sb.append("- ARR: ").append(opportunity.getArr()).append("\n");
        sb.append("- Owner ID: ").append(opportunity.getOwnerId()).append("\n");
        sb.append("- Landing Date: ").append(opportunity.getLandingDate()).append("\n");
        sb.append("- Close Date: ").append(opportunity.getCloseDate()).append("\n");
        sb.append("- Created At: ").append(opportunity.getCreatedAt()).append("\n");
        sb.append("- Updated At: ").append(opportunity.getUpdatedAt()).append("\n");

        if (!opportunity.getCustom().isEmpty()) {
            sb.append("- Custom Fields:\n");
            opportunity.getCustom().forEach((key, value) ->
                    sb.append("  - ").append(key).append(": ").append(Objects.requireNonNullElse(value, "")).append("\n"));
        }

        return sb.toString();
    }
}

@ApplicationScoped
class PlanhatOpportunitiesConfig {

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
            this.arguments = List.copyOf(arguments);
            this.prompt = prompt;
            this.context = Map.copyOf(context);
        }

        @Override
        public String toString() {
            return getToStringGenerator().generateGetterConfig(this);
        }

        @Override
        public int hashCode() {
            return getToStringGenerator().generateHashGetterConfig(this);
        }

        public String getCompanyId() {
            return getArgsAccessor().getArgument(
                    getConfigCompany()::get,
                    arguments,
                    context,
                    PlanhatOpportunities.COMPANY_ID_ARG,
                    PlanhatOpportunities.COMPANY_ID_ARG,
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
                    PlanhatOpportunities.SEARCH_TTL_ARG,
                    PlanhatOpportunities.SEARCH_TTL_ARG,
                    DEFAULT_TTL);

            return Try.of(argument::value)
                    .map(i -> Math.max(0, Integer.parseInt(i)))
                    .getOrElse(Integer.parseInt(DEFAULT_TTL));
        }
    }
}
