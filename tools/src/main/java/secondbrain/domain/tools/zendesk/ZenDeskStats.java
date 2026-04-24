package secondbrain.domain.tools.zendesk;

import com.google.common.collect.ImmutableList;
import io.smallrye.common.annotation.Identifier;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.date.DateParser;
import secondbrain.domain.encryption.Encryptor;
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
import secondbrain.infrastructure.zendesk.ZenDeskClient;
import secondbrain.infrastructure.zendesk.api.ZenDeskTicket;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;

/**
 * A tool that returns a summary of ZenDesk ticket counts grouped by status for a given organisation.
 */
@ApplicationScoped
public class ZenDeskStats implements Tool<ZenDeskTicket> {

    public static final String ZENDESK_ORGANIZATION_ARG = "zenDeskOrganization";
    public static final String ZENDESK_HISTORY_TTL_ARG = "historyTtl";

    private static final String INSTRUCTIONS = """
            You are a helpful assistant.
            You are given a summary of ZenDesk ticket counts grouped by status for an organisation.
            You must assume the information required to answer the question is present in the ticket statistics.
            You must answer the question based on the ticket statistics provided.
            You will be penalized for answering that the ticket statistics can not be accessed.
            """.stripLeading();

    @Inject
    private ZenDeskStatsConfig config;

    @Inject
    @Preferred
    private ZenDeskClient zenDeskClient;

    @Inject
    @Preferred
    private LlmClient llmClient;

    @Inject
    private ExceptionMapping exceptionMapping;

    @Override
    public String getName() {
        return ZenDeskStats.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Returns a summary of ZenDesk ticket counts grouped by status for a given organisation.";
    }

    @Override
    public String getContextLabel() {
        return "ZenDesk Ticket Statistics";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return ImmutableList.of(
                new ToolArguments(ZENDESK_ORGANIZATION_ARG, "The name of the ZenDesk organisation to retrieve ticket statistics for", ""),
                new ToolArguments(CommonArguments.DAYS_ARG, "The optional number of days worth of tickets to return", "0"),
                new ToolArguments(CommonArguments.HOURS_ARG, "The optional number of hours worth of tickets to return", "0"),
                new ToolArguments(CommonArguments.START_DATE, "The optional start date for the ticket query (e.g. 2024-01-01)", ""),
                new ToolArguments(CommonArguments.END_DATE, "The optional end date for the ticket query (e.g. 2024-12-31)", ""));
    }

    @Override
    public List<RagDocumentContext<ZenDeskTicket>> getContext(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final ZenDeskStatsConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final String organization = parsedArgs.getOrganization();

        if (StringUtils.isBlank(organization)) {
            throw new InternalFailure("You must provide an organisation name to query ZenDesk ticket statistics");
        }

        final ZenDeskCreds creds = new ZenDeskCreds(
                parsedArgs.getUrl(),
                parsedArgs.getUser(),
                parsedArgs.getSecretToken(),
                parsedArgs.getSecretAuthHeader());

        if (!creds.isValid()) {
            throw new InternalFailure("ZenDesk credentials are not configured correctly");
        }

        final String query = buildTicketQuery(parsedArgs, organization);

        final List<ZenDeskTicket> tickets = zenDeskClient.getTickets(
                creds.auth(),
                creds.url(),
                query,
                parsedArgs.getSearchTTL());

        final Map<String, Long> countsByStatus = tickets.stream()
                .collect(Collectors.groupingBy(
                        ticket -> StringUtils.defaultIfBlank(Objects.toString(ticket.status(), "unknown"), "unknown"),
                        Collectors.counting()));

        final long total = tickets.size();

        final String document = buildDocument(organization, countsByStatus, total);

        return List.of(new RagDocumentContext<>(
                getName(),
                getContextLabel(),
                document,
                List.of(),
                organization,
                null,
                null,
                List.of()));
    }

