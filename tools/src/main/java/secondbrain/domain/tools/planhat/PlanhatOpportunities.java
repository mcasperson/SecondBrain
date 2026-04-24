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
public class PlanhatOpportunities implements Tool<Opportunity> {

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

    @Override
    public String getName() {
        return PlanhatOpportunities.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Returns PlanHat opportunities for a given PlanHat company ID.";
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
    public List<RagDocumentContext<Opportunity>> getContext(
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

        final List<Opportunity> opportunities = Try.withResources(ClientBuilder::newClient)
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
                .toList();
    }

    @Override
    public RagMultiDocumentContext<Opportunity> call(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final List<RagDocumentContext<Opportunity>> contextList = getContext(environmentSettings, prompt, arguments);

        final Try<RagMultiDocumentContext<Opportunity>> result = Try.of(() -> contextList)
                .map(ragDoc -> new RagMultiDocumentContext<>(prompt, INSTRUCTIONS, ragDoc))
                .map(ragDoc -> llmClient.callWithCache(ragDoc, environmentSettings, getName()));

        return exceptionMapping.map(result).get();
    }

    private String toDocument(final Opportunity opportunity) {
        final StringBuilder sb = new StringBuilder();
        sb.append("PlanHat Opportunity:\n");
        sb.append("- ID: ").append(Objects.requireNonNullElse(opportunity.id(), "")).append("\n");
        sb.append("- Title: ").append(Objects.requireNonNullElse(opportunity.title(), "")).append("\n");
        sb.append("- Status: ").append(Objects.requireNonNullElse(opportunity.status(), "")).append("\n");
        sb.append("- Sales Stage: ").append(Objects.requireNonNullElse(opportunity.salesStage(), "")).append("\n");
        sb.append("- Company ID: ").append(Objects.requireNonNullElse(opportunity.companyId(), "")).append("\n");
        sb.append("- Company Name: ").append(Objects.requireNonNullElse(opportunity.companyName(), "")).append("\n");
        sb.append("- MRR: ").append(Objects.requireNonNullElse(opportunity.mrr(), "")).append("\n");
        sb.append("- ARR: ").append(Objects.requireNonNullElse(opportunity.arr(), "")).append("\n");
        sb.append("- Owner ID: ").append(Objects.requireNonNullElse(opportunity.ownerId(), "")).append("\n");
        sb.append("- Landing Date: ").append(Objects.requireNonNullElse(opportunity.landingDate(), "")).append("\n");
        sb.append("- Close Date: ").append(Objects.requireNonNullElse(opportunity.closeDate(), "")).append("\n");
        sb.append("- Created At: ").append(Objects.requireNonNullElse(opportunity.createdAt(), "")).append("\n");
        sb.append("- Updated At: ").append(Objects.requireNonNullElse(opportunity.updatedAt(), "")).append("\n");

        if (opportunity.custom() != null && !opportunity.custom().isEmpty()) {
            sb.append("- Custom Fields:\n");
            opportunity.custom().forEach((key, value) ->
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

