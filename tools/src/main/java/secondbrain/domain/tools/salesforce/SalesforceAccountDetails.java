package secondbrain.domain.tools.salesforce;

import com.google.common.collect.ImmutableList;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
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
import secondbrain.infrastructure.salesforce.SalesforceClient;
import secondbrain.infrastructure.salesforce.api.SalesforceAccountRecord;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * A tool that returns the name and owner of a Salesforce account.
 */
@ApplicationScoped
public class SalesforceAccountDetails implements Tool<Void> {

    public static final String ACCOUNT_ID_ARG = "accountId";
    public static final String DOMAIN_ARG = "domain";
    public static final String CLIENT_ID_ARG = "clientId";
    public static final String CLIENT_SECRET_ARG = "clientSecret";

    /**
     * Salesforce IDs are 15 or 18 alphanumeric characters. Validating the ID also ensures it can be safely
     * embedded in the SOQL query.
     */
    private static final Pattern ACCOUNT_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9]{15}([a-zA-Z0-9]{3})?$");

    private static final String INSTRUCTIONS = """
            You are a helpful assistant.
            You are given the Salesforce account details, including the account name, account owner, billing address, industry, and number of employees.
            You must assume the information required to answer the question is present in the account details.
            You must answer the question based on the account details provided.
            You will be penalized for answering that the account details can not be accessed.
            """.stripLeading();

    @Inject
    private SalesforceAccountDetailsConfig config;

    @Inject
    @Preferred
    private SalesforceClient salesforceClient;

    @Inject
    @Preferred
    private LlmClient llmClient;

    @Inject
    private ExceptionMapping exceptionMapping;

    @Inject
    private Logger logger;

    @Override
    public String getName() {
        return SalesforceAccountDetails.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Returns the name, owner name, owner email, billing address, industry, and number of employees of a Salesforce account.";
    }

    @Override
    public String getContextLabel() {
        return "Salesforce Account Details";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return ImmutableList.of(
                new ToolArguments(ACCOUNT_ID_ARG, "The Salesforce account ID to retrieve the details for", ""),
                new ToolArguments(DOMAIN_ARG, "The optional domain used to link to the Salesforce account", ""));
    }

    @Override
    public int contextHashCode(final Map<String, String> environmentSettings, final List<String> prompts, final List<ToolArgs> arguments) {
        final SalesforceAccountDetailsConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompts, environmentSettings);
        return 31 * parsedArgs.hashCode() + prompts.hashCode();
    }

    @Override
    public List<RagDocumentContext<Void>> getContext(
            final Map<String, String> environmentSettings,
            final List<String> prompts,
            final List<ToolArgs> arguments) {
        return Try.of(() -> getContextPrivate(environmentSettings, prompts, arguments))
                .onFailure(ex -> logger.warning("Failed to get context for " + getName() + ": " + ExceptionUtils.getRootCauseMessage(ex)))
                .getOrElse(List::of);
    }

    private List<RagDocumentContext<Void>> getContextPrivate(
            final Map<String, String> environmentSettings,
            final List<String> prompts,
            final List<ToolArgs> arguments) {

        final SalesforceAccountDetailsConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompts, environmentSettings);
        final String accountId = parsedArgs.getAccountId();

        if (StringUtils.isBlank(accountId)) {
            throw new InternalFailure("You must provide an account ID to query the Salesforce account details");
        }

        if (!ACCOUNT_ID_PATTERN.matcher(accountId).matches()) {
            throw new InternalFailure("The Salesforce account ID " + accountId + " is not valid");
        }

        final String token = salesforceClient.getToken(parsedArgs.getClientId(), parsedArgs.getSecretClientSecret()).accessToken();

        return salesforceClient.getAccount(token, accountId)
                .stream()
                .map(account -> new RagDocumentContext<>(
                        getName(),
                        getContextLabel(),
                        toDocument(account),
                        List.of(),
                        account.getId(),
                        account,
                        getLink(parsedArgs.getDomain(), account),
                        List.of()))
                .map(ragDoc -> ragDoc.addIntermediateResult(new IntermediateResult(ragDoc.document(), "Data-SalesforceAccountDetails-" + accountId + ".txt")))
                .map(RagDocumentContext::convertToRagDocumentContextVoid)
                .toList();
    }

    @Override
    public RagMultiDocumentContext<Void> call(
            final Map<String, String> environmentSettings,
            final List<String> prompts,
            final List<ToolArgs> arguments) {

        final List<RagDocumentContext<Void>> contextList = getContext(environmentSettings, prompts, arguments);

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> contextList)
                .map(ragDoc -> new RagMultiDocumentContext<>(prompts, INSTRUCTIONS, ragDoc))
                .map(ragDoc -> llmClient.callWithCache(ragDoc, environmentSettings, getName()));

        return exceptionMapping.map(result).get();
    }

    @Nullable
    private String getLink(final String domain, final SalesforceAccountRecord account) {
        if (StringUtils.isBlank(domain)) {
            return null;
        }

        return "[Salesforce Account " + account.getName() + "](https://" + domain + ".lightning.force.com/lightning/r/Account/" + account.getId() + "/view)";
    }

    private String toDocument(final SalesforceAccountRecord account) {
        return "Salesforce Account " + account.getId() + ":\n"
                + "- Account Name: " + account.getName() + "\n"
                + "- Account Owner Name: " + account.getOwner().getName() + "\n"
                + "- Account Owner Email: " + account.getOwner().getEmail() + "\n"
                + "- Billing Street: " + account.getBillingStreet() + "\n"
                + "- Billing City: " + account.getBillingCity() + "\n"
                + "- Billing State: " + account.getBillingState() + "\n"
                + "- Billing Postal Code: " + account.getBillingPostalCode() + "\n"
                + "- Billing Country: " + account.getBillingCountry() + "\n"
                + "- Industry: " + account.getIndustry() + "\n"
                + "- Number Of Employees: " + account.getNumberOfEmployees() + "\n";
    }
}

