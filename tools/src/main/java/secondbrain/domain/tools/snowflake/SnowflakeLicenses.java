package secondbrain.domain.tools.snowflake;

import com.google.common.collect.ImmutableList;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptionhandling.ExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.tooldefs.IntermediateResult;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.snowflake.SnowflakeClient;
import secondbrain.infrastructure.snowflake.SnowflakeLicenseDetails;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A tool that returns Snowflake license details for a given Salesforce account ID.
 */
@ApplicationScoped
public class SnowflakeLicenses implements Tool<Void> {

    public static final String COMPANY_ID_ARG = "companyId";

    private static final String INSTRUCTIONS = """
            You are a helpful assistant.
            You are given Snowflake license and usage details associated with an account.
            You must assume the information required to answer the question is present in the license details.
            You must answer the question based on the license details provided.
            You will be penalized for answering that the license details can not be accessed.
            """.stripLeading();

    @Inject
    private SnowflakeLicensesConfig config;

    @Inject
    private SnowflakeClient snowflakeClient;

    @Inject
    @Preferred
    private LlmClient llmClient;

    @Inject
    private ExceptionMapping exceptionMapping;

    @Override
    public String getName() {
        return SnowflakeLicenses.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Returns Snowflake license and usage details for a Salesforce account ID.";
    }

    @Override
    public String getContextLabel() {
        return "Snowflake License Details";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return ImmutableList.of(
                new ToolArguments(COMPANY_ID_ARG, "The Salesforce account system ID (SFDC_ACCOUNT_SYSTEM_ID) to retrieve license details for", ""));
    }

    @Override
    public int contextHashCode(final Map<String, String> environmentSettings, final List<String> prompts, final List<ToolArgs> arguments) {
        final SnowflakeLicensesConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompts, environmentSettings);
        return 31 * parsedArgs.hashCode() + prompts.hashCode();
    }

    @Override
    public List<RagDocumentContext<Void>> getContext(
            final Map<String, String> environmentSettings,
            final List<String> prompts,
            final List<ToolArgs> arguments) {
        final String prompt = prompts.isEmpty() ? "" : prompts.getFirst();
        return Try.of(() -> getContextPrivate(environmentSettings, prompt, arguments))
                .onFailure(ex -> java.util.logging.Logger.getLogger(getClass().getName()).warning("Failed to get context for " + getName() + ": " + ExceptionUtils.getRootCauseMessage(ex)))
                .getOrElse(List::of);
    }

    private List<RagDocumentContext<Void>> getContextPrivate(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final SnowflakeLicensesConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, List.of(prompt), environmentSettings);

        if (StringUtils.isBlank(parsedArgs.getCompanyId())) {
            throw new InternalFailure("You must provide a company ID to query Snowflake license details");
        }

        final List<SnowflakeLicenseDetails> results = snowflakeClient.getLicenseDetails(parsedArgs.getCompanyId());

        return results.stream()
                .map(details -> new RagDocumentContext<>(
                        getName(),
                        getContextLabel(),
                        toDocument(details),
                        List.of(),
                        details.sfdcAccountSystemId(),
                        details,
                        null,
                        List.of()))
                .map(ragDoc -> ragDoc.addIntermediateResult(new IntermediateResult(ragDoc.document(), "Data-Snowflake-" + ragDoc.id() + "-" + parsedArgs.getCompanyId() + ".txt")))
                .map(RagDocumentContext::convertToRagDocumentContextVoid)
                .toList();

    }

    @Override
    public RagMultiDocumentContext<Void> call(
            final Map<String, String> environmentSettings,
            final List<String> prompts,
            final List<ToolArgs> arguments) {

        final String firstPrompt = prompts.isEmpty() ? "" : prompts.get(0);
        final List<RagDocumentContext<Void>> contextList = getContext(environmentSettings, prompts, arguments);

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> contextList)
                .map(ragDoc -> new RagMultiDocumentContext<>(prompts, INSTRUCTIONS, ragDoc))
                .map(ragDoc -> llmClient.callWithCache(ragDoc, environmentSettings, getName()));

        return exceptionMapping.map(result).get();
    }

    private String toDocument(final SnowflakeLicenseDetails details) {
        return "Snowflake License Details for Salesforce Account " + details.sfdcAccountSystemId() + ":\n"
                + "- Total License Count: " + details.getTotalLicenseCount() + "\n"
                + "- Active License Count: " + details.getActiveLicenseCount() + "\n"
                + "- Last Recorded At: " + details.getLastRecordedAt() + "\n"
                + "- Projects: " + details.getProjects() + "\n"
                + "- Projects (30 days prior): " + details.getProjects30dPrior() + "\n"
                + "- Projects (% change): " + details.getProjectsPercentChange() + "%\n"
                + "- Tenants: " + details.getTenants() + "\n"
                + "- Tenants (30 days prior): " + details.getTenants30dPrior() + "\n"
                + "- Tenants (% change): " + details.getTenantsPercentChange() + "%\n"
                + "- Machines: " + details.getMachines() + "\n"
                + "- Machines (30 days prior): " + details.getMachines30dPrior() + "\n"
                + "- Machines (% change): " + details.getMachinesPercentChange() + "%\n"
                + "- Monthly Active Users: " + details.getMonthlyActiveUsers() + "\n"
                + "- Monthly Active Users (30 days prior): " + details.getMonthlyActiveUsers30dPrior() + "\n"
                + "- Monthly Active Users (% change): " + details.getMonthlyActiveUsersPercentChange() + "%\n"
                + "- Active Projects: " + details.getProjectsActive() + "\n"
                + "- Active Projects (30 days prior): " + details.getProjectsActive30dPrior() + "\n"
                + "- Active Projects (% change): " + details.getProjectsActivePercentChange() + "%\n"
                + "- Active Tenants: " + details.getTenantsActive() + "\n"
                + "- Active Tenants (30 days prior): " + details.getTenantsActive30dPrior() + "\n"
                + "- Active Tenants (% change): " + details.getTenantsActivePercentChange() + "%\n"
                + "- Active Machines: " + details.getMachinesActive() + "\n"
                + "- Active Machines (30 days prior): " + details.getMachinesActive30dPrior() + "\n"
                + "- Active Machines (% change): " + details.getMachinesActivePercentChange() + "%\n"
                + "- Deployments Per Day (current): " + details.getDeploymentsPerDayCurrent() + "\n"
                + "- Deployments Per Day (30 days prior): " + details.getDeploymentsPerDay30dPrior() + "\n"
                + "- Deployments Per Day (% change): " + details.getDeploymentsPerDayPercentChange() + "%\n";
    }
}

@ApplicationScoped
class SnowflakeLicensesConfig {

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private ToStringGenerator toStringGenerator;

    @Inject
    @ConfigProperty(name = "sb.snowflake.companyId")
    private Optional<String> configCompanyId;


    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    public ToStringGenerator getToStringGenerator() {
        return toStringGenerator;
    }

    public Optional<String> getConfigCompanyId() {
        return configCompanyId;
    }


    public class LocalArguments {
        private final List<ToolArgs> arguments;
        private final List<String> prompts;
        private final Map<String, String> context;

        public LocalArguments(final List<ToolArgs> arguments, final List<String> prompts, final Map<String, String> context) {
            this.arguments = List.copyOf(arguments);
            this.prompts = List.copyOf(prompts);
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
                    getConfigCompanyId()::get,
                    arguments,
                    context,
                    SnowflakeLicenses.COMPANY_ID_ARG,
                    SnowflakeLicenses.COMPANY_ID_ARG,
                    "").getSafeValue();
        }
    }
}
