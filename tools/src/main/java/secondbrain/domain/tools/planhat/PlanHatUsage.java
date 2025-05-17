package secondbrain.domain.tools.planhat;

import com.google.common.collect.ImmutableList;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.config.ModelConfig;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.exceptions.FailedOllama;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.tooldefs.MetaObjectResult;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.planhat.PlanHatClient;
import secondbrain.infrastructure.planhat.api.Company;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.base.Predicates.instanceOf;

@ApplicationScoped
public class PlanHatUsage implements Tool<Company> {
    public static final String SEARCH_TTL_ARG = "searchTtl";
    public static final String COMPANY_ID_ARGS = "companyId";
    public static final String PLANHAT_CUSTOM_1_ARG = "custom1";
    public static final String PLANHAT_CUSTOM_2_ARG = "custom2";
    public static final String PLANHAT_USAGE_ID_1_ARG = "usageId1";
    public static final String PLANHAT_USAGE_ID_2_ARG = "usageId2";
    public static final String PLANHAT_USAGE_ID_3_ARG = "usageId3";
    public static final String PLANHAT_USAGE_ID_4_ARG = "usageId4";
    public static final String PLANHAT_USAGE_ID_5_ARG = "usageId5";
    public static final String PLANHAT_USAGE_NAME_1_ARG = "usageName1";
    public static final String PLANHAT_USAGE_NAME_2_ARG = "usageName2";
    public static final String PLANHAT_USAGE_NAME_3_ARG = "usageName3";
    public static final String PLANHAT_USAGE_NAME_4_ARG = "usageName4";
    public static final String PLANHAT_USAGE_NAME_5_ARG = "usageName5";

    private static final String INSTRUCTIONS = """
            You are a helpful assistant.
            You are given details of platform usage associated with a PlanHat company.
            You must assume the information required to answer the question is present in the platform usage.
            You must answer the question based on the platform usage provided.
            You will be tipped $1000 for answering the question directly from the platform usage.
            When the user asks a question indicating that they want to know about platform usage, you must generate the answer based on the platform usage.
            You will be penalized for answering that the platform usage can not be accessed.
            """.stripLeading();

    @Inject
    private PlanHatUsageConfig config;

    @Inject
    @Preferred
    private PlanHatClient planHatClient;

    @Inject
    private ModelConfig modelConfig;

    @Inject
    private PromptBuilderSelector promptBuilderSelector;

    @Inject
    private OllamaClient ollamaClient;

    @Override
    public String getName() {
        return PlanHat.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Queries PlanHat for company platform usage";
    }

    @Override
    public String getContextLabel() {
        return "PlanHat Platform Usage";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return ImmutableList.of(
                new ToolArguments(COMPANY_ID_ARGS, "The company ID to query", ""));
    }

    @Override
    public List<RagDocumentContext<Company>> getContext(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        final PlanHatUsageConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        if (StringUtils.isBlank(parsedArgs.getCompany())) {
            throw new InternalFailure("You must provide a company ID to query");
        }

        // We can process multiple planhat instances
        final List<Pair<String, String>> tokens = Stream.of(
                        Pair.of(parsedArgs.getUrl(), parsedArgs.getToken()),
                        Pair.of(parsedArgs.getUrl2(), parsedArgs.getToken2()))
                .filter(pair -> StringUtils.isNotBlank(pair.getRight()) && StringUtils.isNotBlank(pair.getLeft()))
                .toList();

        // Find the first Planhat instance to return company details
        final Optional<Company> firstCompany = Try.withResources(ClientBuilder::newClient)
                .of(client -> tokens
                        .stream()
                        .map(pair -> Try.of(() -> planHatClient.getCompany(
                                client,
                                parsedArgs.getCompany(),
                                pair.getLeft(),
                                pair.getRight(),
                                parsedArgs.getSearchTTL())))
                        .filter(Try::isSuccess)
                        .map(Try::get)
                        .findFirst()
                )
                .get();

        if (firstCompany.isEmpty()) {
            throw new InternalFailure("Could not find company " + parsedArgs.getCompany());
        }

        final Company company = firstCompany.get();

        final List<RagDocumentContext<Company>> usageContext = Stream.of(
                        Pair.of(parsedArgs.getUsageName1(), parsedArgs.getUsageId1()),
                        Pair.of(parsedArgs.getUsageName2(), parsedArgs.getUsageId2()),
                        Pair.of(parsedArgs.getUsageName3(), parsedArgs.getUsageId3()),
                        Pair.of(parsedArgs.getUsageName4(), parsedArgs.getUsageId4()),
                        Pair.of(parsedArgs.getUsageName5(), parsedArgs.getUsageId5()))
                .filter(pair -> StringUtils.isNotBlank(pair.getLeft()) && StringUtils.isNotBlank(pair.getRight()))
                .map(pair -> new RagDocumentContext<>(
                        getContextLabel() + " " + company.name() + " " + pair.getLeft(),
                        company.usage().getOrDefault(pair.getRight(), 0).toString(),
                        List.of(),
                        company.id() + ":" + pair.getRight(),
                        company,
                        null,
                        List.of()))
                .toList();

        final List<RagDocumentContext<Company>> customContext = Stream.of(
                        parsedArgs.getCustom1(),
                        parsedArgs.getCustom2()
                )
                .filter(StringUtils::isNotBlank)
                .map(custom -> new RagDocumentContext<>(
                        getContextLabel() + " " + company.name() + " " + custom,
                        company.custom().getOrDefault(custom, "").toString(),
                        List.of(),
                        company.id() + ":" + custom,
                        company,
                        null,
                        List.of()))
                .toList();

        return ListUtils.union(usageContext, customContext);
    }

