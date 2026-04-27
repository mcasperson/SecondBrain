package secondbrain.domain.tools.planhat;

import com.google.common.collect.ImmutableList;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.annotations.PropertyLabelDescriptionValue;
import secondbrain.domain.annotations.PropertyValueReader;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptionhandling.ExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.tooldefs.*;
import secondbrain.domain.validate.ValidateString;
import secondbrain.domain.web.ClientConstructor;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.planhat.PlanHatClient;
import secondbrain.infrastructure.planhat.api.Company;
import secondbrain.infrastructure.planhat.api.PlanHatUser;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ApplicationScoped
public class PlanHatUsage implements Tool<Company> {
    public static final String SEARCH_TTL_ARG = "searchTtl";
    public static final String COMPANY_ID_ARGS = "companyId";
    public static final String PLANHAT_CUSTOM_1_ARG = "custom1";
    public static final String PLANHAT_CUSTOM_2_ARG = "custom2";
    public static final String PLANHAT_CUSTOM_3_ARG = "custom3";
    public static final String PLANHAT_CUSTOM_4_ARG = "custom4";
    public static final String PLANHAT_CUSTOM_5_ARG = "custom5";
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
    public static final String PLANHAT_CUSTOM_PERSON_1_ARG = "customPerson1";
    public static final String PLANHAT_CUSTOM_PERSON_2_ARG = "customPerson2";
    public static final String PLANHAT_CUSTOM_PERSON_3_ARG = "customPerson3";
    public static final String PLANHAT_CUSTOM_PERSON_4_ARG = "customPerson4";
    public static final String PLANHAT_CUSTOM_PERSON_5_ARG = "customPerson5";

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
    @Preferred
    private LlmClient llmClient;

    @Inject
    private ExceptionMapping exceptionMapping;

    @Inject
    private ClientConstructor clientConstructor;

    @Inject
    private PropertyValueReader propertyValueReader;

    @Override
    public String getName() {
        return PlanHatUsage.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Queries PlanHat for company platform usage";
    }

    @Override
    public String getContextLabel() {
        return "PlanHat Metadata";
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
                        Pair.of(parsedArgs.getUrl(), parsedArgs.getSecretToken()),
                        Pair.of(parsedArgs.getUrl2(), parsedArgs.getSecretToken2()))
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

        final List<RagDocumentContext<Company>> usageContext = parsedArgs.getUsagePairs()
                 .filter(pair -> StringUtils.isNotBlank(pair.getLeft()) && StringUtils.isNotBlank(pair.getRight()))
                .map(pair -> new RagDocumentContext<>(
                        getName(),
                        getContextLabel() + " " + company.name() + " " + pair.getLeft(),
                        company.usage().getOrDefault(pair.getRight(), 0).toString(),
                        List.of(),
                        company.id() + ":" + pair.getRight(),
                        company,
                        new MetaObjectResults(new MetaObjectResult(
                                pair.getLeft(),
                                company.usage().getOrDefault(pair.getRight(), 0).toString(),
                                company.id(),
                                getName())),
                        null,
                        null,
                        List.of()))
                .toList();

        final List<RagDocumentContext<Company>> customContext = parsedArgs.getCustoms().stream()
                .filter(StringUtils::isNotBlank)
                .map(custom -> new RagDocumentContext<>(
                        getName(),
                        getContextLabel() + " " + company.name() + " " + custom,
                        company.custom().getOrDefault(custom, "").toString(),
                        List.of(),
                        company.id() + ":" + custom,
                        company,
                        new MetaObjectResults(new MetaObjectResult(
                                custom,
                                company.custom().getOrDefault(custom, "").toString(),
                                company.id(),
                                getName())),
                        null,
                        null,
                        List.of()))
                .toList();

