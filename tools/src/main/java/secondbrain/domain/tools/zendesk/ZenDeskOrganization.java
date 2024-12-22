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
import org.jspecify.annotations.Nullable;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.constants.Constants;
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
    @ConfigProperty(name = "sb.ollama.model", defaultValue = "llama3.2")
    private String model;

    @Inject
    @ConfigProperty(name = "sb.ollama.contextwindow")
    private Optional<String> contextWindow;

    @Inject
    private Encryptor textEncryptor;

    @Inject
    @Identifier("removeSpacing")
    private SanitizeDocument removeSpacing;

    @Inject
    @Identifier("sanitizeEmail")
    private SanitizeArgument sanitizeEmail;

    @Inject
    @Identifier("sanitizeOrganization")
    private SanitizeArgument sanitizeOrganization;

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

    @Override
    public List<RagDocumentContext<ZenDeskResultsResponse>> getContext(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        final Arguments parsedArgs = Arguments.fromToolArgs(
                arguments,
                argsAccessor,
                context,
                validateInputs,
                validateString,
                sanitizeOrganization,
                sanitizeEmail,
                textEncryptor,
                prompt,
                zenDeskAccessToken,
                zenDeskUrl,
                zenDeskUser,
                zenExcludedOrgs,
                model,
                contextWindow);

        final String authHeader = "Basic " + new String(Try.of(() -> new Base64().encode(
                (parsedArgs.user() + "/token:" + parsedArgs.token()).getBytes(UTF_8))).get(), UTF_8);

        final List<String> query = new ArrayList<>();
        query.add("type:ticket");
        query.add("created>" + parsedArgs.startDate());

        if (!StringUtils.isBlank(parsedArgs.organization())) {
            query.add("organization:" + parsedArgs.organization());
        }

        return Try.withResources(ClientBuilder::newClient)
                .of(client -> Try.of(() -> zenDeskClient.getTickets(client, authHeader, parsedArgs.url(), String.join(" ", query)))
                        // Filter out any tickets based on the submitter and assignee
                        .map(response -> filterResponse(response, true, parsedArgs.excludedSubmitters(), parsedArgs.excludedOrganization(), parsedArgs.recipient()))
                        // Limit how many tickets we process. We're unlikely to be able to pass the details of many tickets to the LLM anyway
                        .map(response -> response.subList(0, Math.min(response.size(), MAX_TICKETS)))
                        // Get the ticket comments (i.e. the initial email)
                        .map(response -> ticketToComments(response, client, authHeader, parsedArgs.numComments()))
                        /*
                            Take the raw ticket comments and summarize them with individual calls to the LLM.
                            The individual ticket summaries are then combined into a single context.
                            This was necessary because the private LLMs didn't do a very good job of summarising
                            raw tickets. The reality is that even LLMs with a context length of 128k tokens mostly fixated
                            one a small number of tickets.
                         */
                        .map(tickets -> summariseTickets(tickets, parsedArgs))
                        .get())
                .get();

    }

    @Override
    public RagMultiDocumentContext<ZenDeskResultsResponse> call(final Map<String, String> context, final String prompt, final List<ToolArgs> arguments) {

        final Arguments parsedArgs = Arguments.fromToolArgs(
                arguments,
                argsAccessor,
                context,
                validateInputs,
                validateString,
                sanitizeOrganization,
                sanitizeEmail,
                textEncryptor,
                prompt,
                zenDeskAccessToken,
                zenDeskUrl,
                zenDeskUser,
                zenExcludedOrgs,
                model,
                contextWindow);

        final String debugArgs = debugToolArgs.debugArgs(arguments);

        final Try<RagMultiDocumentContext<ZenDeskResultsResponse>> result = Try.of(() -> getContext(context, prompt, arguments))
                // Limit the list to just those that fit in the context
                .map(list -> listLimiter.limitListContent(
                        list,
                        RagDocumentContext::document,
                        parsedArgs.contextWindowChars()))
                // Combine the individual zen desk tickets into a parent RagMultiDocumentContext
                .map(tickets -> mergeContext(tickets, debugArgs, parsedArgs.customModel()))
                // Make sure we had some content for the prompt
                .mapTry(mergedContext ->
                        validateString.throwIfEmpty(mergedContext, RagMultiDocumentContext::combinedDocument))
                // Build the final prompt including instructions, context and the user prompt
                .map(ragContext -> ragContext.updateDocument(
                        promptBuilderSelector
                                .getPromptBuilder(parsedArgs.customModel())
                                .buildFinalPrompt(INSTRUCTIONS, ragContext.combinedDocument(), prompt)))
                // Call Ollama with the final prompt
                .map(ragDoc -> ollamaClient.callOllama(ragDoc, parsedArgs.customModel(), parsedArgs.contextWindow()))
                // Clean up the response
                .map(response -> response.updateDocument(removeSpacing.sanitize(response.combinedDocument())));

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(
                        API.Case(API.$(instanceOf(EmptyString.class)),
                                throwable -> new FailedTool("No tickets found after " + parsedArgs.startDate() + " for organization '" + parsedArgs.organization() + "'" + debugArgs)),
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
    private List<RagDocumentContext<ZenDeskResultsResponse>> summariseTickets(final List<RagDocumentContext<ZenDeskResultsResponse>> tickets, final Arguments parsedArgs) {
        return tickets.stream()
                .map(ticket -> ticket.updateDocument(getTicketSummary(ticket.document(), parsedArgs)))
                .collect(Collectors.toList());
    }

    /**
     * Summarise an individual ticket
     */
    private String getTicketSummary(final String ticketContents, final Arguments parsedArgs) {
        final String context = promptBuilderSelector
                .getPromptBuilder(parsedArgs.customModel())
                .buildContextPrompt("ZenDesk Ticket", ticketContents);

        final String prompt = promptBuilderSelector
                .getPromptBuilder(parsedArgs.customModel())
                .buildFinalPrompt("You are a helpful agent", context, "Summarise the ticket in one paragraph");

        return Try.withResources(ClientBuilder::newClient)
                .of(client -> ollamaClient.callOllama(
                                client,
                                new OllamaGenerateBody(
                                        parsedArgs.customModel(),
                                        prompt,
                                        false,
                                        new OllamaGenerateBodyOptions(parsedArgs.contextWindow())))
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
                .map(comments -> getDocumentContext(comments.context(), comments.id(), comments.meta(), authorization))
                // Get a list of context strings
                .collect(Collectors.toList());
    }

    private RagDocumentContext<ZenDeskResultsResponse> getDocumentContext(final String document, final String id, final ZenDeskResultsResponse meta, final String authHeader) {
        return Try.of(() -> sentenceSplitter.splitDocument(document, 10))
                .map(sentences -> new RagDocumentContext<ZenDeskResultsResponse>(
                        document,
                        sentences.stream()
                                .map(sentenceVectorizer::vectorize)
                                .collect(Collectors.toList()),
                        id,
                        meta,
                        null))
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                // If we can't vectorize the sentences, just return the document
                .recover(e -> new RagDocumentContext<>(document, List.of(), id, meta, null))
                // Add the links to each of the tickets
                .map(ragDocumentContext -> ragDocumentContext.updateLink(ticketToLink(zenDeskUrl.get(), meta, authHeader)))
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


    /**
     * A record that hold the arguments used by the tool. This centralizes the logic for extracting, validating, and sanitizing
     * the various inputs to the tool.
     */
    record Arguments(String organization,
                     List<String> excludedOrganization,
                     String recipient,
                     List<String> excludedSubmitters,
                     int hours,
                     int days,
                     int numComments,
                     String token,
                     String url,
                     String user,
                     String customModel,
                     @Nullable Integer contextWindow,
                     String startDate,
                     int contextWindowChars) {
        public static Arguments fromToolArgs(final List<ToolArgs> arguments, final ArgsAccessor argsAccessor, final Map<String, String> context, final ValidateInputs validateInputs, final ValidateString validateString, final SanitizeArgument sanitizeOrganization, final SanitizeArgument sanitizeEmail, final Encryptor textEncryptor, final String prompt, final Optional<String> zenDeskAccessToken, final Optional<String> zenDeskUrl, final Optional<String> zenDeskUser, final Optional<String> zenExcludedOrgs, final String model, final Optional<String> contextWindow) {
            final String organization = validateInputs.getCommaSeparatedList(
                    prompt,
                    argsAccessor.getArgument(arguments, "zenDeskOrganization", ""));

            final List<String> excludedOrganization = Arrays.stream(validateInputs.getCommaSeparatedList(
                            prompt,
                            argsAccessor.getArgument(arguments, "excludeOrganization", "")).split(","))
                    .map(String::trim)
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.toList());

            excludedOrganization.addAll(Arrays.stream(zenExcludedOrgs.orElse("").split(",")).toList());

            final String recipient = argsAccessor.getArgument(arguments, "recipient", "");

            // These arguments get swapped by the LLM all the time, so we need to fix them
            final String fixedRecipient = sanitizeEmail.sanitize(EmailValidator.getInstance().isValid(sanitizeEmail.sanitize(organization, prompt)) && StringUtils.isBlank(recipient) ? organization : recipient, prompt);
            final String fixedOrganization = sanitizeOrganization.sanitize(EmailValidator.getInstance().isValid(sanitizeEmail.sanitize(organization, prompt)) && StringUtils.isBlank(recipient) ? "" : organization, prompt);

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

            // Try to decrypt the value, otherwise assume it is a plain text value, and finally
            // fall back to the value defined in the local configuration.
            final Try<String> token = Try.of(() -> textEncryptor.decrypt(context.get("zendesk_access_token")))
                    .recover(e -> context.get("zendesk_access_token"))
                    .mapTry(validateString::throwIfEmpty)
                    .recoverWith(e -> Try.of(() -> zenDeskAccessToken.get()));

            if (token.isFailure() || StringUtils.isBlank(token.get())) {
                throw new FailedTool("Failed to get Zendesk access token");
            }

            final Try<String> url = getContext("zendesk_url", context, textEncryptor)
                    .recoverWith(e -> Try.of(() -> zenDeskUrl.get()));

            if (url.isFailure() || StringUtils.isBlank(url.get())) {
                throw new FailedTool("Failed to get Zendesk URL");
            }

            final Try<String> user = Try.of(() -> textEncryptor.decrypt(context.get("zendesk_user")))
                    .recover(e -> context.get("zendesk_user"))
                    .mapTry(validateString::throwIfEmpty)
                    .recoverWith(e -> Try.of(() -> zenDeskUser.get()));

            if (user.isFailure() || StringUtils.isBlank(user.get())) {
                throw new FailedTool("Failed to get Zendesk User");
            }

            final String customModel = Try.of(() -> context.get("custom_model"))
                    .mapTry(validateString::throwIfEmpty)
                    .recover(e -> model)
                    .get();

            final String startDate = OffsetDateTime.now(ZoneId.systemDefault())
                    .truncatedTo(ChronoUnit.SECONDS)
                    // Assume one day if nothing was specified
                    .minusDays(fixedDays + fixedHours == 0 ? 1 : fixedDays)
                    .minusHours(fixedHours)
                    .format(ISO_OFFSET_DATE_TIME);

            final Integer contextWindowValue = Try.of(contextWindow::get)
                    .map(Integer::parseInt)
                    .recover(e -> null)
                    .get();

            final int contextWindowChars = contextWindowValue == null
                    ? Constants.MAX_CONTEXT_LENGTH
                    : (int) (contextWindowValue * Constants.CONTENT_WINDOW_BUFFER * Constants.CHARACTERS_PER_TOKEN);

            return new Arguments(fixedOrganization, excludedOrganization, fixedRecipient, exclude, fixedHours, fixedDays, numComments, token.get(), url.get(), user.get(), customModel, contextWindowValue, startDate, contextWindowChars);
        }

        private static int switchArguments(final String prompt, final int a, final int b, final String aPromptKeyword, final String bPromptKeyword) {
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

        private static Try<String> getContext(final String name, final Map<String, String> context, Encryptor textEncryptor) {
            return Try.of(() -> textEncryptor.decrypt(context.get(name)))
                    .recover(e -> context.get(name))
                    .mapTry(Objects::requireNonNull);
        }
    }
}
