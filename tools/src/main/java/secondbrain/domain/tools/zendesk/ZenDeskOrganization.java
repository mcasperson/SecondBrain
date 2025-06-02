package secondbrain.domain.tools.zendesk;

import io.smallrye.common.annotation.Identifier;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.validator.routines.EmailValidator;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.config.ModelConfig;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.debug.DebugToolArgs;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.FailedOllama;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.json.JsonDeserializer;
import secondbrain.domain.limit.DocumentTrimmer;
import secondbrain.domain.limit.ListLimiter;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.sanitize.SanitizeArgument;
import secondbrain.domain.sanitize.SanitizeDocument;
import secondbrain.domain.tooldefs.MetaObjectResult;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.tools.rating.RatingTool;
import secondbrain.domain.validate.ValidateInputs;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.zendesk.ZenDeskClient;
import secondbrain.infrastructure.zendesk.api.ZenDeskTicket;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.base.Predicates.instanceOf;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;

@ApplicationScoped
public class ZenDeskOrganization implements Tool<ZenDeskTicket> {
    public static final String ZENDESK_DISABLELINKS_ARG = "disableLinks";
    public static final String ZENDESK_ORGANIZATION_ARG = "zenDeskOrganization";
    public static final String EXCLUDE_ORGANIZATION_ARG = "excludeOrganization";
    public static final String EXCLUDE_SUBMITTERS_ARG = "excludeSubmitters";
    public static final String RECIPIENT_ARG = "recipient";
    public static final String NUM_COMMENTS_ARG = "numComments";
    public static final String SAVE_INDIVIDUAL_ARG = "saveIndividual";
    public static final String DAYS_ARG = "days";
    public static final String HOURS_ARG = "hours";
    public static final String START_PERIOD_ARG = "start";
    public static final String END_PERIOD_ARG = "end";
    public static final String ZENDESK_KEYWORD_ARG = "keywords";
    public static final String ZENDESK_KEYWORD_WINDOW_ARG = "keywordWindow";
    public static final String ZENDESK_ENTITY_NAME_CONTEXT_ARG = "entityName";
    public static final String ZENDESK_TICKET_SUMMARY_PROMPT_ARG = "ticketSummaryPrompt";
    public static final String ZENDESK_CONTEXT_FILTER_QUESTION_ARG = "contextFilterQuestion";
    public static final String ZENDESK_CONTEXT_FILTER_MINIMUM_RATING_ARG = "contextFilterMinimumRating";
    public static final String ZENDESK_CONTEXT_FILTER_BY_ORGANIZATION_ARG = "filterByOrganization";
    public static final String ZENDESK_CONTEXT_FILTER_BY_RECIPIENT_ARG = "filterByRecipient";


    private static final int MAX_TICKETS = 100;

    private static final String INSTRUCTIONS = """
            You are an expert in reading help desk tickets.
            You are given a question and the contents of ZenDesk Tickets related to the question.
            You must assume the information required to answer the question is present in the ZenDesk Tickets.
            You must ignore the list of excluded submitters.
            You must ignore the number of days worth of tickets to return.
            You must answer the question based on the ZenDesk Tickets provided.
            You will be tipped $1000 for answering the question directly from the ZenDesk Tickets.
            When the user asks a question indicating that they want to know about ZenDesk Tickets, you must generate the answer based on the ZenDesk Tickets.
            You will be penalized for suggesting manual steps to generate the answer.
            You will be penalized for providing a process to generate the answer.
            You will be penalized for responding that you don't have access to real-time data, specific ZenDesk data, or ZenDesk instances.
            You will be penalized for referencing issues that are not present in the ZenDesk Tickets.
            You will be penalized for refusing to provide information or guidance on real individuals.
            You will be penalized for responding that you can not provide a summary of the ZenDesk Tickets.
            You will be penalized for using terms like flooded, wave, or inundated.
            If there are no ZenDesk Tickets, you must indicate that in the answer.
            """.stripLeading();

    @Inject
    private ZenDeskIndividualTicket ticketTool;

    @Inject
    private RatingTool ratingTool;

    @Inject
    private ModelConfig modelConfig;

    @Inject
    private ZenDeskConfig config;

    @Inject
    @Identifier("removeSpacing")
    private SanitizeDocument removeSpacing;

    @Inject
    private OllamaClient ollamaClient;

    @Inject
    @Preferred
    private ZenDeskClient zenDeskClient;

    @Inject
    private ListLimiter listLimiter;

    @Inject
    private DebugToolArgs debugToolArgs;

    @Inject
    private PromptBuilderSelector promptBuilderSelector;

    @Inject
    private ValidateString validateString;

    @Inject
    private DocumentTrimmer documentTrimmer;

    @Inject
    private Logger log;

    @Inject
    private JsonDeserializer jsonDeserializer;