        // Look up each custom person field by resolving the custom field key to a user ID, then fetching the user
        final Map<String, String> people = tokens.isEmpty()
                ? Map.of()
                : parsedArgs.getCustomPersons()
                  .filter(StringUtils::isNotBlank)
                  .collect(Collectors.toMap(
                          customFieldKey -> customFieldKey,
                          customFieldKey -> {
                              if (StringUtils.isBlank(company.getCustomStringKey(customFieldKey))) {
                                  return "";
                              }
                              // Step 2: fetch the user by the resolved ID
                              return Try.withResources(clientConstructor::getClient)
                                     .of(client -> planHatClient.getUser(
                                             client,
                                             company.getCustomStringKey(customFieldKey),
                                             // TODO: work out a way to identify planhat instances with custom fields. This logic assumes one planhat instance.
                                             tokens.getFirst().getLeft(),
                                             tokens.getFirst().getRight(),
                                             parsedArgs.getSearchTTL()))
                                     .map(PlanHatUser::getFullName)
                                     .getOrElse("");
                          }));

        final List<RagDocumentContext<Company>> customPersonContext = people.entrySet().stream()
                .filter(entry -> StringUtils.isNotBlank(entry.getValue()))
                .map(entry -> new RagDocumentContext<Company>(
                        getName(),
                        getContextLabel() + " " + company.name() + " " + entry.getKey(),
                        entry.getValue(),
                        List.of(),
                        company.id() + ":" + entry.getKey(),
                        company,
                        new MetaObjectResults(new MetaObjectResult(
                                entry.getKey(),
                                entry.getValue(),
                                company.id(),
                                getName())),
                        null,
                        null,
                        List.of()))
                .toList();

        final List<PropertyLabelDescriptionValue> propertyLabelDescriptionValues = propertyValueReader.getValues(company);

        // Iterate over every property in the company object and add a RagDocumentContext for each
        final List<RagDocumentContext<Company>> companyPropertyContext = propertyLabelDescriptionValues
                .stream()
                .map(p -> new RagDocumentContext<Company>(
                                getName(),
                                getContextLabel() + " " + company.name() + " " + p.description(),
                                p.value().toString(),
                                List.of(),
                                company.id() + ":" + p.description(),
                                company,
                                new MetaObjectResults(new MetaObjectResult(
                                        p.description(),
                                        p.value().toString(),
                                        company.id(),
                                        getName())),
                                null,
                                null,
                                List.of()))
                .toList();

        return Stream.of(usageContext, customContext, customPersonContext, companyPropertyContext)
                .flatMap(List::stream)
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
                .map(ragDoc -> new RagMultiDocumentContext<>(prompt, INSTRUCTIONS, ragDoc))
                .map(ragDoc -> llmClient.callWithCache(
                        ragDoc,
                        environmentSettings,
                        getName()));

        return exceptionMapping.map(result).get();
    }
}

@ApplicationScoped
class PlanHatUsageConfig {
    private static final String DEFAULT_TTL = (1000 * 60 * 60 * 24 * 7) + "";

    @Inject
    @ConfigProperty(name = "sb.planhat.company")
    private Optional<String> configCompany;

    @Inject
    @ConfigProperty(name = "sb.planhat.url", defaultValue = "https://api-us4.planhat.com")
    private Optional<String> configUrl;

    @Inject
    @ConfigProperty(name = "sb.planhat.url2")
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
    @ConfigProperty(name = "sb.planhat.custom1")
    private Optional<String> configCustom1;

    @Inject
    @ConfigProperty(name = "sb.planhat.custom2")
    private Optional<String> configCustom2;

    @Inject
    @ConfigProperty(name = "sb.planhat.custom3")
    private Optional<String> configCustom3;

    @Inject
    @ConfigProperty(name = "sb.planhat.custom4")
    private Optional<String> configCustom4;

    @Inject
    @ConfigProperty(name = "sb.planhat.custom5")
    private Optional<String> configCustom5;

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
    @ConfigProperty(name = "sb.planhat.customperson1")
    private Optional<String> configCustomPerson1;

    @Inject
    @ConfigProperty(name = "sb.planhat.customperson2")
    private Optional<String> configCustomPerson2;

    @Inject
    @ConfigProperty(name = "sb.planhat.customperson3")
    private Optional<String> configCustomPerson3;

    @Inject
    @ConfigProperty(name = "sb.planhat.customperson4")
    private Optional<String> configCustomPerson4;

    @Inject
    @ConfigProperty(name = "sb.planhat.customperson5")
    private Optional<String> configCustomPerson5;

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

    public Optional<String> getConfigCustom3() {
        return configCustom3;
    }

