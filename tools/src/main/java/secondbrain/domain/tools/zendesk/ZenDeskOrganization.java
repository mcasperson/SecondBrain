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
import org.jspecify.annotations.Nullable;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.config.ModelConfig;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.*;
import secondbrain.domain.debug.DebugToolArgs;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.FailedOllama;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.limit.DocumentTrimmer;
import secondbrain.domain.limit.ListLimiter;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.sanitize.SanitizeArgument;
import secondbrain.domain.sanitize.SanitizeDocument;
import secondbrain.domain.tooldefs.MetaObjectResult;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateInputs;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.zendesk.*;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.base.Predicates.instanceOf;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;

@ApplicationScoped
public class ZenDeskOrganization implements Tool<ZenDeskResultsResponse> {
    public static final String ZENDESK_DISABLELINKS_ARG = "disableLinks";
    public static final String ZENDESK_ORGANIZATION_ARG = "zenDeskOrganization";
    public static final String EXCLUDE_ORGANIZATION_ARG = "excludeOrganization";
    public static final String EXCLUDE_SUBMITTERS_ARG = "excludeSubmitters";
    public static final String RECIPIENT_ARG = "recipient";
    public static final String NUM_COMMENTS_ARG = "numComments";
    public static final String DAYS_ARG = "days";
    public static final String HOURS_ARG = "hours";
    public static final String ZENDESK_KEYWORD_ARG = "keywords";
    public static final String ZENDESK_KEYWORD_WINDOW_ARG = "keywordWindow";
    public static final String ZENDESK_ENTITY_NAME_CONTEXT_ARG = "entityName";


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
    private ModelConfig modelConfig;

    @Inject
    private ZenDeskConfig config;

    @Inject
    @Identifier("removeSpacing")
    private SanitizeDocument removeSpacing;

    @Inject
    private OllamaClient ollamaClient;

    @Inject
    private ZenDeskClient zenDeskClient;

    @Inject
    private ListLimiter listLimiter;

    @Inject
    private DebugToolArgs debugToolArgs;

    @Inject
    private SentenceSplitter sentenceSplitter;

    @Inject
    private SentenceVectorizer sentenceVectorizer;

    @Inject
    private PromptBuilderSelector promptBuilderSelector;

    @Inject
    private ValidateString validateString;

    @Inject
    private DocumentTrimmer documentTrimmer;

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
    public List<RagDocumentContext<ZenDeskResultsResponse>> getContext(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final ZenDeskConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final String authHeader = "Basic " + new String(Try.of(() -> new Base64().encode(
                (parsedArgs.getUser() + "/token:" + parsedArgs.getToken()).getBytes(UTF_8))).get(), UTF_8);

        final List<String> query = new ArrayList<>();
        query.add("type:ticket");
        query.add("created>" + parsedArgs.getStartDate());

        final Try<List<RagDocumentContext<ZenDeskResultsResponse>>> result = Try.withResources(ClientBuilder::newClient)
                .of(client -> Try.of(() -> zenDeskClient.getTickets(client, authHeader, parsedArgs.getUrl(), String.join(" ", query), parsedArgs.getSearchTTL()))
                        // Filter out any tickets based on the submitter and assignee
                        .map(response -> filterResponse(response, parsedArgs.getOrganization(), true, parsedArgs.getExcludedSubmitters(), parsedArgs.getExcludedOrganization(), parsedArgs.getRecipient()))
                        // Limit how many tickets we process. We're unlikely to be able to pass the details of many tickets to the LLM anyway
                        .map(response -> response.subList(0, Math.min(response.size(), MAX_TICKETS)))
                        // Get the ticket comments (i.e. the initial email)
                        .map(response -> ticketToComments(response, client, authHeader, parsedArgs.getNumComments(), parsedArgs))
                        .map(tickets -> trimTickets(tickets, parsedArgs))
                        /*
                            Take the raw ticket comments and summarize them with individual calls to the LLM.
                            The individual ticket summaries are then combined into a single context.
                            This was necessary because the private LLMs didn't do a very good job of summarising
                            raw tickets. The reality is that even LLMs with a context length of 128k tokens mostly fixated
                            one a small number of tickets.
                         */
                        .map(tickets -> summariseTickets(tickets, environmentSettings))
                        .get());

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(
                        API.Case(API.$(instanceOf(EmptyString.class)), throwable -> new InternalFailure("The ZenDesk ticket is empty", throwable)),
                        API.Case(API.$(instanceOf(InternalFailure.class)), throwable -> throwable),
                        API.Case(API.$(instanceOf(FailedOllama.class)), throwable -> new InternalFailure(throwable.getMessage(), throwable)),
                        API.Case(API.$(), ex -> new ExternalFailure(getName() + " failed to call ZenDesk API", ex)))
                .get();

    }

