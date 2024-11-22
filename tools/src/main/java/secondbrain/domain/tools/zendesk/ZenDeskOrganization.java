package secondbrain.domain.tools.zendesk;

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
import org.jasypt.util.text.BasicTextEncryptor;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.*;
import secondbrain.domain.debug.DebugToolArgs;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.limit.ListLimiter;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateInputs;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.ollama.OllamaClient;
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
    private static final int DEFAULT_DURATION = 30;
    private static final int MAX_TICKETS = 25;

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
    @ConfigProperty(name = "sb.encryption.password", defaultValue = "12345678")
    String encryptionPassword;

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
        return List.of(new ToolArguments("organization", "The name of the ZenDesk organization", ""),
                new ToolArguments("excludeSubmitters", "A comma separated list of submitters to exclude", ""),
                new ToolArguments("recipient", "The recipient email address that tickets must be sent to", ""),
                new ToolArguments("days", "The number of days worth of tickets to return", DEFAULT_DURATION + ""));
    }

    @Override
    public String call(final Map<String, String> context, final String prompt, final List<ToolArgs> arguments) {
        final String owner = validateInputs.getCommaSeparatedList(
                prompt,
                argsAccessor.getArgument(arguments, "organization", ""));

        final String recipient = argsAccessor.getArgument(arguments, "recipient", "");

        // These arguments get swapped by the LLM all the time, so we need to fix them
        final String fixedRecipient = sanitizeRecipient(EmailValidator.getInstance().isValid(sanitizeRecipient(owner)) && StringUtils.isBlank(recipient) ? owner : recipient);
        final String fixedOwner = sanitizeOwner(EmailValidator.getInstance().isValid(sanitizeRecipient(owner)) && StringUtils.isBlank(recipient) ? "" : owner);

        final List<String> exclude = Arrays.stream(argsAccessor.getArgument(arguments, "excludeSubmitters", "").split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());

        final int days = Try.of(() -> Integer.parseInt(argsAccessor.getArgument(arguments, "days", "30")))
                .recover(throwable -> DEFAULT_DURATION)
                .get();

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

        final List<String> query = new ArrayList<>();
        query.add("type:ticket");
        query.add("created>" + OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.SECONDS).minusDays(days).format(ISO_OFFSET_DATE_TIME));

        if (!StringUtils.isBlank(fixedOwner)) {
            query.add("organization:" + fixedOwner);
        }

        return Try.withResources(ClientBuilder::newClient)
                .of(client -> Try.of(() -> zenDeskClient.getTickets(client, authHeader, url.get(), String.join(" ", query)))

                        // Filter out any tickets based on the submitter and assignee
                        .map(response -> filterResponse(response, true, exclude, fixedRecipient))
                        // Limit how many tickets we process. We're unlikely to be able to pass the details of many tickets to the LLM anyway
                        .map(response -> response.subList(0, Math.min(response.size(), MAX_TICKETS)))
                        // Get the ticket comments (i.e. the initial email)
                        .map(response -> ticketToFirstComment(response, client, authHeader))
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
                                + debugToolArgs.debugArgs(arguments, true))
                        .recover(EmptyString.class, "No tickets found")
                        .recover(throwable -> "Failed to get tickets or context: " + throwable.getMessage())
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
                                "* [" + meta.id() + " - "
                                        + meta.subject() + " - "
                                        // Best effort to get the organization name, but don't treat this as a failure
                                        + Try.of(() -> zenDeskClient.getOrganizationCached(client, authHeader, url, meta.organization_id()))
                                        .map(ZenDeskOrganizationItemResponse::name)
                                        .getOrElse("Unknown Organization")
                                        + "](" + idToLink(url, meta.id()) + ")")
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
        final BasicTextEncryptor textEncryptor = new BasicTextEncryptor();
        textEncryptor.setPassword(encryptionPassword);

        return Try.of(() -> textEncryptor.decrypt(context.get(name)))
                .recover(e -> context.get(name))
                .mapTry(Objects::requireNonNull);
    }

    private List<ZenDeskResultsResponse> filterResponse(final List<ZenDeskResultsResponse> tickets, final boolean forceAssignee, final List<String> exclude, final String recipient) {
        if (!forceAssignee && exclude.isEmpty() && StringUtils.isBlank(recipient)) {
            return tickets;
        }

        return tickets.stream()
                .filter(ticket -> !exclude.contains(ticket.submitter_id()))
                .filter(ticket -> !forceAssignee || !StringUtils.isBlank(ticket.assignee_id()))
                .filter(ticket -> StringUtils.isBlank(recipient) || recipient.equals(ticket.recipient()))
                .collect(Collectors.toList());
    }


    private List<RagDocumentContext<ZenDeskResultsResponse>> ticketToFirstComment(final List<ZenDeskResultsResponse> tickets,
                                                                                  final Client client,
                                                                                  final String authorization) {
        return tickets.stream()
                // Get the context associated with the ticket
                .map(ticket -> new IndividualContext<>(
                        ticket.id(),
                        zenDeskClient.getComments(client, authorization, zenDeskUrl.get(), ticket.id()),
                        ticket))
                // Get the first comment, or an empty list
                .map(comments -> comments.updateContext(
                        ticketToBody(comments.context(), 1)))
                // Get the comment body as a LLM context string
                .map(comments -> comments.updateContext(
                        String.join("\n", comments.context())))
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

    private String sanitizeOwner(final String owner) {
        if (StringUtils.isBlank(owner)) {
            return "";
        }

        // A bunch of hallucinations that we need to ignore
        final String[] invalid = {
                "zendesk",
                "support",
                "helpdesk",
                "help desk",
                "help",
                "desk",
                "supportdesk",
                "support desk",
                "your organization name"};

        if (Arrays.stream(invalid).anyMatch(owner::equalsIgnoreCase)) {
            return "";
        }

        return owner;
    }

    private String sanitizeRecipient(final String recipient) {
        if (StringUtils.isBlank(recipient)) {
            return "";
        }

        // find the first thing that looks like an email address
        return Arrays.stream(recipient.split(" "))
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .filter(e -> EmailValidator.getInstance().isValid(e))
                .limit(1)
                .findFirst()
                .orElse("");
    }
}