    public Optional<String> getConfigCustom4() {
        return configCustom4;
    }

    public Optional<String> getConfigCustom5() {
        return configCustom5;
    }

    public Optional<String> getConfigUrl() {
        return configUrl;
    }

    public Optional<String> getConfigUrl2() {
        return configUrl2;
    }

    public Optional<String> getConfigCustomPerson1() {
        return configCustomPerson1;
    }

    public Optional<String> getConfigCustomPerson2() {
        return configCustomPerson2;
    }

    public Optional<String> getConfigCustomPerson3() {
        return configCustomPerson3;
    }

    public Optional<String> getConfigCustomPerson4() {
        return configCustomPerson4;
    }

    public Optional<String> getConfigCustomPerson5() {
        return configCustomPerson5;
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
                    PlanHatUsage.COMPANY_ID_ARGS,
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
                    .recover(e -> context.get("planhat_usage_token"))
                    .mapTry(getValidateString()::throwIfBlank)
                    .recover(e -> "")
                    .get();
        }

        public String getSecretToken2() {
            return Try.of(getConfigToken2()::get)
                    .mapTry(getValidateString()::throwIfBlank)
                    .recover(e -> context.get("planhat_token2"))
                    .mapTry(getValidateString()::throwIfBlank)
                    .recover(e -> "")
                    .get();
        }

        public String getUrl2() {
            return Try.of(getConfigUrl2()::get)
                    .mapTry(getValidateString()::throwIfBlank)
                    .recover(e -> context.get("planhat_url2"))
                    .mapTry(getValidateString()::throwIfBlank)
                    .recover(e -> "")
                    .get();
        }