    @Override
    public List<MetaObjectResult> getMetadata(Map<String, String> environmentSettings, String prompt, List<ToolArgs> arguments) {
        return List.of();
    }

    @Override
    public RagMultiDocumentContext<ZenDeskResultsResponse> call(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {

        final ZenDeskConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final String debugArgs = debugToolArgs.debugArgs(arguments);

        final Try<RagMultiDocumentContext<ZenDeskResultsResponse>> result = Try.of(() -> getContext(environmentSettings, prompt, arguments))
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
                        API.Case(API.$(instanceOf(EmptyString.class)),
                                throwable -> new InternalFailure("No tickets found after " + parsedArgs.getStartDate() + " for organization '" + parsedArgs.getOrganization() + "'" + debugArgs)),
                        API.Case(API.$(),
                                throwable -> new ExternalFailure("Failed to get tickets or context: " + throwable.toString() + " " + throwable.getMessage() + debugArgs)))
                .get();
    }

    /**
     * Display a Markdown list of the ticket IDs with links to the tickets. This helps users understand
     * where the information is coming from.
     *
     * @param url  The ZenDesk url
     * @param meta The ticket metadata
     * @return A Markdown link to the source ticket
     */
    private String ticketToLink(final String url, final ZenDeskResultsResponse meta, final String authHeader) {
        return Try.withResources(ClientBuilder::newClient)
                .of(client -> meta.subject().replaceAll("\\r\\n|\\r|\\n", " ") + " - "
                        // Best effort to get the organization name, but don't treat this as a failure
                        + Try.of(() -> zenDeskClient.getOrganizationCached(client, authHeader, url, meta.organization_id()))
                        .map(ZenDeskOrganizationItemResponse::name)
                        .getOrElse("Unknown Organization")
                        + " - "
                        // Best effort to get the username, but don't treat this as a failure
                        + Try.of(() -> zenDeskClient.getUserCached(client, authHeader, url, meta.assignee_id()))
                        .map(ZenDeskUserItemResponse::name)
                        .getOrElse("Unknown User")
                        + " [" + meta.id() + "](" + idToLink(url, meta.id()) + ")")
                .get();
    }

    @Nullable
    private String getOrganizationName(final ZenDeskResultsResponse meta, final String authHeader, final String url) {
        return Try.withResources(ClientBuilder::newClient)
                .of(client -> Try
                        .of(() -> zenDeskClient.getOrganizationCached(client, authHeader, url, meta.organization_id()))
                        .map(ZenDeskOrganizationItemResponse::name)
                        .get())
                // Do a best effort here - we don't want to fail the whole process because we can't get the organization name
                .getOrNull();
    }

    private String idToLink(final String url, final String id) {
        return url + "/agent/tickets/" + id;
    }

    private RagMultiDocumentContext<ZenDeskResultsResponse> mergeContext(final List<RagDocumentContext<ZenDeskResultsResponse>> context, final String debug, final String customModel) {
        return new RagMultiDocumentContext<>(
                context.stream()
                        .map(RagDocumentContext::document)
                        .map(content -> promptBuilderSelector.getPromptBuilder(customModel).buildContextPrompt("ZenDesk Ticket", content))
                        .collect(Collectors.joining("\n")),
                context,
                debug);
    }


