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
import secondbrain.infrastructure.snowflake.SnowflakeAdoptionIndexDetails;
import secondbrain.infrastructure.snowflake.SnowflakeClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A tool that returns the Snowflake adoption index for a given account ID.
 */
@ApplicationScoped
public class SnowflakeAdoptionIndex implements Tool<Void> {

    public static final String COMPANY_ID_ARG = "companyId";

    private static final String INSTRUCTIONS = """
            You are a helpful assistant.
            You are given the Snowflake adoption index details associated with an account.
            You must assume the information required to answer the question is present in the adoption index details.
            You must answer the question based on the adoption index details provided.
            You will be penalized for answering that the adoption index details can not be accessed.
            """.stripLeading();

    @Inject
    private SnowflakeAdoptionIndexConfig config;

    @Inject
    @Preferred
    private SnowflakeClient snowflakeClient;

    @Inject
    @Preferred
    private LlmClient llmClient;

    @Inject
    private ExceptionMapping exceptionMapping;

    @Override
    public String getName() {
        return SnowflakeAdoptionIndex.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Returns the Snowflake adoption index, adoption index status, and adoption index date for an account ID.";
    }

    @Override
    public String getContextLabel() {
        return "Snowflake Adoption Index";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return ImmutableList.of(
                new ToolArguments(COMPANY_ID_ARG, "The account ID (ACCOUNT_ID) to retrieve the adoption index for", ""));
    }

    @Override
    public int contextHashCode(final Map<String, String> environmentSettings, final List<String> prompts, final List<ToolArgs> arguments) {
        final SnowflakeAdoptionIndexConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompts, environmentSettings);
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

        final SnowflakeAdoptionIndexConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, List.of(prompt), environmentSettings);

        if (StringUtils.isBlank(parsedArgs.getCompanyId())) {
            throw new InternalFailure("You must provide a company ID to query the Snowflake adoption index");
        }

        return snowflakeClient.getAdoptionIndex(parsedArgs.getCompanyId())
                .stream()
                .map(details -> new RagDocumentContext<>(
                        getName(),
                        getContextLabel(),
                        toDocument(details),
                        List.of(),
                        details.accountId(),
                        details,
                        null,
                        List.of()))
                .map(ragDoc -> ragDoc.addIntermediateResult(new IntermediateResult(ragDoc.document(), "Data-SnowflakeAdoptionIndex-" + ragDoc.id() + "-" + parsedArgs.getCompanyId() + ".txt")))
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

    private String toDocument(final SnowflakeAdoptionIndexDetails details) {
        return "Snowflake Adoption Index for Account " + details.accountId() + ":\n"
                + "- Adoption Index: " + details.getAdoptionIndex() + "\n"
                + "- Adoption Index Status: " + details.getAdoptionIndexStatus() + "\n"
                + "- Adoption Index Date: " + details.getAdoptionIndexDate() + "\n";
    }
}

@ApplicationScoped
class SnowflakeAdoptionIndexConfig {

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private ToStringGenerator toStringGenerator;

    @Inject
    @ConfigProperty(name = "sb.snowflakeadoptionindex.companyId")
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
            final String accountId = getArgsAccessor().getArgument(
                    getConfigCompanyId()::get,
                    arguments,
                    context,
                    SnowflakeAdoptionIndex.COMPANY_ID_ARG,
                    SnowflakeAdoptionIndex.COMPANY_ID_ARG,
                    "").getSafeValue();

            if (StringUtils.isEmpty(accountId)) {
                return "";
            }

            // There is always a prefix "SFDC_" for the account ID in Snowflake, so we add it here if it's not already present.
            if (accountId.startsWith("SFDC_")) {
                return accountId;
            }

            return "SFDC_" + accountId;
        }
    }
}