    @Override
    public List<MetaObjectResult> getMetadata(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        final PlanHatUsageConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        if (StringUtils.isBlank(parsedArgs.getCompany())) {
            throw new InternalFailure("You must provide a company ID to query");
        }

        final Company company = Try.withResources(ClientBuilder::newClient)
                .of(client -> planHatClient.getCompany(
                        client,
                        parsedArgs.getCompany(),
                        parsedArgs.getUrl(),
                        parsedArgs.getToken(),
                        parsedArgs.getSearchTTL()))
                .get();

        if (company.custom() == null) {
            return List.of();
        }

        return Stream.of(parsedArgs.getCustom1())
                .filter(StringUtils::isNotBlank)
                .map(custom -> new MetaObjectResult(custom, company.custom().getOrDefault(custom, "").toString()))
                .toList();
    }

    @Override
    public RagMultiDocumentContext<Company> call(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        final List<RagDocumentContext<Company>> contextList = getContext(environmentSettings, prompt, arguments);

        final PlanHatUsageConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        if (StringUtils.isBlank(parsedArgs.getCompany())) {
            throw new InternalFailure("You must provide a company to query");
        }

        final Try<RagMultiDocumentContext<Company>> result = Try.of(() -> contextList)
                .map(ragDoc -> mergeContext(ragDoc, modelConfig.getCalculatedModel(environmentSettings)))
                .map(ragContext -> ragContext.updateDocument(promptBuilderSelector
                        .getPromptBuilder(modelConfig.getCalculatedModel(environmentSettings))
                        .buildFinalPrompt(
                                INSTRUCTIONS,
                                ragContext.getDocumentLeft(modelConfig.getCalculatedContextWindowChars(environmentSettings)),
                                prompt)))
                .map(ragDoc -> ollamaClient.callOllamaWithCache(
                        ragDoc,
                        modelConfig.getCalculatedModel(environmentSettings),
                        getName(),
                        modelConfig.getCalculatedContextWindow(environmentSettings)));

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(
                        API.Case(API.$(instanceOf(EmptyString.class)), throwable -> new InternalFailure("The PlanHat activities is empty", throwable)),
                        API.Case(API.$(instanceOf(InternalFailure.class)), throwable -> throwable),
                        API.Case(API.$(instanceOf(FailedOllama.class)), throwable -> new InternalFailure(throwable.getMessage(), throwable)),
                        API.Case(API.$(), ex -> new InternalFailure(getName() + " failed to call Ollama", ex)))
                .get();
    }

    private RagMultiDocumentContext<Company> mergeContext(final List<RagDocumentContext<Company>> context, final String customModel) {
        return new RagMultiDocumentContext<>(
                context.stream()
                        .map(ragDoc -> promptBuilderSelector
                                .getPromptBuilder(customModel)
                                .buildContextPrompt(
                                        ragDoc.contextLabel(),
                                        ragDoc.document()))
                        .collect(Collectors.joining("\n")),
                context);
    }
}

