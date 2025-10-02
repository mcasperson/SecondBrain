package secondbrain.domain.tools.salesforce;

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
import secondbrain.domain.context.SentenceSplitter;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.exceptionhandling.ExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.salesforce.SalesforceClient;
import secondbrain.infrastructure.salesforce.api.SalesforceTaskRecord;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * A tool that answers a query based on the emails associated with a Salesforce account.
 */
@ApplicationScoped
public class Salesforce implements Tool<SalesforceTaskRecord> {
    public static final String ACCOUNT_ID = "accountId";
    public static final String CLIENT_SECRET = "clientSecret";
    public static final String CLIENT_ID = "clientId";
    private static final String INSTRUCTIONS = """
            You are a helpful assistant.
            You are given list of emails sent to people associated with a Salesforce account.
            You must assume the information required to answer the question is present in the emails.
            You must answer the question based on the emails provided.
            When the user asks a question indicating that they want to know about emails, you must generate the answer based on the emails.
            You will be penalized for answering that the emails can not be accessed.
            """.stripLeading();
    @Inject
    @ConfigProperty(name = "sb.salesforce.domain")
    private Optional<String> domain;

    @Inject
    private ExceptionMapping exceptionMapping;

    @Inject
    private SalesforceClient salesforceClient;

    @Inject
    private SalesforceConfig config;

    @Inject
    private Logger logger;

    @Inject
    private SentenceSplitter sentenceSplitter;

    @Inject
    private SentenceVectorizer sentenceVectorizer;

    @Inject
    @Preferred
    private LlmClient llmClient;

    @Override
    public String getName() {
        return Salesforce.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Returns email communications with an account from Salesforce.";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return ImmutableList.of(new ToolArguments(
                "greeting",
                "The greeting to display",
                "World"));
    }

    @Override
    public List<RagDocumentContext<SalesforceTaskRecord>> getContext(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {
        logger.log(Level.INFO, "Getting context for " + getName());

        final SalesforceConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final Try<List<RagDocumentContext<SalesforceTaskRecord>>> context = Try.of(() -> salesforceClient.getToken(parsedArgs.getClientId(), parsedArgs.getClientSecret()))
                .map(token -> salesforceClient.getTasks(token.accessToken(), parsedArgs.getAccountId(), "Email"))
                .map(emails -> Stream.of(emails).map(email -> getDocumentContext(email, parsedArgs)).toList());

        return exceptionMapping.map(context).get();
    }

    @Override
    public RagMultiDocumentContext<SalesforceTaskRecord> call(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        logger.log(Level.INFO, "Calling " + getName());

        final List<RagDocumentContext<SalesforceTaskRecord>> contextList = getContext(environmentSettings, prompt, arguments);

        final SalesforceConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        if (StringUtils.isBlank(parsedArgs.getAccountId())) {
            throw new InternalFailure("You must provide an account ID to query");
        }

        final Try<RagMultiDocumentContext<SalesforceTaskRecord>> result = Try.of(() -> contextList)
                .map(ragDoc -> new RagMultiDocumentContext<>(prompt, INSTRUCTIONS, ragDoc))
                .map(ragDoc -> llmClient.callWithCache(ragDoc, environmentSettings, getName()));

        return exceptionMapping.map(result).get();
    }

    @Override
    public String getContextLabel() {
        return "Email";
    }

    private RagDocumentContext<SalesforceTaskRecord> getDocumentContext(final SalesforceTaskRecord task, final SalesforceConfig.LocalArguments parsedArgs) {
        return Try.of(() -> sentenceSplitter.splitDocument(task.getEmailText(), 10))
                .map(sentences -> new RagDocumentContext<SalesforceTaskRecord>(
                        getName(),
                        getContextLabel(),
                        task.getEmailText(),
                        sentenceVectorizer.vectorize(sentences),
                        task.id(),
                        task,
                        "[Salesforce Task " + task.id() + "](https://" + domain.orElse("fixme") + ".my.salesforce.com)"))
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                .get();
    }
}

@ApplicationScoped
class SalesforceConfig {
    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    @ConfigProperty(name = "sb.salesforce.clientid")
    private Optional<String> configClientId;

    @Inject
    @ConfigProperty(name = "sb.salesforce.clientsecret")
    private Optional<String> configClientSecret;

    @Inject
    @ConfigProperty(name = "sb.salesforce.accountid")
    private Optional<String> configAccountId;

    public Optional<String> getConfigClientId() {
        return configClientId;
    }

    public Optional<String> getConfigClientSecret() {
        return configClientSecret;
    }

    public Optional<String> getConfigAccountId() {
        return configAccountId;
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

        public String getAccountId() {
            return getArgsAccessor().getArgument(
                    getConfigAccountId()::get,
                    arguments,
                    context,
                    Salesforce.ACCOUNT_ID,
                    Salesforce.ACCOUNT_ID,
                    "").value();
        }

        public String getClientId() {
            return getArgsAccessor().getArgument(
                    getConfigClientId()::get,
                    arguments,
                    context,
                    Salesforce.CLIENT_ID,
                    Salesforce.CLIENT_ID,
                    "").value();
        }

        public String getClientSecret() {
            return getArgsAccessor().getArgument(
                    getConfigClientSecret()::get,
                    arguments,
                    context,
                    Salesforce.CLIENT_SECRET,
                    Salesforce.CLIENT_SECRET,
                    "").value();
        }
    }
}