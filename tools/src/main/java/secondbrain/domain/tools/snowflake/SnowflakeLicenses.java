package secondbrain.domain.tools.snowflake;

import com.google.common.collect.ImmutableList;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptionhandling.ExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.objects.ToStringGenerator;
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
public class SnowflakeLicenses implements Tool<SnowflakeLicenseDetails> {

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
    public List<RagDocumentContext<SnowflakeLicenseDetails>> getContext(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final SnowflakeLicensesConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

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
                .toList();

    }

    @Override
    public RagMultiDocumentContext<SnowflakeLicenseDetails> call(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final List<RagDocumentContext<SnowflakeLicenseDetails>> contextList = getContext(environmentSettings, prompt, arguments);

        final Try<RagMultiDocumentContext<SnowflakeLicenseDetails>> result = Try.of(() -> contextList)
                .map(ragDoc -> new RagMultiDocumentContext<>(prompt, INSTRUCTIONS, ragDoc))
                .map(ragDoc -> llmClient.callWithCache(ragDoc, environmentSettings, getName()));

        return exceptionMapping.map(result).get();
    }

    private String toDocument(final SnowflakeLicenseDetails details) {
        return "Snowflake License Details for Salesforce Account " + details.sfdcAccountSystemId() + ":\n"
                + "- Total License Count: " + details.totalLicenseCount() + "\n"
                + "- Active License Count: " + details.activeLicenseCount() + "\n"
                + "- Last Recorded At: " + details.lastRecordedAt() + "\n"
                + "- Projects: " + details.projects() + "\n"
                + "- Projects (30 days prior): " + details.projects30dPrior() + "\n"
                + "- Tenants: " + details.tenants() + "\n"
                + "- Tenants (30 days prior): " + details.tenants30dPrior() + "\n"
                + "- Machines: " + details.machines() + "\n"
                + "- Machines (30 days prior): " + details.machines30dPrior() + "\n"
                + "- Monthly Active Users: " + details.monthlyActiveUsers() + "\n"
                + "- Monthly Active Users (30 days prior): " + details.monthlyActiveUsers30dPrior() + "\n"
                + "- Active Projects: " + details.projectsActive() + "\n"
                + "- Active Projects (30 days prior): " + details.projectsActive30dPrior() + "\n"
                + "- Active Tenants: " + details.tenantsActive() + "\n"
                + "- Active Tenants (30 days prior): " + details.tenantsActive30dPrior() + "\n"
                + "- Active Machines: " + details.machinesActive() + "\n"
                + "- Active Machines (30 days prior): " + details.machinesActive30dPrior() + "\n"
                + "- Deployments Per Day (current): " + details.deploymentsPerDayCurrent() + "\n"
                + "- Deployments Per Day (30 days prior): " + details.deploymentsPerDay30dPrior() + "\n";
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
        private final String prompt;
        private final Map<String, String> context;

        public LocalArguments(final List<ToolArgs> arguments, final String prompt, final Map<String, String> context) {
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
                    getConfigCompanyId()::get,
                    arguments,
                    context,
                    SnowflakeLicenses.COMPANY_ID_ARG,
                    SnowflakeLicenses.COMPANY_ID_ARG,
                    "").getSafeValue();
        }
    }
}