@ApplicationScoped
class PlanHatUsageConfig {
    private static final String DEFAULT_TTL = (1000 * 60 * 60 * 24) + "";

    @Inject
    @ConfigProperty(name = "sb.planhat.company")
    private Optional<String> configCompany;

    @Inject
    @ConfigProperty(name = "sb.planhat.url", defaultValue = "https://api-us4.planhat.com")
    private Optional<String> configUrl;

    @Inject
    @ConfigProperty(name = "sb.planhat.url2", defaultValue = "https://api.planhat.com")
    private Optional<String> configUrl2;

    @Inject
    @ConfigProperty(name = "sb.planhat.accesstoken")
    private Optional<String> configToken;

    @Inject
    @ConfigProperty(name = "sb.planhat.accesstoken2")
    private Optional<String> configToken2;

    @Inject
    @ConfigProperty(name = "sb.planhat.searchttl")
    private Optional<String> configSearchTtl;

    @Inject
    @ConfigProperty(name = "sb.planhat.disablelinks")
    private Optional<String> configDisableLinks;

    @Inject
    @ConfigProperty(name = "sb.planhat.custom1")
    private Optional<String> configCustom1;

    @Inject
    @ConfigProperty(name = "sb.planhat.custom2")
    private Optional<String> configCustom2;

    @Inject
    @ConfigProperty(name = "sb.planhat.usageid1")
    private Optional<String> configUsageId1;

    @Inject
    @ConfigProperty(name = "sb.planhat.usagename1")
    private Optional<String> configUsageName1;

    @Inject
    @ConfigProperty(name = "sb.planhat.usageid2")
    private Optional<String> configUsageId2;

    @Inject
    @ConfigProperty(name = "sb.planhat.usagename2")
    private Optional<String> configUsageName2;

    @Inject
    @ConfigProperty(name = "sb.planhat.usageid3")
    private Optional<String> configUsageId3;

    @Inject
    @ConfigProperty(name = "sb.planhat.usagename3")
    private Optional<String> configUsageName3;

    @Inject
    @ConfigProperty(name = "sb.planhat.usageid4")
    private Optional<String> configUsageId4;

    @Inject
    @ConfigProperty(name = "sb.planhat.usagename4")
    private Optional<String> configUsageName4;

    @Inject
    @ConfigProperty(name = "sb.planhat.usageid5")
    private Optional<String> configUsageId5;

    @Inject
    @ConfigProperty(name = "sb.planhat.usagename5")
    private Optional<String> configUsageName5;

    @Inject
    private ValidateString validateString;

    @Inject
    private ArgsAccessor argsAccessor;

    public Optional<String> getConfigCompany() {
        return configCompany;
    }

    public Optional<String> getConfigToken() {
        return configToken;
    }

    public Optional<String> getConfigToken2() {
        return configToken2;
    }

    public Optional<String> getConfigSearchTtl() {
        return configSearchTtl;
    }

    public Optional<String> getConfigDisableLinks() {
        return configDisableLinks;
    }

    public ValidateString getValidateString() {
        return validateString;
    }

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    public Optional<String> getConfigUsageId1() {
        return configUsageId1;
    }

    public Optional<String> getConfigUsageName1() {
        return configUsageName1;
    }

    public Optional<String> getConfigUsageId2() {
        return configUsageId2;
    }

    public Optional<String> getConfigUsageName2() {
        return configUsageName2;
    }

    public Optional<String> getConfigUsageId3() {
        return configUsageId3;
    }

    public Optional<String> getConfigUsageName3() {
        return configUsageName3;
    }

    public Optional<String> getConfigUsageId4() {
        return configUsageId4;
    }

    public Optional<String> getConfigUsageName4() {
        return configUsageName4;
    }

    public Optional<String> getConfigUsageId5() {
        return configUsageId5;
    }

    public Optional<String> getConfigUsageName5() {
        return configUsageName5;
    }

    public Optional<String> getConfigCustom1() {
        return configCustom1;
    }

    public Optional<String> getConfigCustom2() {
        return configCustom2;
    }

    public Optional<String> getConfigUrl() {
        return configUrl;
    }