    @Override
    public RagMultiDocumentContext<ZenDeskTicket> call(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final List<RagDocumentContext<ZenDeskTicket>> contextList = getContext(environmentSettings, prompt, arguments);

        final Try<RagMultiDocumentContext<ZenDeskTicket>> result = Try.of(() -> contextList)
                .map(ragDoc -> new RagMultiDocumentContext<>(prompt, INSTRUCTIONS, ragDoc))
                .map(ragDoc -> llmClient.callWithCache(ragDoc, environmentSettings, getName()));

        return exceptionMapping.map(result).get();
    }

    private String buildTicketQuery(final ZenDeskStatsConfig.LocalArguments parsedArgs, final String organization) {
        final java.util.ArrayList<String> query = new java.util.ArrayList<>();
        query.add("type:ticket");
        query.add("organization:" + organization);

        // Manually supplied start date takes precedence
        if (StringUtils.isNotBlank(parsedArgs.getStartPeriod())) {
            query.add("created>" + parsedArgs.getStartPeriod());
        }

        // Manually supplied end date takes precedence
        if (StringUtils.isNotBlank(parsedArgs.getEndPeriod())) {
            query.add("created<" + parsedArgs.getEndPeriod());
        }

        // Fall back to computed date range from days/hours
        if (StringUtils.isBlank(parsedArgs.getStartPeriod()) && StringUtils.isBlank(parsedArgs.getEndPeriod())) {
            query.add("created>" + parsedArgs.getStartDate());
            query.add("created<" + parsedArgs.getEndDate());
        }

        return String.join(" ", query);
    }

    private String buildDocument(final String organization, final Map<String, Long> countsByStatus, final long total) {
        final StringBuilder sb = new StringBuilder();
        sb.append("ZenDesk Ticket Status Summary for organisation '").append(organization).append("':\n");

        countsByStatus.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n"));

        sb.append("Total tickets: ").append(total).append("\n");
        return sb.toString();
    }
}

@ApplicationScoped
class ZenDeskStatsConfig {

    private static final String DEFAULT_TTL_SECONDS = (60 * 60 * 24 * 90) + "";

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private ToStringGenerator toStringGenerator;

    @Inject
    private ValidateString validateString;

    @Inject
    @Identifier("AES")
    private Encryptor textEncryptor;

    @Inject
    @Identifier("everything")
    private DateParser dateParser;

    @Inject
    @ConfigProperty(name = "sb.zendesk.url")
    private Optional<String> configZenDeskUrl;

    @Inject
    @ConfigProperty(name = "sb.zendesk.user")
    private Optional<String> configZenDeskUser;

    @Inject
    @ConfigProperty(name = "sb.zendesk.accesstoken")
    private Optional<String> configZenDeskAccessToken;

    @Inject
    @ConfigProperty(name = "sb.zendesk.organization")
    private Optional<String> configZenDeskOrganization;

    @Inject
    @ConfigProperty(name = "sb.zendesk.historyttl")
    private Optional<String> configHistoryttl;

    @Inject
    @ConfigProperty(name = "sb.zendesk.days")
    private Optional<String> configZenDeskDays;

    @Inject
    @ConfigProperty(name = "sb.zendesk.hours")
    private Optional<String> configZenDeskHours;

    @Inject
    @ConfigProperty(name = "sb.zendesk.startperiod")
    private Optional<String> configZenDeskStartPeriod;

    @Inject
    @ConfigProperty(name = "sb.zendesk.endperiod")
    private Optional<String> configZenDeskEndPeriod;

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    public ToStringGenerator getToStringGenerator() {
        return toStringGenerator;
    }

    public ValidateString getValidateString() {
        return validateString;
    }

    public Encryptor getTextEncryptor() {
        return textEncryptor;
    }

    public DateParser getDateParser() {
        return dateParser;
    }

    public Optional<String> getConfigZenDeskUrl() {
        return configZenDeskUrl;
    }

    public Optional<String> getConfigZenDeskUser() {
        return configZenDeskUser;
    }

    public Optional<String> getConfigZenDeskAccessToken() {
        return configZenDeskAccessToken;
    }

    public Optional<String> getConfigZenDeskOrganization() {
        return configZenDeskOrganization;
    }

    public Optional<String> getConfigHistoryttl() {
        return configHistoryttl;
    }

    public Optional<String> getConfigZenDeskDays() {
        return configZenDeskDays;
    }

