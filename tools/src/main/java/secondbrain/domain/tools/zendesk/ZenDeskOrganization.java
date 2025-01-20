package secondbrain.domain.tools.zendesk;

import io.smallrye.common.annotation.Identifier;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.validator.routines.EmailValidator;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.config.ModelConfig;
import secondbrain.domain.context.*;
import secondbrain.domain.debug.DebugToolArgs;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.exceptions.FailedTool;
import secondbrain.domain.limit.ListLimiter;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.sanitize.SanitizeArgument;
import secondbrain.domain.sanitize.SanitizeDocument;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateInputs;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.ollama.OllamaGenerateBody;
import secondbrain.infrastructure.ollama.OllamaGenerateBodyOptions;
import secondbrain.infrastructure.zendesk.*;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static com.google.common.base.Predicates.instanceOf;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;

@ApplicationScoped
public class ZenDeskOrganization implements Tool<ZenDeskResultsResponse> {
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
    private Arguments parsedArgs;

    @Inject
    @Identifier("removeSpacing")
    private SanitizeDocument removeSpacing;

    @Inject
    private ValidateString validateString;

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
        return List.of(new ToolArguments("zenDeskOrganization", "An optional name of the organization", ""),
                new ToolArguments("excludeOrganization", "An optional comma separated list of organizations to exclude", ""),
                new ToolArguments("excludeSubmitters", "An optional comma separated list of submitters to exclude", ""),
                new ToolArguments("recipient", "An optional recipient email address that tickets must be sent to", ""),
                new ToolArguments("numComments", "The optional number of comments to include in the context", "1"),
                new ToolArguments("days", "The optional number of days worth of tickets to return", "0"),
                new ToolArguments("hours", "The optional number of hours worth of tickets to return", "0"));
    }

    public String getContextLabel() {
        return "ZenDesk Ticket";
    }

    @Override
    public List<RagDocumentContext<ZenDeskResultsResponse>> getContext(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        parsedArgs.setInputs(arguments, prompt, context);

        final String authHeader = "Basic " + new String(Try.of(() -> new Base64().encode(
                (parsedArgs.getUser() + "/token:" + parsedArgs.getToken()).getBytes(UTF_8))).get(), UTF_8);

        final List<String> query = new ArrayList<>();
        query.add("type:ticket");
        query.add("created>" + parsedArgs.getStartDate());

        if (!StringUtils.isBlank(parsedArgs.getOrganization())) {
            query.add("organization:" + parsedArgs.getOrganization());
        }

        return Try.withResources(ClientBuilder::newClient)
                .of(client -> Try.of(() -> zenDeskClient.getTickets(client, authHeader, parsedArgs.getUrl(), String.join(" ", query)))
                        // Filter out any tickets based on the submitter and assignee
                        .map(response -> filterResponse(response, true, parsedArgs.getExcludedSubmitters(), parsedArgs.getExcludedOrganization(), parsedArgs.getRecipient()))
                        // Limit how many tickets we process. We're unlikely to be able to pass the details of many tickets to the LLM anyway
                        .map(response -> response.subList(0, Math.min(response.size(), MAX_TICKETS)))
                        // Get the ticket comments (i.e. the initial email)
                        .map(response -> ticketToComments(response, client, authHeader, parsedArgs.getNumComments()))
                        /*
                            Take the raw ticket comments and summarize them with individual calls to the LLM.
                            The individual ticket summaries are then combined into a single context.
                            This was necessary because the private LLMs didn't do a very good job of summarising
                            raw tickets. The reality is that even LLMs with a context length of 128k tokens mostly fixated
                            one a small number of tickets.
                         */
                        .map(tickets -> summariseTickets(tickets, context))
                        .get())
                .get();

    }

    @Override
    public RagMultiDocumentContext<ZenDeskResultsResponse> call(final Map<String, String> context, final String prompt, final List<ToolArgs> arguments) {

        parsedArgs.setInputs(arguments, prompt, context);

        final String debugArgs = debugToolArgs.debugArgs(arguments);

        final Try<RagMultiDocumentContext<ZenDeskResultsResponse>> result = Try.of(() -> getContext(context, prompt, arguments))
                // Limit the list to just those that fit in the context
                .map(list -> listLimiter.limitListContent(
                        list,
                        RagDocumentContext::document,
                        modelConfig.getCalculatedContextWindowChars()))
                // Combine the individual zen desk tickets into a parent RagMultiDocumentContext
                .map(tickets -> mergeContext(tickets, debugArgs, modelConfig.getCalculatedModel(context)))
                // Make sure we had some content for the prompt
                .mapTry(mergedContext ->
                        validateString.throwIfEmpty(mergedContext, RagMultiDocumentContext::combinedDocument))
                // Build the final prompt including instructions, context and the user prompt
                .map(ragContext -> ragContext.updateDocument(
                        promptBuilderSelector
                                .getPromptBuilder(modelConfig.getCalculatedModel(context))
                                .buildFinalPrompt(INSTRUCTIONS, ragContext.combinedDocument(), prompt)))
                // Call Ollama with the final prompt
                .map(ragDoc -> ollamaClient.callOllama(
                        ragDoc,
                        modelConfig.getCalculatedModel(context),
                        modelConfig.getCalculatedContextWindow()))
                // Clean up the response
                .map(response -> response.updateDocument(removeSpacing.sanitize(response.combinedDocument())))
                .recover(EmptyString.class, e -> new RagMultiDocumentContext<>("No tickets found after " + parsedArgs.getStartDate() + " for organization '" + parsedArgs.getOrganization() + "'", List.of(), ""));

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(
                        API.Case(API.$(instanceOf(EmptyString.class)),
                                throwable -> new FailedTool("No tickets found after " + parsedArgs.getStartDate() + " for organization '" + parsedArgs.getOrganization() + "'" + debugArgs)),
                        API.Case(API.$(),
                                throwable -> new FailedTool("Failed to get tickets or context: " + throwable.toString() + " " + throwable.getMessage() + debugArgs)))
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

    /**
     * Summarise an individual ticket
     */
    private String getTicketSummary(final String ticketContents, final Map<String, String> context) {
        final String ticketContext = promptBuilderSelector
                .getPromptBuilder(modelConfig.getCalculatedModel(context))
                .buildContextPrompt("ZenDesk Ticket", ticketContents);

        final String prompt = promptBuilderSelector
                .getPromptBuilder(modelConfig.getCalculatedModel(context))
                .buildFinalPrompt("You are a helpful agent", ticketContext, "Summarise the ticket in one paragraph");

        return Try.withResources(ClientBuilder::newClient)
                .of(client -> ollamaClient.callOllama(
                                client,
                                new OllamaGenerateBody(
                                        modelConfig.getCalculatedModel(context),
                                        prompt,
                                        false,
                                        new OllamaGenerateBodyOptions(modelConfig.getCalculatedContextWindow())))
                        .response())
                .get();
    }

    private List<RagDocumentContext<ZenDeskResultsResponse>> ticketToComments(final List<ZenDeskResultsResponse> tickets,
                                                                              final Client client,
                                                                              final String authorization,
                                                                              final int numComments) {
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
                .map(comments -> getDocumentContext(comments.context(), comments.id(), comments.meta(), authorization))
                // Get a list of context strings
                .collect(Collectors.toList());
    }

    private RagDocumentContext<ZenDeskResultsResponse> getDocumentContext(final String document, final String id, final ZenDeskResultsResponse meta, final String authHeader) {
        return Try.of(() -> sentenceSplitter.splitDocument(document, 10))
                .map(sentences -> new RagDocumentContext<ZenDeskResultsResponse>(
                        getContextLabel(),
                        document,
                        sentences.stream()
                                .map(sentenceVectorizer::vectorize)
                                .collect(Collectors.toList()),
                        id,
                        meta,
                        null))
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                // If we can't vectorize the sentences, just return the document
                .recover(e -> new RagDocumentContext<>(
                        getContextLabel(),
                        document,
                        List.of(),
                        id,
                        meta,
                        null))
                // Add the links to each of the tickets
                .map(ragDocumentContext -> ragDocumentContext.updateLink(ticketToLink(parsedArgs.getZenDeskUrl(), meta, authHeader)))
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
class Arguments {
    private static final int MAX_TICKETS = 100;

    @Inject
    @ConfigProperty(name = "sb.zendesk.accesstoken")
    private Optional<String> zenDeskAccessToken;

    @Inject
    @ConfigProperty(name = "sb.zendesk.user")
    private Optional<String> zenDeskUser;

    @Inject
    @ConfigProperty(name = "sb.zendesk.url")
    private Optional<String> zenDeskUrl;

    @Inject
    @ConfigProperty(name = "sb.zendesk.excludedorgs")
    private Optional<String> zenExcludedOrgs;

    @Inject
    @ConfigProperty(name = "sb.zendesk.recipient")
    private Optional<String> zenDeskRecipient;

    @Inject
    @ConfigProperty(name = "sb.zendesk.organization")
    private Optional<String> zenDeskOrganization;

    @Inject
    @ConfigProperty(name = "sb.zendesk.days")
    private Optional<String> zenDeskDays;

    @Inject
    @ConfigProperty(name = "sb.zendesk.hours")
    private Optional<String> zenDeskHours;

    @Inject
    @ConfigProperty(name = "sb.zendesk.numcomments")
    private Optional<String> zenDeskNumComments;

    @Inject
    @ConfigProperty(name = "sb.zendesk.excludedsubmitters")
    private Optional<String> zenDeskExcludedSubmitters;

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

    private List<ToolArgs> arguments;

    private String prompt;

    private Map<String, String> context;

    public void setInputs(final List<ToolArgs> arguments, final String prompt, final Map<String, String> context) {
        this.arguments = arguments;
        this.prompt = prompt;
        this.context = context;
    }

    public String getZenDeskUrl() {
        return zenDeskUrl.get();
    }

    public String getRawOrganization() {
        final String stringValue = argsAccessor.getArgument(
                zenDeskOrganization::get,
                arguments,
                context,
                "zenDeskOrganization",
                "zendesk_organization",
                "");

        return validateInputs.getCommaSeparatedList(
                prompt,
                stringValue);
    }

    public String getOrganization() {
        // Organization is just a name or number. If organization is an email address, it was mixed up for the receipt.
        if (EmailValidator.getInstance().isValid(sanitizeEmail.sanitize(getRawOrganization(), prompt)) && StringUtils.isBlank(getRecipient())) {
            return "";
        }

        return sanitizeOrganization.sanitize(getRawOrganization(), prompt);
    }

    public List<String> getExcludedOrganization() {
        final String stringValue = argsAccessor.getArgument(
                zenExcludedOrgs::get,
                arguments,
                context,
                "excludeOrganization",
                "zendesk_excludeorganization",
                "");

        final List<String> excludedOrganization = Arrays.stream(validateInputs.getCommaSeparatedList(
                        prompt,
                        stringValue).split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());

        excludedOrganization.addAll(Arrays.stream(zenExcludedOrgs.orElse("").split(",")).toList());

        return excludedOrganization;
    }

    public String getRawRecipient() {
        return argsAccessor.getArgument(
                zenDeskRecipient::get,
                arguments,
                context,
                "recipient",
                "zendesk_recipient",
                "");
    }

    public String getRecipient() {
        // Organization is just a name or number. If organization is an email address, it was mixed up for the receipt.
        if (EmailValidator.getInstance().isValid(sanitizeEmail.sanitize(getRawOrganization(), prompt)) && StringUtils.isBlank(getRawRecipient())) {
            return sanitizeEmail.sanitize(getRawOrganization(), prompt);
        }

        return sanitizeEmail.sanitize(getRawRecipient(), prompt);
    }

    public List<String> getExcludedSubmitters() {
        final String stringValue = argsAccessor.getArgument(
                zenDeskExcludedSubmitters::get,
                arguments,
                context,
                "excludeSubmitters",
                "zendesk_excludesubmitters",
                "");

        return Arrays.stream(stringValue.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
    }

    public int getRawHours() {
        final String stringValue = argsAccessor.getArgument(
                zenDeskHours::get,
                arguments,
                context,
                "hours",
                "zendesk_hours",
                "0");

        return Try.of(() -> Integer.parseInt(stringValue))
                .recover(throwable -> 0)
                .map(i -> Math.max(0, i))
                .get();
    }

    public int getRawDays() {
        final String stringValue = argsAccessor.getArgument(
                zenDeskDays::get,
                arguments,
                context,
                "days",
                "zendesk_days",
                "0");

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
        final String stringValue = argsAccessor.getArgument(
                zenDeskNumComments::get,
                arguments,
                context,
                "numComments",
                "zendesk_numcomments",
                MAX_TICKETS + "");

        return Try.of(() -> Integer.parseInt(stringValue))
                .recover(throwable -> MAX_TICKETS)
                // Must be at least 1
                .map(i -> Math.max(1, i))
                .get();
    }

    public String getToken() {
        // Try to decrypt the value, otherwise assume it is a plain text value, and finally
        // fall back to the value defined in the local configuration.
        final Try<String> token = Try.of(() -> textEncryptor.decrypt(context.get("zendesk_access_token")))
                .recover(e -> context.get("zendesk_access_token"))
                .mapTry(validateString::throwIfEmpty)
                .recoverWith(e -> Try.of(() -> zenDeskAccessToken.get()));

        if (token.isFailure() || StringUtils.isBlank(token.get())) {
            throw new FailedTool("Failed to get Zendesk access token");
        }

        return token.get();
    }

    public String getUrl() {
        final Try<String> url = getContext("zendesk_url", context, textEncryptor)
                .recoverWith(e -> Try.of(() -> zenDeskUrl.get()));

        if (url.isFailure() || StringUtils.isBlank(url.get())) {
            throw new FailedTool("Failed to get Zendesk URL");
        }

        return url.get();
    }

    public String getUser() {
        final Try<String> user = Try.of(() -> textEncryptor.decrypt(context.get("zendesk_user")))
                .recover(e -> context.get("zendesk_user"))
                .mapTry(validateString::throwIfEmpty)
                .recoverWith(e -> Try.of(() -> zenDeskUser.get()));

        if (user.isFailure() || StringUtils.isBlank(user.get())) {
            throw new FailedTool("Failed to get Zendesk User");
        }

        return user.get();
    }

    public String getStartDate() {
        return OffsetDateTime.now(ZoneId.systemDefault())
                .truncatedTo(ChronoUnit.SECONDS)
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
        // If the prompt did not mention the keyword for the first argument, assume that it was never mentioned, and return 0
        if (!prompt.toLowerCase().contains(aPromptKeyword.toLowerCase())) {
            return 0;
        }

        // If the prompt did mention the first argument, but did not mention the keyword for the second argument,
        // and the first argument is 0, assume the LLM switched things up, and return the second argument
        if (!prompt.toLowerCase().contains(bPromptKeyword.toLowerCase()) && a == 0) {
            return b;
        }

        // If both the first and second keywords were mentioned, we just have to trust the LLM
        return a;
    }
}