    public Optional<String> getConfigUrl2() {
        return configUrl2;
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

        public String getCompany() {
            return getArgsAccessor().getArgument(
                    getConfigCompany()::get,
                    arguments,
                    context,
                    PlanHatUsage.COMPANY_ID_ARGS,
                    "planhat_usage_company",
                    "").value();
        }

        public String getUrl() {
            return Try.of(getConfigUrl()::get)
                    .mapTry(getValidateString()::throwIfEmpty)
                    .recover(e -> context.get("planhat_url"))
                    .mapTry(getValidateString()::throwIfEmpty)
                    .recover(e -> "")
                    .get();
        }

        public String getToken() {
            return Try.of(getConfigToken()::get)
                    .mapTry(getValidateString()::throwIfEmpty)
                    .recover(e -> context.get("planhat_usage_token"))
                    .mapTry(getValidateString()::throwIfEmpty)
                    .recover(e -> "")
                    .get();
        }

        public String getToken2() {
            return Try.of(getConfigToken2()::get)
                    .mapTry(getValidateString()::throwIfEmpty)
                    .recover(e -> context.get("planhat_token2"))
                    .mapTry(getValidateString()::throwIfEmpty)
                    .recover(e -> "")
                    .get();
        }

        public String getUrl2() {
            return Try.of(getConfigUrl2()::get)
                    .mapTry(getValidateString()::throwIfEmpty)
                    .recover(e -> context.get("planhat_url2"))
                    .mapTry(getValidateString()::throwIfEmpty)
                    .recover(e -> "")
                    .get();
        }

        public int getSearchTTL() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigSearchTtl()::get,
                    arguments,
                    context,
                    PlanHatUsage.SEARCH_TTL_ARG,
                    "planhat_searchttl",
                    DEFAULT_TTL);

            return Try.of(argument::value)
                    .map(i -> Math.max(0, Integer.parseInt(i)))
                    .get();
        }

        public boolean getDisableLinks() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigDisableLinks()::get,
                    arguments,
                    context,
                    PlanHat.DISABLE_LINKS_ARG,
                    "planhat_usage_disablelinks",
                    "false");

            return BooleanUtils.toBoolean(argument.value());
        }

        public String getCustom1() {
            return getArgsAccessor().getArgument(
                    getConfigCustom1()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_CUSTOM_1_ARG,
                    "planhat_custom1",
                    "").value();
        }

        public String getCustom2() {
            return getArgsAccessor().getArgument(
                    getConfigCustom2()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_CUSTOM_2_ARG,
                    "planhat_custom2",
                    "").value();
        }

        public String getUsageId1() {
            return getArgsAccessor().getArgument(
                    getConfigUsageId1()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_USAGE_ID_1_ARG,
                    "planhat_usage_id1",
                    "").value();
        }

        public String getUsageId2() {
            return getArgsAccessor().getArgument(
                    getConfigUsageId2()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_USAGE_ID_2_ARG,
                    "planhat_usage_id2",
                    "").value();
        }

        public String getUsageId3() {
            return getArgsAccessor().getArgument(
                    getConfigUsageId3()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_USAGE_ID_3_ARG,
                    "planhat_usage_id3",
                    "").value();
        }

        public String getUsageId4() {
            return getArgsAccessor().getArgument(
                    getConfigUsageId4()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_USAGE_ID_4_ARG,
                    "planhat_usage_id4",
                    "").value();
        }

        public String getUsageId5() {
            return getArgsAccessor().getArgument(
                    getConfigUsageId5()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_USAGE_ID_5_ARG,
                    "planhat_usage_id5",
                    "").value();
        }

        public String getUsageName1() {
            return getArgsAccessor().getArgument(
                    getConfigUsageName1()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_USAGE_NAME_1_ARG,
                    "planhat_usage_name1",
                    "").value();
        }

        public String getUsageName2() {
            return getArgsAccessor().getArgument(
                    getConfigUsageName2()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_USAGE_NAME_2_ARG,
                    "planhat_usage_name2",
                    "").value();
        }

        public String getUsageName3() {
            return getArgsAccessor().getArgument(
                    getConfigUsageName3()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_USAGE_NAME_3_ARG,
                    "planhat_usage_name3",
                    "").value();
        }

        public String getUsageName4() {
            return getArgsAccessor().getArgument(
                    getConfigUsageName4()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_USAGE_NAME_4_ARG,
                    "planhat_usage_name4",
                    "").value();
        }

        public String getUsageName5() {
            return getArgsAccessor().getArgument(
                    getConfigUsageName5()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_USAGE_NAME_5_ARG,
                    "planhat_usage_name5",
                    "").value();
        }
    }
}