@ApplicationScoped
class SalesforceAccountDetailsConfig {

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private ToStringGenerator toStringGenerator;

    @Inject
    @ConfigProperty(name = "sb.salesforceaccountdetails.accountid")
    private Optional<String> configAccountId;

    @Inject
    @ConfigProperty(name = "sb.salesforce.domain")
    private Optional<String> configDomain;

    @Inject
    @ConfigProperty(name = "sb.salesforce.clientid")
    private Optional<String> configClientId;

    @Inject
    @ConfigProperty(name = "sb.salesforce.clientsecret")
    private Optional<String> configClientSecret;

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    public ToStringGenerator getToStringGenerator() {
        return toStringGenerator;
    }

    public Optional<String> getConfigAccountId() {
        return configAccountId;
    }

    public Optional<String> getConfigDomain() {
        return configDomain;
    }

    public Optional<String> getConfigClientId() {
        return configClientId;
    }

    public Optional<String> getConfigClientSecret() {
        return configClientSecret;
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

        public String getAccountId() {
            return getArgsAccessor().getArgument(
                    getConfigAccountId()::get,
                    arguments,
                    context,
                    SalesforceAccountDetails.ACCOUNT_ID_ARG,
                    SalesforceAccountDetails.ACCOUNT_ID_ARG,
                    "").getSafeValue().trim();
        }

        public String getDomain() {
            return getArgsAccessor().getArgument(
                    getConfigDomain()::get,
                    arguments,
                    context,
                    SalesforceAccountDetails.DOMAIN_ARG,
                    SalesforceAccountDetails.DOMAIN_ARG,
                    "").getSafeValue();
        }

        public String getClientId() {
            return getArgsAccessor().getArgument(
                    getConfigClientId()::get,
                    arguments,
                    context,
                    SalesforceAccountDetails.CLIENT_ID_ARG,
                    SalesforceAccountDetails.CLIENT_ID_ARG,
                    "").getSafeValue();
        }

        public String getSecretClientSecret() {
            return getArgsAccessor().getArgument(
                    getConfigClientSecret()::get,
                    arguments,
                    context,
                    SalesforceAccountDetails.CLIENT_SECRET_ARG,
                    SalesforceAccountDetails.CLIENT_SECRET_ARG,
                    "").getSafeValue();
        }
    }
}
