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
import secondbrain.domain.date.DateParser;
import secondbrain.domain.exceptionhandling.ExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.planhat.PlanHatClient;
import secondbrain.infrastructure.planhat.api.Conversation;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;

/**
 * A tool that returns a summary of PlanHat conversation counts grouped by type for a given company.
 */
@ApplicationScoped
public class PlanhatConversationStats implements Tool<Conversation> {

    public static final String COMPANY_ID_ARG = "companyId";
    public static final String SEARCH_TTL_ARG = "searchTtl";

    private static final String INSTRUCTIONS = """
            You are a helpful assistant.
            You are given a summary of PlanHat conversation counts grouped by type for a company.
            You must assume the information required to answer the question is present in the conversation statistics.
            You must answer the question based on the conversation statistics provided.
            You will be penalized for answering that the conversation statistics can not be accessed.
            """.stripLeading();

    @Inject
    private PlanhatConversationStatsConfig config;

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
        return PlanhatConversationStats.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Returns a summary of PlanHat conversation counts grouped by type for a given company.";
    }

    @Override
    public String getContextLabel() {
        return "PlanHat Conversation Statistics";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return ImmutableList.of(
                new ToolArguments(COMPANY_ID_ARG, "The PlanHat company ID to retrieve conversation statistics for", ""),
                new ToolArguments(CommonArguments.DAYS_ARG, "The optional number of days worth of conversations to return", "0"),
                new ToolArguments(CommonArguments.HOURS_ARG, "The optional number of hours worth of conversations to return", "0"),
                new ToolArguments(CommonArguments.START_DATE, "The optional start date for the conversation query (e.g. 2024-01-01)", ""),
                new ToolArguments(CommonArguments.END_DATE, "The optional end date for the conversation query (e.g. 2024-12-31)", ""));
    }

    @Override
    public List<RagDocumentContext<Conversation>> getContext(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final PlanhatConversationStatsConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        if (StringUtils.isBlank(parsedArgs.getCompanyId())) {
            throw new InternalFailure("You must provide a company ID to query PlanHat conversation statistics");
        }

        final String url = parsedArgs.getUrl();
        final String token = parsedArgs.getSecretToken();

        if (StringUtils.isBlank(url) || StringUtils.isBlank(token)) {
            throw new InternalFailure("PlanHat URL and access token must be configured");
        }

        final ZonedDateTime startDate = parsedArgs.getStartZonedDateTime();
        final ZonedDateTime endDate = parsedArgs.getEndZonedDateTime();

        final List<Conversation> conversations = Try.withResources(ClientBuilder::newClient)
                .of(client -> planHatClient.getConversations(
                        client,
                        parsedArgs.getCompanyId(),
                        url,
                        token,
                        startDate,
                        endDate,
                        parsedArgs.getSearchTTL()))
                .get();

        final Map<String, Long> countsByType = conversations.stream()
                .collect(Collectors.groupingBy(
                        c -> StringUtils.defaultIfBlank(Objects.toString(c.type(), "unknown"), "unknown"),
                        Collectors.counting()));

        final long total = conversations.size();

        final String document = buildDocument(parsedArgs.getCompanyId(), countsByType, total);

        return List.of(new RagDocumentContext<>(
                getName(),
                getContextLabel(),
                document,
                List.of(),
                parsedArgs.getCompanyId(),
                null,
                null,
                List.of()));
    }

    @Override
    public RagMultiDocumentContext<Conversation> call(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final List<RagDocumentContext<Conversation>> contextList = getContext(environmentSettings, prompt, arguments);

        final Try<RagMultiDocumentContext<Conversation>> result = Try.of(() -> contextList)
                .map(ragDoc -> new RagMultiDocumentContext<>(prompt, INSTRUCTIONS, ragDoc))
                .map(ragDoc -> llmClient.callWithCache(ragDoc, environmentSettings, getName()));

        return exceptionMapping.map(result).get();
    }

    private String buildDocument(final String companyId, final Map<String, Long> countsByType, final long total) {
        final StringBuilder sb = new StringBuilder();
        sb.append("PlanHat Conversation Type Summary for company '").append(companyId).append("':\n");

        countsByType.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n"));

        sb.append("Total conversations: ").append(total).append("\n");
        return sb.toString();
    }
}

@ApplicationScoped
class PlanhatConversationStatsConfig {

    private static final String DEFAULT_TTL = (60 * 60 * 24 * 7) + "";

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private ToStringGenerator toStringGenerator;

    @Inject
    private ValidateString validateString;