    private List<ZenDeskResultsResponse> filterResponse(
            final List<ZenDeskResultsResponse> tickets,
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
     * Summarise the tickets by passing them through the LLM
     */
    private List<RagDocumentContext<ZenDeskResultsResponse>> summariseTickets(final List<RagDocumentContext<ZenDeskResultsResponse>> tickets, final Map<String, String> context) {
        return tickets.stream()
                .map(ticket -> ticket.updateDocument(getTicketSummary(ticket.document(), context)))
                .collect(Collectors.toList());
    }

    private List<RagDocumentContext<ZenDeskResultsResponse>> trimTickets(final List<RagDocumentContext<ZenDeskResultsResponse>> tickets, final ZenDeskConfig.LocalArguments parsedArgs) {
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
     * Summarise an individual ticket
     */
    private String getTicketSummary(final String ticketContents, final Map<String, String> environmentSettings) {
        final String ticketContext = promptBuilderSelector
                .getPromptBuilder(modelConfig.getCalculatedModel(environmentSettings))
                .buildContextPrompt("ZenDesk Ticket", ticketContents);

        final String prompt = promptBuilderSelector
                .getPromptBuilder(modelConfig.getCalculatedModel(environmentSettings))
                .buildFinalPrompt("You are a helpful agent", ticketContext, "Summarise the ticket in one paragraph");

        return ollamaClient.callOllamaWithCache(
                new RagMultiDocumentContext<>(prompt),
                modelConfig.getCalculatedModel(environmentSettings),
                getName(),
                modelConfig.getCalculatedContextWindow(environmentSettings)
        ).combinedDocument();
    }

    private List<RagDocumentContext<ZenDeskResultsResponse>> ticketToComments(final List<ZenDeskResultsResponse> tickets,
                                                                              final Client client,
                                                                              final String authorization,
                                                                              final int numComments,
                                                                              final ZenDeskConfig.LocalArguments parsedArgs) {
        return tickets.stream()
                // Get the context associated with the ticket
                .map(ticket -> new IndividualContext<>(
                        ticket.id(),
                        ticketToBody(zenDeskClient.getComments(client, authorization, parsedArgs.getZenDeskUrl(), ticket.id()), numComments),
                        ticket))
                // Get the comment body as a LLM context string
                .map(comments -> comments.updateContext(
                        comments.meta().subject() + "\n" + String.join("\n", comments.context())))
                // Get the LLM context string as a RAG context, complete with vectorized sentences
                .map(comments -> getDocumentContext(comments.context(), comments.id(), comments.meta(), authorization, parsedArgs))
                // Get a list of context strings
                .collect(Collectors.toList());
    }

    private RagDocumentContext<ZenDeskResultsResponse> getDocumentContext(final String document, final String id, final ZenDeskResultsResponse meta, final String authHeader, final ZenDeskConfig.LocalArguments parsedArgs) {
        final String contextLabel = String.join(
                " ",
                Stream.of(getContextLabel(), getOrganizationName(meta, authHeader, parsedArgs.getZenDeskUrl()))
                        .filter(StringUtils::isNotBlank)
                        .toList());

        if (parsedArgs.getDisableLinks()) {
            return new RagDocumentContext<>(contextLabel, document, List.of());
        }

        return Try.of(() -> sentenceSplitter.splitDocument(document, 10))
                .map(sentences -> new RagDocumentContext<>(
                        contextLabel,
                        document,
                        sentences.stream()
                                .map(sentence -> sentenceVectorizer.vectorize(sentence, parsedArgs.getEntity()))
                                .collect(Collectors.toList()),
                        id,
                        meta,
                        ticketToLink(parsedArgs.getZenDeskUrl(), meta, authHeader)))
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                .get();
    }

    private List<String> ticketToBody(final ZenDeskCommentsResponse comments, final int limit) {
        return comments
                .getResults()
                .stream()
                .limit(limit)
                .map(ZenDeskCommentResponse::body)
                .map(body -> Arrays.stream(body.split("\\r\\n|\\r|\\n"))
                        .filter(StringUtils::isNotBlank)
                        .collect(Collectors.joining("\n")))
                .collect(Collectors.toList());
    }
}

@ApplicationScoped
class ZenDeskConfig {
    private static final int MAX_TICKETS = 100;
    private static final String DEFAULT_TTL = (1000 * 60 * 60 * 24) + "";

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

    public class LocalArguments {
        private final List<ToolArgs> arguments;

        private final String prompt;

        private final Map<String, String> context;

        public LocalArguments(final List<ToolArgs> arguments, final String prompt, final Map<String, String> context) {
            this.arguments = arguments;
            this.prompt = prompt;
            this.context = context;
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
            // Organization is just a name or number. If organization is an email address, it was mixed up for the receipt.
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

        public int getRawHours() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigZenDeskHours()::get,
                    arguments,
                    context,
                    ZenDeskOrganization.HOURS_ARG,
                    "zendesk_hours",
                    "0").value();

            return Try.of(() -> Integer.parseInt(stringValue))
                    .recover(throwable -> 0)
                    .map(i -> Math.max(0, i))
                    .get();
        }

        public int getRawDays() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigZenDeskDays()::get,
                    arguments,
                    context,
                    ZenDeskOrganization.DAYS_ARG,
                    "zendesk_days",
                    "0").value();

            return Try.of(() -> Integer.parseInt(stringValue))
                    .recover(throwable -> 0)
                    .map(i -> Math.max(0, i))
                    .get();
        }

        public int getHours() {
            return switchArguments(prompt, getRawHours(), getRawDays(), "hour", "day");
        }

        public int getDays() {
            return switchArguments(prompt, getRawDays(), getRawHours(), "day", "hour");
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
    }
}
