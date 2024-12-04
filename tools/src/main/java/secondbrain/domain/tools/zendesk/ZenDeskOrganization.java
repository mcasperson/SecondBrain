package secondbrain.domain.tools.zendesk;

import io.smallrye.common.annotation.Identifier;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.validator.routines.EmailValidator;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.*;
import secondbrain.domain.debug.DebugToolArgs;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.limit.ListLimiter;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.sanitize.SanitizeDocument;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateInputs;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.ollama.OllamaGenerateBody;
import secondbrain.infrastructure.ollama.OllamaGenerateBodyWithContext;
import secondbrain.infrastructure.zendesk.*;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;

@ApplicationScoped
public class ZenDeskOrganization implements Tool {
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
    @ConfigProperty(name = "sb.zendesk.accesstoken")
    Optional<String> zenDeskAccessToken;

    @Inject
    @ConfigProperty(name = "sb.zendesk.user")
    Optional<String> zenDeskUser;

    @Inject
    @ConfigProperty(name = "sb.zendesk.url")
    Optional<String> zenDeskUrl;

    @Inject
    @ConfigProperty(name = "sb.zendesk.excludedorgs")
    Optional<String> zenExcludedOrgs;

    @Inject
    @ConfigProperty(name = "sb.annotation.minsimilarity", defaultValue = "0.5")
    String minSimilarity;

    @Inject
    @ConfigProperty(name = "sb.ollama.model", defaultValue = "llama3.2")
    String model;

    @Inject
    @ConfigProperty(name = "sb.ollama.contentlength", defaultValue = "" + Constants.MAX_CONTEXT_LENGTH)
    String limit;

    @Inject
    private Encryptor textEncryptor;

    @Inject
    @Identifier("removeSpacing")
    private SanitizeDocument removeSpacing;

    @Inject
    @Identifier("sanitizeEmail")
    private SanitizeDocument sanitizeEmail;

    @Inject
    @Identifier("sanitizeOrganization")
    private SanitizeDocument sanitizeOrganization;

    @Inject
    private ValidateString validateString;

    @Inject
    private OllamaClient ollamaClient;

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private ZenDeskClient zenDeskClient;

    @Inject
    private ListLimiter listLimiter;

    @Inject
    private DebugToolArgs debugToolArgs;

    @Inject
    private ValidateInputs validateInputs;

    @Inject
    private SentenceSplitter sentenceSplitter;

    @Inject
    private SentenceVectorizer sentenceVectorizer;