    @Inject
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "sb.planhat.company")
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

    @Inject
    @ConfigProperty(name = "sb.planhat.days")
    private Optional<String> configDays;

    @Inject
    @ConfigProperty(name = "sb.planhat.hours")
    private Optional<String> configHours;

    @Inject
    @ConfigProperty(name = "sb.planhat.startperiod")
    private Optional<String> configStartPeriod;

    @Inject
    @ConfigProperty(name = "sb.planhat.endperiod")
    private Optional<String> configEndPeriod;

    @Inject
    @io.smallrye.common.annotation.Identifier("everything")
    private DateParser dateParser;

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

    public Optional<String> getConfigDays() {
        return configDays;
    }

    public Optional<String> getConfigHours() {
        return configHours;
    }

    public Optional<String> getConfigStartPeriod() {
        return configStartPeriod;
    }

    public Optional<String> getConfigEndPeriod() {
        return configEndPeriod;
    }

    public DateParser getDateParser() {
        return dateParser;
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
                    PlanhatConversationStats.COMPANY_ID_ARG,
                    PlanhatConversationStats.COMPANY_ID_ARG,
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
                    PlanhatConversationStats.SEARCH_TTL_ARG,
                    PlanhatConversationStats.SEARCH_TTL_ARG,
                    DEFAULT_TTL);

            return Try.of(argument::value)
                    .map(i -> Math.max(0, Integer.parseInt(i)))
                    .getOrElse(Integer.parseInt(DEFAULT_TTL));
        }

        private Argument getHoursArgument() {
            return getArgsAccessor().getArgument(
                    getConfigHours()::get,
                    arguments,
                    context,
                    CommonArguments.HOURS_ARG,
                    CommonArguments.HOURS_ARG,
                    "0");
        }

        private Argument getDaysArgument() {
            return getArgsAccessor().getArgument(
                    getConfigDays()::get,
                    arguments,
                    context,
                    CommonArguments.DAYS_ARG,
                    CommonArguments.DAYS_ARG,
                    "0");
        }

        public int getRawHours() {
            return Try.of(() -> Integer.parseInt(getHoursArgument().getSafeValue()))
                    .recover(throwable -> 0)
                    .map(i -> Math.max(0, i))
                    .get();
        }

        public int getRawDays() {
            return Try.of(() -> Integer.parseInt(getDaysArgument().getSafeValue()))
                    .recover(throwable -> 0)
                    .map(i -> Math.max(0, i))
                    .get();
        }

        public int getHours() {
            if (getHoursArgument().trusted() && getDaysArgument().trusted()) {
                return getRawHours();
            }
            return switchArguments(prompt, getRawHours(), getRawDays(), "hour", "day");
        }

        public int getDays() {
            if (getHoursArgument().trusted() && getDaysArgument().trusted()) {
                return getRawDays();
            }
            return switchArguments(prompt, getRawDays(), getRawHours(), "day", "hour");
        }

        public String getStartPeriod() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigStartPeriod()::get,
                    arguments,
                    context,
                    CommonArguments.START_DATE,
                    CommonArguments.START_DATE,
                    "").getSafeValue();

            if (StringUtils.isNotBlank(stringValue)) {
                return getDateParser().parseDate(stringValue).format(ISO_OFFSET_DATE_TIME);
            }
            return "";
        }

        public String getEndPeriod() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigEndPeriod()::get,
                    arguments,
                    context,
                    CommonArguments.END_DATE,
                    CommonArguments.END_DATE,
                    "").getSafeValue();

            if (StringUtils.isNotBlank(stringValue)) {
                return getDateParser().parseDate(stringValue).format(ISO_OFFSET_DATE_TIME);
            }
            return "";
        }

        public String getStartDate() {
            final TemporalUnit truncatedTo = getHours() == 0 ? ChronoUnit.DAYS : ChronoUnit.HOURS;

            return OffsetDateTime.now(ZoneId.systemDefault())
                    .truncatedTo(truncatedTo)
                    .minusDays(getDays() + getHours() == 0 ? 1 : getDays())
                    .minusHours(getHours())
                    .format(ISO_OFFSET_DATE_TIME);
        }

        public String getEndDate() {
            return OffsetDateTime.now(ZoneId.systemDefault())
                    .truncatedTo(ChronoUnit.DAYS)
                    .plusDays(1)
                    .format(ISO_OFFSET_DATE_TIME);
        }

        /**
         * Returns the effective start date as a ZonedDateTime for use with the PlanHat client.
         * Explicit start period takes precedence over computed date range.
         */
        public ZonedDateTime getStartZonedDateTime() {
            final String period = getStartPeriod();
            if (StringUtils.isNotBlank(period)) {
                return getDateParser().parseDate(period);
            }
            return getDateParser().parseDate(getStartDate());
        }

        /**
         * Returns the effective end date as a ZonedDateTime for use with the PlanHat client.
         * Explicit end period takes precedence over computed date range.
         */
        public ZonedDateTime getEndZonedDateTime() {
            final String period = getEndPeriod();
            if (StringUtils.isNotBlank(period)) {
                return getDateParser().parseDate(period);
            }
            return getDateParser().parseDate(getEndDate());
        }

        private int switchArguments(final String prompt, final int a, final int b, final String aKeyword, final String bKeyword) {
            final java.util.Locale locale = java.util.Locale.getDefault();

            if (!prompt.toLowerCase(locale).contains(aKeyword.toLowerCase(locale))) {
                return 0;
            }

            if (!prompt.toLowerCase(locale).contains(bKeyword.toLowerCase(locale)) && a == 0) {
                return b;
            }

            return a;
        }
    }
}