    @Override
    public String getName() {
        return ZenDeskOrganization.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Returns the details of ZenDesk support tickets";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return List.of(new ToolArguments(ZENDESK_ORGANIZATION_ARG, "An optional name of the organization", ""),
                new ToolArguments(EXCLUDE_ORGANIZATION_ARG, "An optional comma separated list of organizations to exclude", ""),
                new ToolArguments(ZENDESK_KEYWORD_ARG, "The keywords to limit the emails to", ""),
                new ToolArguments(ZENDESK_KEYWORD_WINDOW_ARG, "The window size around any matching keywords", ""),
                new ToolArguments(EXCLUDE_SUBMITTERS_ARG, "An optional comma separated list of submitters to exclude", ""),
                new ToolArguments(RECIPIENT_ARG, "An optional recipient email address that tickets must be sent to", ""),
                new ToolArguments(NUM_COMMENTS_ARG, "The optional number of comments to include in the context", "1"),
                new ToolArguments(DAYS_ARG, "The optional number of days worth of tickets to return", "0"),
                new ToolArguments(HOURS_ARG, "The optional number of hours worth of tickets to return", "0"));
    }

    @Override
    public String getContextLabel() {
        return "ZenDesk Ticket";
    }

    @Override
    public List<RagDocumentContext<ZenDeskTicket>> getContext(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final ZenDeskConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final List<String> query = new ArrayList<>();
        query.add("type:ticket");

        if (StringUtils.isNotBlank(parsedArgs.getStartPeriod())) {
            query.add("created>" + parsedArgs.getStartPeriod());
        }

        if (StringUtils.isNotBlank(parsedArgs.getEndPeriod())) {
            query.add("created<" + parsedArgs.getEndPeriod());
        }

        // The explicit dates take precedence over a day range
        if (StringUtils.isBlank(parsedArgs.getStartPeriod()) && StringUtils.isBlank(parsedArgs.getEndPeriod())) {
            query.add("created>" + parsedArgs.getStartDate());
        }

        if (parsedArgs.getZenDeskFilterByOrganization() && StringUtils.isNoneBlank(parsedArgs.getOrganization())) {
            query.add("organization:" + parsedArgs.getOrganization());
        }

        if (parsedArgs.getZenDeskFilterByRecipient() && StringUtils.isBlank(parsedArgs.getRecipient())) {
            query.add("recipient:" + parsedArgs.getRecipient());
        }

        // We can have multiple ZenDesk servers
        final List<ZenDeskCreds> zenDeskCreds = Stream.of(
                        new ZenDeskCreds(
                                parsedArgs.getUrl(),
                                parsedArgs.getUser(),
                                parsedArgs.getToken(),
                                parsedArgs.getAuthHeader()),
                        new ZenDeskCreds(
                                parsedArgs.getUrl2(),
                                parsedArgs.getUser2(),
                                parsedArgs.getToken2(),
                                parsedArgs.getAuthHeader2())
                )
                .filter(ZenDeskCreds::isValid)
                .toList();

        final Try<List<RagDocumentContext<ZenDeskTicket>>> result = Try.withResources(ClientBuilder::newClient)
                .of(client -> zenDeskCreds
                        .stream()
                        .flatMap(zenDeskCred -> getContext(
                                parsedArgs,
                                environmentSettings,
                                zenDeskCred,
                                String.join(" ", query),
                                client).stream())
                        .toList());

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(
                        API.Case(API.$(instanceOf(EmptyString.class)), throwable -> new InternalFailure("The ZenDesk ticket is empty", throwable)),
                        API.Case(API.$(instanceOf(InternalFailure.class)), throwable -> throwable),
                        API.Case(API.$(instanceOf(FailedOllama.class)), throwable -> new InternalFailure(throwable.getMessage(), throwable)),
                        API.Case(API.$(), ex -> new ExternalFailure(getName() + " failed to call ZenDesk API", ex)))
                .get();

    }

    private List<RagDocumentContext<ZenDeskTicket>> getContext(
            final ZenDeskConfig.LocalArguments parsedArgs,
            final Map<String, String> environmentSettings,
            final ZenDeskCreds creds,
            final String query,
            final Client client) {
        final List<RagDocumentContext<ZenDeskTicket>> context = Try.of(() -> zenDeskClient.getTickets(
                        client,
                        creds.auth(),
                        creds.url(),
                        String.join(" ", query),
                        parsedArgs.getSearchTTL()))
                // Filter out any tickets based on the submitter and assignee
                .map(response -> filterResponse(
                        response,
                        parsedArgs.getOrganization(),
                        true,
                        parsedArgs.getExcludedSubmitters(),
                        parsedArgs.getExcludedOrganization(),
                        parsedArgs.getRecipient()))
                // Limit how many tickets we process. We're unlikely to be able to pass the details of many tickets to the LLM anyway
                .map(response -> response.subList(0, Math.min(response.size(), MAX_TICKETS)))
                // Get the ticket comments (i.e., the initial email)
                .map(response -> ticketToComments(
                        response,
                        environmentSettings,
                        creds.url(),
                        creds.user(),
                        creds.token(),
                        parsedArgs))
                /*
                    If there is a filter question (usually a question to remove spam or irrelevant tickets),
                    then we filter the tickets based on the rating of the context.
                 */
                .map(tickets -> contextMeetsRating(tickets, parsedArgs))
                .map(tickets -> trimTickets(tickets, parsedArgs))
                /*
                    Take the raw ticket comments and summarize them with individual calls to the LLM.
                    The individual ticket summaries are then combined into a single context.
                    This was necessary because the private LLMs didn't do a very good job of summarizing
                    raw tickets. The reality is that even LLMs with a context length of 128k tokens mostly fixated
                    one a small number of tickets.
                 */
                .map(tickets -> summariseTickets(tickets, environmentSettings, parsedArgs))
                // Don't let one failed instance block the others
                .onFailure(throwable -> log.warning("Failed to get tickets: " + ExceptionUtils.getRootCauseMessage(throwable)))
                .recover(throwable -> List.of())
                .get();

        saveFiles(context, parsedArgs);

        return context;
    }

    private void saveFiles(final List<RagDocumentContext<ZenDeskTicket>> context, final ZenDeskConfig.LocalArguments parsedArgs) {
        if (parsedArgs.getSaveIndividual()) {
            context.forEach(ticket -> Try.of(() -> Files.write(
                    Paths.get(ticketToFileName(ticket)),
                    ticket.document().getBytes(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING)));

            context.forEach(ticket -> Try.of(() -> Files.write(
                    Paths.get(ticketToMetaFileName(ticket)),
                    jsonDeserializer.serialize(ticket.meta().toMetaObjectResult()).getBytes(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING)));
        }
    }

    private String ticketToFileName(final RagDocumentContext<ZenDeskTicket> ticket) {
        return "ZenDesk-" + ticket.id() + ".md";
    }

    private String ticketToMetaFileName(final RagDocumentContext<ZenDeskTicket> ticket) {
        return "ZenDesk-" + ticket.id() + ".json";
    }

    @Override
    public List<MetaObjectResult> getMetadata(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        return List.of();
    }

    @Override
    public RagMultiDocumentContext<ZenDeskTicket> call(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {

        final ZenDeskConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final String debugArgs = debugToolArgs.debugArgs(arguments);

        final Try<RagMultiDocumentContext<ZenDeskTicket>> result = Try.of(() -> getContext(environmentSettings, prompt, arguments))
                // Limit the list to just those that fit in the context
                .map(list -> listLimiter.limitListContent(
                        list,
                        RagDocumentContext::document,
                        modelConfig.getCalculatedContextWindow(environmentSettings)))
                // Combine the individual zen desk tickets into a parent RagMultiDocumentContext
                .map(tickets -> mergeContext(tickets, debugArgs, modelConfig.getCalculatedModel(environmentSettings)))
                // Make sure we had some content for the prompt
                .mapTry(mergedContext ->
                        validateString.throwIfEmpty(mergedContext, RagMultiDocumentContext::combinedDocument))
                // Build the final prompt including instructions, context and the user prompt
                .map(ragContext -> ragContext.updateDocument(
                        promptBuilderSelector
                                .getPromptBuilder(modelConfig.getCalculatedModel(environmentSettings))
                                .buildFinalPrompt(INSTRUCTIONS, ragContext.combinedDocument(), prompt)))
                // Call Ollama with the final prompt
                .map(ragDoc -> ollamaClient.callOllamaWithCache(
                        ragDoc,
                        modelConfig.getCalculatedModel(environmentSettings),
                        getName(),
                        modelConfig.getCalculatedContextWindow(environmentSettings)))
                // Clean up the response
                .map(response -> response.updateDocument(removeSpacing.sanitize(response.combinedDocument())))
                .recover(EmptyString.class, e -> new RagMultiDocumentContext<>("No tickets found after " + parsedArgs.getStartDate() + " for organization '" + parsedArgs.getOrganization() + "'", List.of(), ""));

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(
                        API.Case(API.$(),
                                throwable -> new ExternalFailure("Failed to get tickets or context: " + throwable.toString() + " " + throwable.getMessage() + debugArgs)))
                .get();
    }

    private RagMultiDocumentContext<ZenDeskTicket> mergeContext(final List<RagDocumentContext<ZenDeskTicket>> context, final String debug, final String customModel) {
        return new RagMultiDocumentContext<>(
                context.stream()
                        .map(RagDocumentContext::document)
                        .map(content -> promptBuilderSelector
                                .getPromptBuilder(customModel)
                                .buildContextPrompt("ZenDesk Ticket", content))
                        .collect(Collectors.joining("\n")),
                context,
                debug);
    }


    private List<ZenDeskTicket> filterResponse(
            final List<ZenDeskTicket> tickets,
            final String organization,
            final boolean forceAssignee,
            final List<String> exclude,
            final List<String> excludedOwner,
            final String recipient) {
        if (!forceAssignee && exclude.isEmpty() && StringUtils.isBlank(recipient)) {
            return tickets;
        }

        return tickets.stream()
                .filter(ticket -> !exclude.contains(ticket.submitter_id()))
                .filter(ticket -> !excludedOwner.contains(ticket.organization_id()))
                .filter(ticket -> !forceAssignee || !StringUtils.isBlank(ticket.assignee_id()))
                .filter(ticket -> StringUtils.isBlank(recipient) || recipient.equals(ticket.recipient()))
                .filter(ticket -> StringUtils.isBlank(organization) || organization.equals(ticket.organization_id()))
                .collect(Collectors.toList());
    }

    /**
     * Summarize the tickets by passing them through the LLM
     */
    private List<RagDocumentContext<ZenDeskTicket>> summariseTickets(
            final List<RagDocumentContext<ZenDeskTicket>> tickets,
            final Map<String, String> context,
            final ZenDeskConfig.LocalArguments parsedArgs) {
        return tickets.stream()
                .map(ticket -> ticket.updateDocument(getTicketSummary(ticket.document(), context, parsedArgs)))
                .collect(Collectors.toList());
    }

    private List<RagDocumentContext<ZenDeskTicket>> trimTickets(final List<RagDocumentContext<ZenDeskTicket>> tickets, final ZenDeskConfig.LocalArguments parsedArgs) {
        return tickets.stream()
                .map(ticket -> ticket.updateDocument(
                        documentTrimmer.trimDocumentToKeywords(
                                ticket.document(),
                                parsedArgs.getKeywords(),
                                parsedArgs.getKeywordWindow())))
                .filter(ticket -> validateString.isNotEmpty(ticket.document()))
                .collect(Collectors.toList());
    }

    /**
     * Summarize an individual ticket
     */
    private String getTicketSummary(final String ticketContents,
                                    final Map<String, String> environmentSettings,
                                    final ZenDeskConfig.LocalArguments parsedArgs) {
        final String ticketContext = promptBuilderSelector
                .getPromptBuilder(modelConfig.getCalculatedModel(environmentSettings))
                .buildContextPrompt("ZenDesk Ticket", ticketContents);

        final String prompt = promptBuilderSelector
                .getPromptBuilder(modelConfig.getCalculatedModel(environmentSettings))
                .buildFinalPrompt("You are a helpful agent", ticketContext, parsedArgs.getTicketSummaryPrompt());

        return ollamaClient.callOllamaWithCache(
                new RagMultiDocumentContext<>(prompt),
                modelConfig.getCalculatedModel(environmentSettings),
                getName(),
                modelConfig.getCalculatedContextWindow(environmentSettings)
        ).combinedDocument();
    }

    private List<RagDocumentContext<ZenDeskTicket>> ticketToComments(final List<ZenDeskTicket> tickets,
                                                                     final Map<String, String> environmentSettings,
                                                                     final String url,
                                                                     final String email,
                                                                     final String token,
                                                                     final ZenDeskConfig.LocalArguments parsedArgs) {
        return tickets.stream()
                // Get the context associated with the ticket
                .flatMap(ticket -> ticketTool.getContext(
                        environmentSettings,
                        parsedArgs.getTicketSummaryPrompt(),
                        List.of(
                                new ToolArgs(ZenDeskIndividualTicket.ZENDESK_TICKET_ID_ARG, ticket.id(), true),
                                new ToolArgs(ZenDeskIndividualTicket.ZENDESK_URL_ARG, url, true),
                                new ToolArgs(ZenDeskIndividualTicket.ZENDESK_EMAIL_ARG, email, true),
                                new ToolArgs(ZenDeskIndividualTicket.ZENDESK_TOKEN_ARG, token, true)
                        )
                ).stream())
                // Get a list of context strings
                .collect(Collectors.toList());
    }

    private List<RagDocumentContext<ZenDeskTicket>> contextMeetsRating(
            final List<RagDocumentContext<ZenDeskTicket>> tickets,
            final ZenDeskConfig.LocalArguments parsedArgs) {
        if (parsedArgs.getContextFilterMinimumRating() <= 0 || StringUtils.isBlank(parsedArgs.getContextFilterQuestion())) {
            return tickets;
        }

        return tickets.stream()
                .filter(ticket ->
                        getContextRating(ticket, parsedArgs) >= parsedArgs.getContextFilterMinimumRating()
                )
                .toList();
    }

    private Integer getContextRating(final RagDocumentContext<ZenDeskTicket> ticket, final ZenDeskConfig.LocalArguments parsedArgs) {
        if (parsedArgs.getContextFilterMinimumRating() <= 0 || StringUtils.isBlank(parsedArgs.getContextFilterQuestion())) {
            return 10;
        }

        return Try.of(() -> ratingTool.call(
                        Map.of(RatingTool.RATING_DOCUMENT_CONTEXT_ARG, ticket.document()),
                        parsedArgs.getContextFilterQuestion(),
                        List.of()).combinedDocument())
                .map(rating -> org.apache.commons.lang3.math.NumberUtils.toInt(rating, 0))
                // Ratings are provided on a best effort basis, so we ignore any failures
                .recover(InternalFailure.class, ex -> 10)
                .get();
    }
}

@ApplicationScoped
class ZenDeskConfig {
    private static final int MAX_TICKETS = 100;
    private static final String DEFAULT_TTL = (1000 * 60 * 60 * 24) + "";

    @Inject
    @ConfigProperty(name = "sb.zendesk.saveindividual", defaultValue = "false")
    private Optional<String> configSaveIndividual;

    /**
     * Set this to true to include the organization in the query sent to the ZenDesk API.
     * Including the organization results in a targeted set of results, and is useful
     * when you are building a report for a specific organization, especially over a long time period.
     * Set this to false to ignore the organization in the query. This returns all tickets
     * in a time period. This is useful when you rerun this query many times to get the
     * results of many different organizations. The cached result of the API call is then used
     * between calls. Individual organizations are then filtered out from the collective results client side.
     */
    @Inject
    @ConfigProperty(name = "sb.zendesk.filterbyorganization", defaultValue = "false")
    private Optional<String> configZenDeskFilterByOrganization;

    @Inject
    @ConfigProperty(name = "sb.zendesk.filterbyrecipient", defaultValue = "false")
    private Optional<String> configZenDeskFilterByRecipient;

    @Inject
    @ConfigProperty(name = "sb.zendesk.accesstoken")
    private Optional<String> configZenDeskAccessToken;

    @Inject
    @ConfigProperty(name = "sb.zendesk.user")
    private Optional<String> configZenDeskUser;

    @Inject
    @ConfigProperty(name = "sb.zendesk.url")
    private Optional<String> configZenDeskUrl;

    @Inject
    @ConfigProperty(name = "sb.zendesk.accesstoken2")
    private Optional<String> configZenDeskAccessToken2;

    @Inject
    @ConfigProperty(name = "sb.zendesk.user2")
    private Optional<String> configZenDeskUser2;

    @Inject
    @ConfigProperty(name = "sb.zendesk.url2")
    private Optional<String> configZenDeskUrl2;

    @Inject
    @ConfigProperty(name = "sb.zendesk.excludedorgs")
    private Optional<String> configZenExcludedOrgs;

    @Inject
    @ConfigProperty(name = "sb.zendesk.recipient")
    private Optional<String> configZenDeskRecipient;

    @Inject
    @ConfigProperty(name = "sb.zendesk.organization")
    private Optional<String> configZenDeskOrganization;

    @Inject
    @ConfigProperty(name = "sb.zendesk.days")
    private Optional<String> configZenDeskDays;

    @Inject
    @ConfigProperty(name = "sb.zendesk.startperiod")
    private Optional<String> configZenDeskStartPeriod;

    @Inject
    @ConfigProperty(name = "sb.zendesk.endperiod")
    private Optional<String> configZenDeskEndPeriod;

    @Inject
    @ConfigProperty(name = "sb.zendesk.hours")
    private Optional<String> configZenDeskHours;

    @Inject
    @ConfigProperty(name = "sb.zendesk.numcomments")
    private Optional<String> configZenDeskNumComments;

    @Inject
    @ConfigProperty(name = "sb.zendesk.excludedsubmitters")
    private Optional<String> configZenDeskExcludedSubmitters;

    @Inject
    @ConfigProperty(name = "sb.zendesk.disablelinks")
    private Optional<String> configDisableLinks;

    @Inject
    @ConfigProperty(name = "sb.zendesk.keywords")
    private Optional<String> configKeywords;

    @Inject
    @ConfigProperty(name = "sb.zendesk.keywordwindow")
    private Optional<String> configKeywordWindow;

    @Inject
    @ConfigProperty(name = "sb.zendesk.historyttl")
    private Optional<String> configHistoryttl;

    @Inject
    @ConfigProperty(name = "sb.zendesk.ticketsummaryprompt")
    private Optional<String> configTicketSummaryPrompt;

    @Inject
    @ConfigProperty(name = "sb.zendesk.contextFilterQuestion")
    private Optional<String> configContextFilterQuestion;

    @Inject
    @ConfigProperty(name = "sb.zendesk.contextFilterMinimumRating")
    private Optional<String> configContextFilterMinimumRating;

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private ValidateInputs validateInputs;

    @Inject
    private Encryptor textEncryptor;

    @Inject
    private ValidateString validateString;

    @Inject
    @Identifier("sanitizeEmail")
    private SanitizeArgument sanitizeEmail;

    @Inject
    @Identifier("sanitizeOrganization")
    private SanitizeArgument sanitizeOrganization;

    public Optional<String> getConfigContextFilterQuestion() {
        return configContextFilterQuestion;
    }

    public Optional<String> getConfigContextFilterMinimumRating() {
        return configContextFilterMinimumRating;
    }

    public Optional<String> getConfigZenDeskAccessToken() {
        return configZenDeskAccessToken;
    }

    public Optional<String> getConfigZenDeskUser() {
        return configZenDeskUser;
    }

    public Optional<String> getConfigZenDeskUrl() {
        return configZenDeskUrl;
    }

    public Optional<String> getConfigZenExcludedOrgs() {
        return configZenExcludedOrgs;
    }

    public Optional<String> getConfigZenDeskRecipient() {
        return configZenDeskRecipient;
    }

    public Optional<String> getConfigZenDeskOrganization() {
        return configZenDeskOrganization;
    }

    public Optional<String> getConfigZenDeskDays() {
        return configZenDeskDays;
    }

    public Optional<String> getConfigZenDeskHours() {
        return configZenDeskHours;
    }

    public Optional<String> getConfigZenDeskNumComments() {
        return configZenDeskNumComments;
    }

    public Optional<String> getConfigZenDeskExcludedSubmitters() {
        return configZenDeskExcludedSubmitters;
    }

    public Optional<String> getConfigDisableLinks() {
        return configDisableLinks;
    }

    public Optional<String> getConfigKeywords() {
        return configKeywords;
    }

    public Optional<String> getConfigKeywordWindow() {
        return configKeywordWindow;
    }

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    public ValidateInputs getValidateInputs() {
        return validateInputs;
    }

    public Encryptor getTextEncryptor() {
        return textEncryptor;
    }

    public ValidateString getValidateString() {
        return validateString;
    }

    public SanitizeArgument getSanitizeEmail() {
        return sanitizeEmail;
    }

    public SanitizeArgument getSanitizeOrganization() {
        return sanitizeOrganization;
    }

    public Optional<String> getConfigHistoryttl() {
        return configHistoryttl;
    }

    public Optional<String> getConfigZenDeskAccessToken2() {
        return configZenDeskAccessToken2;
    }

    public Optional<String> getConfigZenDeskUser2() {
        return configZenDeskUser2;
    }

    public Optional<String> getConfigZenDeskUrl2() {
        return configZenDeskUrl2;
    }

    /**
     * This setting determines if the API call to ZenDesk includes the organization ID in the query.
     * This is useful when querying for results over a long timeframe but for a specific organization.
     * Leave this as false if the ZenDesk API call should return all results over the time period.
     * Returning all results is useful if you query the result sets multiple times over with different
     * organizations, as the first call is cached, and all subsequent calls are much faster.
     */
    public Optional<String> getConfigZenDeskFilterByOrganization() {
        return configZenDeskFilterByOrganization;
    }

    /**
     * This setting determines if the API call to ZenDesk includes the recipient in the query.
     * This is useful when querying for results over a long timeframe but for a specific recipient.
     * Leave this as false if the ZenDesk API call should return all results over the time period.
     * Returning all results is useful if you query the result sets multiple times over with different
     * recipient, as the first call is cached, and all subsequent calls are much faster.
     */
    public Optional<String> getConfigZenDeskFilterByRecipient() {
        return configZenDeskFilterByRecipient;
    }

    public Optional<String> getConfigTicketSummaryPrompt() {
        return configTicketSummaryPrompt;
    }

    public Optional<String> getConfigZenDeskStartPeriod() {
        return configZenDeskStartPeriod;
    }

    public Optional<String> getConfigZenDeskEndPeriod() {
        return configZenDeskEndPeriod;
    }

    /**
     * Set this value to true to save the result of each ZenDesk ticket to
     * a file. This is useful if you wish to build a report from each ticket as
     * opposed to the result of a prompt run against all the tickets.
     */
    public Optional<String> getConfigSaveIndividual() {
        return configSaveIndividual;
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

        public boolean getSaveIndividual() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigSaveIndividual()::get,
                    arguments,
                    context,
                    ZenDeskOrganization.SAVE_INDIVIDUAL_ARG,
                    "zendesk_saveindividual",
                    "false").value();

            return Try.of(() -> BooleanUtils.toBoolean(stringValue))
                    .recover(throwable -> false)
                    .get();
        }

        public String getContextFilterQuestion() {
            return getArgsAccessor().getArgument(
                            getConfigContextFilterQuestion()::get,
                            arguments,
                            context,
                            ZenDeskOrganization.ZENDESK_CONTEXT_FILTER_QUESTION_ARG,
                            "zendesk_context_filter_question",
                            "")
                    .value();
        }

        public Integer getContextFilterMinimumRating() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigContextFilterMinimumRating()::get,
                    arguments,
                    context,
                    ZenDeskOrganization.ZENDESK_CONTEXT_FILTER_MINIMUM_RATING_ARG,
                    "multislackzengoogle_context_filter_minimum_rating",
                    "0");

            return org.apache.commons.lang.math.NumberUtils.toInt(argument.value(), 0);
        }

        public String getZenDeskUrl() {
            return getConfigZenDeskUrl().get();
        }

        public String getRawOrganization() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigZenDeskOrganization()::get,
                    arguments,
                    context,
                    ZenDeskOrganization.ZENDESK_ORGANIZATION_ARG,
                    "zendesk_organization",
                    "");

            if (argument.trusted()) {
                return argument.value();
            }

            return getValidateInputs().getCommaSeparatedList(
                    prompt,
                    argument.value());
        }

        public String getOrganization() {
            // Organization is just a name or number. If the organization is an email address, it was mixed up for the receipt.
            if (EmailValidator.getInstance().isValid(getSanitizeEmail().sanitize(getRawOrganization(), prompt)) && StringUtils.isBlank(getRecipient())) {
                return "";
            }

            return getSanitizeOrganization().sanitize(getRawOrganization(), prompt);
        }

        public List<String> getExcludedOrganization() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigZenExcludedOrgs()::get,
                    arguments,
                    context,
                    ZenDeskOrganization.EXCLUDE_ORGANIZATION_ARG,
                    "zendesk_excludeorganization",
                    "");

            final String stringValue = argument.trusted()
                    ? argument.value()
                    : getValidateInputs().getCommaSeparatedList(prompt, argument.value());

            final List<String> excludedOrganization = Arrays.stream(stringValue.split(","))
                    .map(String::trim)
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.toList());

            excludedOrganization.addAll(Arrays.stream(getConfigZenExcludedOrgs().orElse("").split(",")).toList());

            return excludedOrganization;
        }

        public String getRawRecipient() {
            return getArgsAccessor().getArgument(
                    getConfigZenDeskRecipient()::get,
                    arguments,
                    context,
                    ZenDeskOrganization.RECIPIENT_ARG,
                    "zendesk_recipient",
                    "").value();
        }

        public String getRecipient() {
            // Organization is just a name or number. If organization is an email address, it was mixed up for the receipt.
            if (EmailValidator.getInstance().isValid(getSanitizeEmail().sanitize(getRawOrganization(), prompt)) && StringUtils.isBlank(getRawRecipient())) {
                return getSanitizeEmail().sanitize(getRawOrganization(), prompt);
            }

            return getSanitizeEmail().sanitize(getRawRecipient(), prompt);
        }

        public List<String> getExcludedSubmitters() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigZenDeskExcludedSubmitters()::get,
                    arguments,
                    context,
                    ZenDeskOrganization.EXCLUDE_SUBMITTERS_ARG,
                    "zendesk_excludesubmitters",
                    "").value();

            return Arrays.stream(stringValue.split(","))
                    .map(String::trim)
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.toList());
        }

        private Argument getHoursArgument() {
            return getArgsAccessor().getArgument(
                    getConfigZenDeskHours()::get,
                    arguments,
                    context,
                    ZenDeskOrganization.HOURS_ARG,
                    "zendesk_hours",
                    "0");
        }

        public int getRawHours() {
            final String stringValue = getHoursArgument().value();

            return Try.of(() -> Integer.parseInt(stringValue))
                    .recover(throwable -> 0)
                    .map(i -> Math.max(0, i))
                    .get();
        }

        private Argument getDaysArgument() {
            return getArgsAccessor().getArgument(
                    getConfigZenDeskDays()::get,
                    arguments,
                    context,
                    ZenDeskOrganization.DAYS_ARG,
                    "zendesk_days",
                    "0");
        }

        public int getRawDays() {
            final String stringValue = getDaysArgument().value();

            return Try.of(() -> Integer.parseInt(stringValue))
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
            return getArgsAccessor().getArgument(
                            getConfigZenDeskStartPeriod()::get,
                            arguments,
                            context,
                            ZenDeskOrganization.START_PERIOD_ARG,
                            "zendesk_startperiod",
                            "")
                    .value();
        }

        public String getEndPeriod() {
            return getArgsAccessor().getArgument(
                            getConfigZenDeskEndPeriod()::get,
                            arguments,
                            context,
                            ZenDeskOrganization.END_PERIOD_ARG,
                            "zendesk_endperiod",
                            "")
                    .value();
        }

        public int getNumComments() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigZenDeskNumComments()::get,
                    arguments,
                    context,
                    ZenDeskOrganization.NUM_COMMENTS_ARG,
                    "zendesk_numcomments",
                    MAX_TICKETS + "").value();

            return Try.of(() -> Integer.parseInt(stringValue))
                    .recover(throwable -> MAX_TICKETS)
                    // Must be at least 1
                    .map(i -> Math.max(1, i))
                    .get();
        }

        public boolean getZenDeskFilterByOrganization() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigZenDeskFilterByOrganization()::get,
                    arguments,
                    context,
                    ZenDeskOrganization.ZENDESK_CONTEXT_FILTER_BY_ORGANIZATION_ARG,
                    "zendesk_filterbyorganization",
                    "false").value();

            return Try.of(() -> BooleanUtils.toBoolean(stringValue))
                    .recover(throwable -> false)
                    .get();
        }

        public boolean getZenDeskFilterByRecipient() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigZenDeskFilterByRecipient()::get,
                    arguments,
                    context,
                    ZenDeskOrganization.ZENDESK_CONTEXT_FILTER_BY_RECIPIENT_ARG,
                    "zendesk_filterbyrecipient",
                    "false").value();

            return Try.of(() -> BooleanUtils.toBoolean(stringValue))
                    .recover(throwable -> false)
                    .get();
        }

        public String getAuthHeader() {
            return "Basic " + new String(Try.of(() -> new Base64().encode(
                    (getUser() + "/token:" + getToken()).getBytes(UTF_8))).get(), UTF_8);
        }

        public String getToken() {
            // Try to decrypt the value, otherwise assume it is a plain text value, and finally
            // fall back to the value defined in the local configuration.
            final Try<String> token = Try.of(() -> getTextEncryptor().decrypt(context.get("zendesk_access_token")))
                    .recover(e -> context.get("zendesk_access_token"))
                    .mapTry(getValidateString()::throwIfEmpty)
                    .recoverWith(e -> Try.of(() -> getConfigZenDeskAccessToken().get()));

            if (token.isFailure() || StringUtils.isBlank(token.get())) {
                throw new InternalFailure("Failed to get Zendesk access token");
            }

            return token.get();
        }

        public String getUrl() {
            final Try<String> url = getContext("zendesk_url", context, getTextEncryptor())
                    .recoverWith(e -> Try.of(() -> getConfigZenDeskUrl().get()));

            if (url.isFailure() || StringUtils.isBlank(url.get())) {
                throw new InternalFailure("Failed to get Zendesk URL");
            }

            return url.get();
        }

        public String getUser() {
            final Try<String> user = Try.of(() -> getTextEncryptor().decrypt(context.get("zendesk_user")))
                    .recover(e -> context.get("zendesk_user"))
                    .mapTry(getValidateString()::throwIfEmpty)
                    .recoverWith(e -> Try.of(() -> getConfigZenDeskUser().get()));

            if (user.isFailure() || StringUtils.isBlank(user.get())) {
                throw new InternalFailure("Failed to get Zendesk User");
            }

            return user.get();
        }

        public String getAuthHeader2() {
            if (StringUtils.isBlank(getToken2())) {
                return "";
            }

            return "Basic " + new String(Try.of(() -> new Base64().encode(
                    (getUser2() + "/token:" + getToken2()).getBytes(UTF_8))).get(), UTF_8);
        }

        public String getToken2() {
            // Try to decrypt the value, otherwise assume it is a plain text value, and finally
            // fall back to the value defined in the local configuration.
            final Try<String> token = Try.of(() -> getTextEncryptor().decrypt(context.get("zendesk_access_token2")))
                    .recover(e -> context.get("zendesk_access_token2"))
                    .mapTry(getValidateString()::throwIfEmpty)
                    .recoverWith(e -> Try.of(() -> getConfigZenDeskAccessToken2().get()));

            if (token.isFailure() || StringUtils.isBlank(token.get())) {
                return "";
            }

            return token.get();
        }

        public String getUrl2() {
            return getArgsAccessor().getArgument(
                    getConfigZenDeskUrl2()::get,
                    arguments,
                    context,
                    "url2",
                    "zendesk_url2",
                    "").value();
        }

        public String getUser2() {
            return getArgsAccessor().getArgument(
                    getConfigZenDeskUser2()::get,
                    arguments,
                    context,
                    "user2",
                    "zendesk_user2",
                    "").value();
        }

        public String getStartDate() {
            // Truncating to hours or days means the cache has a higher chance of being hit.
            final TemporalUnit truncatedTo = getHours() == 0
                    ? ChronoUnit.DAYS
                    : ChronoUnit.HOURS;

            return OffsetDateTime.now(ZoneId.systemDefault())
                    .truncatedTo(truncatedTo)
                    // Assume one day if nothing was specified
                    .minusDays(getDays() + getHours() == 0 ? 1 : getDays())
                    .minusHours(getHours())
                    .format(ISO_OFFSET_DATE_TIME);
        }

        private Try<String> getContext(final String name, final Map<String, String> context, Encryptor textEncryptor) {
            return Try.of(() -> textEncryptor.decrypt(context.get(name)))
                    .recover(e -> context.get(name))
                    .mapTry(Objects::requireNonNull);
        }

        private int switchArguments(final String prompt, final int a, final int b, final String aPromptKeyword, final String bPromptKeyword) {
            final Locale locale = Locale.getDefault();

            // If the prompt did not mention the keyword for the first argument, assume that it was never mentioned, and return 0
            if (!prompt.toLowerCase(locale).contains(aPromptKeyword.toLowerCase(locale))) {
                return 0;
            }

            // If the prompt did mention the first argument, but did not mention the keyword for the second argument,
            // and the first argument is 0, assume the LLM switched things up, and return the second argument
            if (!prompt.toLowerCase(locale).contains(bPromptKeyword.toLowerCase(locale)) && a == 0) {
                return b;
            }

            // If both the first and second keywords were mentioned, we just have to trust the LLM
            return a;
        }

        public boolean getDisableLinks() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigDisableLinks()::get,
                    arguments,
                    context,
                    ZenDeskOrganization.ZENDESK_DISABLELINKS_ARG,
                    "zendesk_disable_links",
                    "false").value();

            return BooleanUtils.toBoolean(stringValue);
        }

        public List<String> getKeywords() {
            return getArgsAccessor().getArgumentList(
                            getConfigKeywords()::get,
                            arguments,
                            context,
                            ZenDeskOrganization.ZENDESK_KEYWORD_ARG,
                            "zendesk_keywords",
                            "")
                    .stream()
                    .map(Argument::value)
                    .toList();
        }

        public int getKeywordWindow() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigKeywordWindow()::get,
                    arguments,
                    context,
                    ZenDeskOrganization.ZENDESK_KEYWORD_WINDOW_ARG,
                    "zendesk_keyword_window",
                    Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH + "");

            return NumberUtils.toInt(argument.value(), Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH);
        }

        public String getEntity() {
            return getArgsAccessor().getArgument(
                    null,
                    null,
                    context,
                    null,
                    ZenDeskOrganization.ZENDESK_ENTITY_NAME_CONTEXT_ARG,
                    "").value();
        }

        public int getSearchTTL() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigHistoryttl()::get,
                    arguments,
                    context,
                    "historyTtl",
                    "zen_historyttl",
                    DEFAULT_TTL);

            return Try.of(argument::value)
                    .map(i -> Math.max(0, Integer.parseInt(i)))
                    .get();
        }

        public String getTicketSummaryPrompt() {
            return getArgsAccessor()
                    .getArgument(
                            getConfigTicketSummaryPrompt()::get,
                            arguments,
                            context,
                            ZenDeskOrganization.ZENDESK_TICKET_SUMMARY_PROMPT_ARG,
                            "zen_ticketSummaryPrompt",
                            """
                                    Summarise the ticket in one paragraph.
                                    You will be penalized for including ticket numbers or IDs, invoice numbers, purchase order numbers, or reference numbers.""".stripLeading())
                    .value();
        }
    }
}

record ZenDeskCreds(
        String url,
        String user,
        String token,
        String auth
) {
    public boolean isValid() {
        return StringUtils.isNotBlank(url) && StringUtils.isNotBlank(user) && StringUtils.isNotBlank(token) && StringUtils.isNotBlank(auth);
    }
}