    public Optional<String> getConfigZenDeskHours() {
        return configZenDeskHours;
    }

    public Optional<String> getConfigZenDeskStartPeriod() {
        return configZenDeskStartPeriod;
    }

    public Optional<String> getConfigZenDeskEndPeriod() {
        return configZenDeskEndPeriod;
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

        public String getOrganization() {
            return getArgsAccessor().getArgument(
                    getConfigZenDeskOrganization()::get,
                    arguments,
                    context,
                    ZenDeskStats.ZENDESK_ORGANIZATION_ARG,
                    ZenDeskStats.ZENDESK_ORGANIZATION_ARG,
                    "").getSafeValue();
        }

        @SuppressWarnings("NullAway")
        public String getSecretToken() {
            final Try<String> token = Try
                    .of(() -> getTextEncryptor().decrypt(context.get("zendesk_access_token")))
                    .recover(e -> context.get("zendesk_access_token"))
                    .mapTry(getValidateString()::throwIfBlank)
                    .recoverWith(e -> Try.of(() -> getConfigZenDeskAccessToken().get()));

            if (token.isFailure() || StringUtils.isBlank(token.get())) {
                throw new InternalFailure("Failed to get Zendesk access token");
            }

            return token.get();
        }

        @SuppressWarnings("NullAway")
        public String getUrl() {
            final Try<String> url = Try
                    .of(() -> getTextEncryptor().decrypt(context.get("zendesk_url")))
                    .recover(e -> context.get("zendesk_url"))
                    .mapTry(getValidateString()::throwIfBlank)
                    .recoverWith(e -> Try.of(() -> getConfigZenDeskUrl().get()));

            if (url.isFailure() || StringUtils.isBlank(url.get())) {
                throw new InternalFailure("Failed to get Zendesk URL");
            }

            return url.get();
        }

        @SuppressWarnings("NullAway")
        public String getUser() {
            final Try<String> user = Try
                    .of(() -> getTextEncryptor().decrypt(context.get("zendesk_user")))
                    .recover(e -> context.get("zendesk_user"))
                    .mapTry(getValidateString()::throwIfBlank)
                    .recoverWith(e -> Try.of(() -> getConfigZenDeskUser().get()));

            if (user.isFailure() || StringUtils.isBlank(user.get())) {
                throw new InternalFailure("Failed to get Zendesk User");
            }

            return user.get();
        }

        public String getSecretAuthHeader() {
            return "Basic " + new String(Try.of(() -> new Base64().encode(
                    (getUser() + "/token:" + getSecretToken()).getBytes(StandardCharsets.UTF_8))).get(),
                    StandardCharsets.UTF_8);
        }

        public int getSearchTTL() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigHistoryttl()::get,
                    arguments,
                    context,
                    ZenDeskStats.ZENDESK_HISTORY_TTL_ARG,
                    ZenDeskStats.ZENDESK_HISTORY_TTL_ARG,
                    DEFAULT_TTL_SECONDS);

            return Try.of(argument::value)
                    .map(i -> Math.max(0, Integer.parseInt(i)))
                    .getOrElse(Integer.parseInt(DEFAULT_TTL_SECONDS));
        }

        private Argument getHoursArgument() {
            return getArgsAccessor().getArgument(
                    getConfigZenDeskHours()::get,
                    arguments,
                    context,
                    CommonArguments.HOURS_ARG,
                    CommonArguments.HOURS_ARG,
                    "0");
        }

        public int getRawHours() {
            return Try.of(() -> Integer.parseInt(getHoursArgument().getSafeValue()))
                    .recover(throwable -> 0)
                    .map(i -> Math.max(0, i))
                    .get();
        }

        private Argument getDaysArgument() {
            return getArgsAccessor().getArgument(
                    getConfigZenDeskDays()::get,
                    arguments,
                    context,
                    CommonArguments.DAYS_ARG,
                    CommonArguments.DAYS_ARG,
                    "0");
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
                    getConfigZenDeskStartPeriod()::get,
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
                    getConfigZenDeskEndPeriod()::get,
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
                    // Default to one day if nothing was specified
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