        public int getSearchTTL() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigSearchTtl()::get,
                    arguments,
                    context,
                    PlanHatUsage.SEARCH_TTL_ARG,
                    PlanHatUsage.SEARCH_TTL_ARG,
                    DEFAULT_TTL);

            return Try.of(argument::value)
                    .map(i -> Math.max(0, Integer.parseInt(i)))
                    .get();
        }

        public String getCustom1() {
            return getArgsAccessor().getArgument(
                    getConfigCustom1()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_CUSTOM_1_ARG,
                    PlanHatUsage.PLANHAT_CUSTOM_1_ARG,
                    "").getSafeValue();
        }

        public String getCustom2() {
            return getArgsAccessor().getArgument(
                    getConfigCustom2()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_CUSTOM_2_ARG,
                    PlanHatUsage.PLANHAT_CUSTOM_2_ARG,
                    "").getSafeValue();
        }

        public String getCustom3() {
            return getArgsAccessor().getArgument(
                    getConfigCustom3()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_CUSTOM_3_ARG,
                    PlanHatUsage.PLANHAT_CUSTOM_3_ARG,
                    "").getSafeValue();
        }

        public String getCustom4() {
            return getArgsAccessor().getArgument(
                    getConfigCustom4()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_CUSTOM_4_ARG,
                    PlanHatUsage.PLANHAT_CUSTOM_4_ARG,
                    "").getSafeValue();
        }

        public String getCustom5() {
            return getArgsAccessor().getArgument(
                    getConfigCustom5()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_CUSTOM_5_ARG,
                    PlanHatUsage.PLANHAT_CUSTOM_5_ARG,
                    "").getSafeValue();
        }

        public List<String> getCustoms() {
            return List.of(
                    getCustom1(),
                    getCustom2(),
                    getCustom3(),
                    getCustom4(),
                    getCustom5()
             );
        }

        public String getUsageId1() {
            return getArgsAccessor().getArgument(
                    getConfigUsageId1()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_USAGE_ID_1_ARG,
                    PlanHatUsage.PLANHAT_USAGE_ID_1_ARG,
                    "").getSafeValue();
        }

        public String getUsageId2() {
            return getArgsAccessor().getArgument(
                    getConfigUsageId2()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_USAGE_ID_2_ARG,
                    PlanHatUsage.PLANHAT_USAGE_ID_2_ARG,
                    "").getSafeValue();
        }

        public String getUsageId3() {
            return getArgsAccessor().getArgument(
                    getConfigUsageId3()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_USAGE_ID_3_ARG,
                    PlanHatUsage.PLANHAT_USAGE_ID_3_ARG,
                    "").getSafeValue();
        }

        public String getUsageId4() {
            return getArgsAccessor().getArgument(
                    getConfigUsageId4()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_USAGE_ID_4_ARG,
                    PlanHatUsage.PLANHAT_USAGE_ID_4_ARG,
                    "").getSafeValue();
        }

        public String getUsageId5() {
            return getArgsAccessor().getArgument(
                    getConfigUsageId5()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_USAGE_ID_5_ARG,
                    PlanHatUsage.PLANHAT_USAGE_ID_5_ARG,
                    "").getSafeValue();
        }

        public String getUsageName1() {
            return getArgsAccessor().getArgument(
                    getConfigUsageName1()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_USAGE_NAME_1_ARG,
                    PlanHatUsage.PLANHAT_USAGE_NAME_1_ARG,
                    "").getSafeValue();
        }

        public String getUsageName2() {
            return getArgsAccessor().getArgument(
                    getConfigUsageName2()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_USAGE_NAME_2_ARG,
                    PlanHatUsage.PLANHAT_USAGE_NAME_2_ARG,
                    "").getSafeValue();
        }

        public String getUsageName3() {
            return getArgsAccessor().getArgument(
                    getConfigUsageName3()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_USAGE_NAME_3_ARG,
                    PlanHatUsage.PLANHAT_USAGE_NAME_3_ARG,
                    "").getSafeValue();
        }

        public String getUsageName4() {
            return getArgsAccessor().getArgument(
                    getConfigUsageName4()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_USAGE_NAME_4_ARG,
                    PlanHatUsage.PLANHAT_USAGE_NAME_4_ARG,
                    "").getSafeValue();
        }

        public String getUsageName5() {
            return getArgsAccessor().getArgument(
                    getConfigUsageName5()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_USAGE_NAME_5_ARG,
                    PlanHatUsage.PLANHAT_USAGE_NAME_5_ARG,
                    "").getSafeValue();
        }

        public String getCustomPerson1() {
            return getArgsAccessor().getArgument(
                    getConfigCustomPerson1()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_CUSTOM_PERSON_1_ARG,
                    PlanHatUsage.PLANHAT_CUSTOM_PERSON_1_ARG,
                    "").getSafeValue();
        }

        public String getCustomPerson2() {
            return getArgsAccessor().getArgument(
                    getConfigCustomPerson2()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_CUSTOM_PERSON_2_ARG,
                    PlanHatUsage.PLANHAT_CUSTOM_PERSON_2_ARG,
                    "").getSafeValue();
        }

        public String getCustomPerson3() {
            return getArgsAccessor().getArgument(
                    getConfigCustomPerson3()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_CUSTOM_PERSON_3_ARG,
                    PlanHatUsage.PLANHAT_CUSTOM_PERSON_3_ARG,
                    "").getSafeValue();
        }

        public String getCustomPerson4() {
            return getArgsAccessor().getArgument(
                    getConfigCustomPerson4()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_CUSTOM_PERSON_4_ARG,
                    PlanHatUsage.PLANHAT_CUSTOM_PERSON_4_ARG,
                    "").getSafeValue();
        }

        public String getCustomPerson5() {
            return getArgsAccessor().getArgument(
                    getConfigCustomPerson5()::get,
                    arguments,
                    context,
                    PlanHatUsage.PLANHAT_CUSTOM_PERSON_5_ARG,
                    PlanHatUsage.PLANHAT_CUSTOM_PERSON_5_ARG,
                    "").getSafeValue();
        }

        public Stream<String> getCustomPersons() {
            return Stream.of(
                    getCustomPerson1(),
                    getCustomPerson2(),
                    getCustomPerson3(),
                    getCustomPerson4(),
                    getCustomPerson5());
        }

         public Stream<Pair<String, String>> getUsagePairs() {
             return Stream.of(
                     Pair.of(getUsageName1(), getUsageId1()),
                     Pair.of(getUsageName2(), getUsageId2()),
                     Pair.of(getUsageName3(), getUsageId3()),
                     Pair.of(getUsageName4(), getUsageId4()),
                     Pair.of(getUsageName5(), getUsageId5()));
         }
    }
}