    @Inject
    private SimilarityCalculator similarityCalculator;

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
        return List.of(new ToolArguments("organization", "An optional name of the organization", ""),
                new ToolArguments("excludeOrganization", "An optional comma separated list of organizations to exclude", ""),
                new ToolArguments("excludeSubmitters", "An optional comma separated list of submitters to exclude", ""),
                new ToolArguments("recipient", "An optional recipient email address that tickets must be sent to", ""),
                new ToolArguments("numComments", "The optional number of comments to include in the context", "1"),
                new ToolArguments("days", "The optional number of days worth of tickets to return", "0"),
                new ToolArguments("hours", "The optional number of hours worth of tickets to return", "0"));
    }

    @Override
    public String call(final Map<String, String> context, final String prompt, final List<ToolArgs> arguments) {
        final String owner = validateInputs.getCommaSeparatedList(
                prompt,
                argsAccessor.getArgument(arguments, "organization", ""));

        final List<String> excludedOwner = Arrays.stream(validateInputs.getCommaSeparatedList(
                        prompt,
                        argsAccessor.getArgument(arguments, "excludeOrganization", "")).split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());

        excludedOwner.addAll(Arrays.stream(zenExcludedOrgs.orElse("").split(",")).toList());

        final String recipient = argsAccessor.getArgument(arguments, "recipient", "");

        // These arguments get swapped by the LLM all the time, so we need to fix them
        final String fixedRecipient = sanitizeEmail.sanitize(EmailValidator.getInstance().isValid(sanitizeEmail.sanitize(owner)) && StringUtils.isBlank(recipient) ? owner : recipient);
        final String fixedOwner = sanitizeOrganization.sanitize(EmailValidator.getInstance().isValid(sanitizeEmail.sanitize(owner)) && StringUtils.isBlank(recipient) ? "" : owner);

        final List<String> exclude = Arrays.stream(argsAccessor.getArgument(arguments, "excludeSubmitters", "").split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());

        final int hours = Try.of(() -> Integer.parseInt(argsAccessor.getArgument(arguments, "hours", "0")))
                .recover(throwable -> 0)
                .map(i -> Math.max(0, i))
                .get();

        final int days = Try.of(() -> Integer.parseInt(argsAccessor.getArgument(arguments, "days", "0")))
                .recover(throwable -> 0)
                .map(i -> Math.max(0, i))
                .get();

        final int numComments = Try.of(() -> Integer.parseInt(argsAccessor.getArgument(arguments, "numComments", "" + MAX_TICKETS)))
                .recover(throwable -> MAX_TICKETS)
                // Must be at least 1
                .map(i -> Math.max(1, i))
                .get();

        // days and hours get mixed up all the time.
        final int fixedDays = switchArguments(prompt, days, hours, "day", "hour");
        final int fixedHours = switchArguments(prompt, hours, days, "hour", "day");

        final float parsedMinSimilarity = Try.of(() -> Float.parseFloat(minSimilarity))
                .recover(throwable -> 0.5f)
                .get();

        // Try to decrypt the value, otherwise assume it is a plain text value, and finally
        // fall back to the value defined in the local configuration.
        final Try<String> token = getContext("zendesk_access_token", context)
                .recoverWith(e -> Try.of(() -> zenDeskAccessToken.get()));

        if (token.isFailure() || StringUtils.isBlank(token.get())) {
            return "Failed to get Zendesk access token";
        }

        final Try<String> url = getContext("zendesk_url", context)
                .recoverWith(e -> Try.of(() -> zenDeskUrl.get()));

        if (url.isFailure() || StringUtils.isBlank(url.get())) {
            return "Failed to get Zendesk URL";
        }

        final Try<String> user = getContext("zendesk_user", context)
                .recoverWith(e -> Try.of(() -> zenDeskUser.get()));

        if (user.isFailure() || StringUtils.isBlank(user.get())) {
            return "Failed to get Zendesk User";
        }

        final String authHeader = "Basic " + new String(Try.of(() -> new Base64().encode(
                (user.get() + "/token:" + token.get()).getBytes(UTF_8))).get(), UTF_8);

        final String startDate = OffsetDateTime.now(ZoneId.systemDefault())
                .truncatedTo(ChronoUnit.SECONDS)
                // Assume one day if nothing was specified
                .minusDays(fixedDays + fixedHours == 0 ? 1 : fixedDays)
                .minusHours(fixedHours)
                .format(ISO_OFFSET_DATE_TIME);

        final List<String> query = new ArrayList<>();
        query.add("type:ticket");
        query.add("created>" + startDate);

        if (!StringUtils.isBlank(fixedOwner)) {
            query.add("organization:" + fixedOwner);
        }

        final String debugArgs = debugToolArgs.debugArgs(arguments, true);

        return Try.withResources(ClientBuilder::newClient)
                .of(client -> Try.of(() -> zenDeskClient.getTickets(client, authHeader, url.get(), String.join(" ", query)))

                        // Filter out any tickets based on the submitter and assignee
                        .map(response -> filterResponse(response, true, exclude, excludedOwner, fixedRecipient))
                        // Limit how many tickets we process. We're unlikely to be able to pass the details of many tickets to the LLM anyway
                        .map(response -> response.subList(0, Math.min(response.size(), MAX_TICKETS)))
                        // Get the ticket comments (i.e. the initial email)
                        .map(response -> ticketToComments(response, client, authHeader, numComments))
                        /*
                            Take the raw ticket comments and summarize them with individual calls to the LLM.
                            The individual ticket summaries are then combined into a single context.
                            This was necessary because the private LLMs didn't do a very good job of summarising
                            raw tickets. The reality is that even LLMs with a context length of 128k tokens mostly fixated
                            one a small number of tickets.
                         */
                        .map(this::summariseTickets)
                        // Limit the list to just those that fit in the context
                        .map(list -> listLimiter.limitListContent(
                                list,
                                RagDocumentContext::document,
                                NumberUtils.toInt(limit, Constants.MAX_CONTEXT_LENGTH)))
                        // Combine the individual zen desk tickets into a parent RagMultiDocumentContext
                        .map(this::mergeContext)
                        // Make sure we had some content for the prompt
                        .mapTry(mergedContext ->
                                validateString.throwIfEmpty(mergedContext, RagMultiDocumentContext::combinedDocument))
                        // Build the final prompt including instructions, context and the user prompt
                        .map(ragContext -> ragContext.updateDocument(
                                promptBuilderSelector
                                        .getPromptBuilder(model)
                                        .buildFinalPrompt(INSTRUCTIONS, ragContext.combinedDocument(), prompt)))
                        // Call Ollama with the final prompt
                        .map(llmPrompt -> ollamaClient.getTools(
                                client,
                                new OllamaGenerateBodyWithContext<>(model, llmPrompt, false)))
                        // Clean up the response
                        .map(response -> response.updateDocument(removeSpacing.sanitize(response.combinedDocument())))
                        // Take the LLM response and annotate it with links to the RAG context
                        .map(response -> response.annotateDocumentContext(
                                parsedMinSimilarity,
                                10,
                                sentenceSplitter,
                                similarityCalculator,
                                sentenceVectorizer)
                                + System.lineSeparator() + System.lineSeparator()
                                + "Tickets:" + System.lineSeparator()
                                + idsToLinks(url.get(), response.getMetas(), authHeader)
                                + debugArgs)
                        .recover(EmptyString.class, "No tickets found after " + startDate + " for organization '" + fixedOwner + "'" + debugArgs)
                        .recover(throwable -> "Failed to get tickets or context: " + throwable.toString() + " " + throwable.getMessage() + debugArgs)
                        .get())
                .get();
    }

    /**
     * Display a Markdown list of the ticket IDs with links to the tickets. This helps users understand
     * where the information is coming from.
     *
     * @param url   The ZenDesk url
     * @param metas The list of ticket metadata
     * @return A Markdown list of source tickets
     */
    private String idsToLinks(final String url, final List<ZenDeskResultsResponse> metas, final String authHeader) {
        return Try.withResources(ClientBuilder::newClient)
                .of(client -> metas.stream()
                        .map(meta ->
                                "* " + meta.subject().replaceAll("\\r\\n|\\r|\\n", " ") + " - "
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
                        .collect(Collectors.joining("\n")))
                .get();
    }

    private String idToLink(final String url, final String id) {
        return url + "/agent/tickets/" + id;
    }

    private RagMultiDocumentContext<ZenDeskResultsResponse> mergeContext(final List<RagDocumentContext<ZenDeskResultsResponse>> context) {
        return new RagMultiDocumentContext<>(
                context.stream()
                        .map(RagDocumentContext::document)
                        .map(content -> promptBuilderSelector.getPromptBuilder(model).buildContextPrompt("ZenDesk Ticket", content))
                        .collect(Collectors.joining("\n")),
                context);
    }


    private Try<String> getContext(final String name, final Map<String, String> context) {
        return Try.of(() -> textEncryptor.decrypt(context.get(name)))
                .recover(e -> context.get(name))
                .mapTry(Objects::requireNonNull);
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
    private List<RagDocumentContext<ZenDeskResultsResponse>> summariseTickets(final List<RagDocumentContext<ZenDeskResultsResponse>> tickets) {
        return tickets.stream()
                .map(ticket -> ticket.updateDocument(getTicketSummary(ticket.document())))
                .collect(Collectors.toList());
    }

    /**
     * Summarise an individual ticket
     */
    private String getTicketSummary(final String ticketContents) {
        final String context = promptBuilderSelector
                .getPromptBuilder(model)
                .buildContextPrompt("ZenDesk Ticket", ticketContents);

        final String prompt = promptBuilderSelector
                .getPromptBuilder(model)
                .buildFinalPrompt("You are a helpful agent", context, "Summarise the ticket in one paragraph");

        return Try.withResources(ClientBuilder::newClient)
                .of(client -> ollamaClient.getTools(
                                client,
                                new OllamaGenerateBody(
                                        model,
                                        prompt,
                                        false))
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
                        ticketToBody(zenDeskClient.getComments(client, authorization, zenDeskUrl.get(), ticket.id()), numComments),
                        ticket))
                // Get the comment body as a LLM context string
                .map(comments -> comments.updateContext(
                        comments.meta().subject() + "\n" + String.join("\n", comments.context())))
                // Get the LLM context string as a RAG context, complete with vectorized sentences
                .map(comments -> getDocumentContext(comments.context(), comments.id(), comments.meta()))
                // Get a list of context strings
                .collect(Collectors.toList());
    }

    private RagDocumentContext<ZenDeskResultsResponse> getDocumentContext(final String document, final String id, final ZenDeskResultsResponse meta) {
        return Try.of(() -> sentenceSplitter.splitDocument(document, 10))
                .map(sentences -> new RagDocumentContext<ZenDeskResultsResponse>(
                        document,
                        sentences.stream()
                                .map(sentenceVectorizer::vectorize)
                                .collect(Collectors.toList()),
                        id,
                        meta))
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                // If we can't vectorize the sentences, just return the document
                .recover(e -> new RagDocumentContext<>(document, List.of(), id, meta))
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

    private int switchArguments(final String prompt, final int a, final int b, final String aPromptKeyword, final String bPromptKeyword) {
        // If the prompt did not mention the keyword for the first argument, assume that it was never mentioned, and return 0
        if (!prompt.contains(aPromptKeyword)) {
            return 0;
        }

        // If the prompt did mention the first argument, but did not mention the keyword for the second argument,
        // and the first argument is 0, assume the LLM switched things up, and return the second argument
        if (!prompt.contains(bPromptKeyword) && a == 0) {
            return b;
        }

        // If both the first and second keywords were mentioned, we just have to trust the LLM
        return a;
    }
}
