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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jsoup.internal.StringUtil;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.config.ModelConfig;
import secondbrain.domain.context.*;
import secondbrain.domain.debug.DebugToolArgs;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.FailedOllama;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.sanitize.SanitizeDocument;
import secondbrain.domain.tooldefs.*;
import secondbrain.domain.tools.rating.RatingTool;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.zendesk.ZenDeskClient;
import secondbrain.infrastructure.zendesk.api.ZenDeskOrganizationItemResponse;
import secondbrain.infrastructure.zendesk.api.ZenDeskTicket;
import secondbrain.infrastructure.zendesk.api.ZenDeskUserItemResponse;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.base.Predicates.instanceOf;
import static java.nio.charset.StandardCharsets.UTF_8;

@ApplicationScoped
public class ZenDeskIndividualTicket implements Tool<ZenDeskTicket> {
    public static final String ZENDESK_FILTER_RATING_META = "FilterRating";
    public static final String ZENDESK_TICKET_ID_ARG = "ticketId";
    public static final String ZENDESK_TICKET_SUBJECT_ARG = "ticketSubject";
    public static final String ZENDESK_URL_ARG = "zendeskUrl";
    public static final String ZENDESK_EMAIL_ARG = "zendeskEmail";
    public static final String ZENDESK_TOKEN_ARG = "zendeskToken";

    private static final String INSTRUCTIONS = "You will be penalized for including ticket numbers or IDs, invoice numbers, purchase order numbers, or reference numbers.";

    @Inject
    private RatingTool ratingTool;

    @Inject
    private ModelConfig modelConfig;

    @Inject
    private ZenDeskTicketConfig config;

    @Inject
    @Identifier("removeSpacing")
    private SanitizeDocument removeSpacing;

    @Inject
    private OllamaClient ollamaClient;

    @Inject
    @Preferred
    private ZenDeskClient zenDeskClient;

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

    @Override
    public String getName() {
        return ZenDeskIndividualTicket.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Returns the details of a single ZenDesk support ticket";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return List.of(new ToolArguments(ZENDESK_TICKET_ID_ARG, "The ID of the ticket to retrieve", ""));
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

        final ZenDeskTicketConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final Try<List<RagDocumentContext<ZenDeskTicket>>> result = Try.withResources(ClientBuilder::newClient)
                .of(client -> ticketToComments(
                        client,
                        parsedArgs.getAuthHeader(),
                        parsedArgs.getNumComments(),
                        parsedArgs))
                .map(ticket -> ticket.updateMetadata(
                        getMetadata(ticket, environmentSettings, prompt, arguments)))
                .map(ticket -> ticket.updateIntermediateResult(
                        new IntermediateResult(ticket.document(), ticketToFileName(ticket))))
                .map(List::of);

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(
                        API.Case(API.$(instanceOf(IllegalArgumentException.class)), throwable -> new InternalFailure("A required property was not defined", throwable)),
                        API.Case(API.$(instanceOf(EmptyString.class)), throwable -> new InternalFailure("The ZenDesk ticket is empty", throwable)),
                        API.Case(API.$(instanceOf(InternalFailure.class)), throwable -> throwable),
                        API.Case(API.$(instanceOf(FailedOllama.class)), throwable -> new InternalFailure(throwable.getMessage(), throwable)),
                        API.Case(API.$(), ex -> new ExternalFailure(getName() + " failed to call ZenDesk API", ex)))
                .get();
    }

    private MetaObjectResults getMetadata(
            final RagDocumentContext<ZenDeskTicket> ticket,
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {
        final ZenDeskTicketConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final List<MetaObjectResult> metadata = ticket.source() != null
                ? new ArrayList<>(ticket.source().toMetaObjectResult())
                : new ArrayList<>();

        if (!StringUtil.isBlank(parsedArgs.getContextFilterQuestion())) {
            final int filterRating = Try.of(() -> ratingTool.call(
                                    Map.of(RatingTool.RATING_DOCUMENT_CONTEXT_ARG, ticket.document()),
                                    parsedArgs.getContextFilterQuestion(),
                                    List.of())
                            .combinedDocument())
                    .map(rating -> org.apache.commons.lang3.math.NumberUtils.toInt(rating, 0))
                    // Ratings are provided on a best effort basis, so we ignore any failures
                    .recover(InternalFailure.class, ex -> 10)
                    .get();

            metadata.add(new MetaObjectResult(ZENDESK_FILTER_RATING_META, filterRating));
        }

        return new MetaObjectResults(metadata, ticketToMetaFileName(ticket), ticket.id());
    }

    private String ticketToMetaFileName(final RagDocumentContext<ZenDeskTicket> ticket) {
        return "ZenDesk-" + ticket.id() + ".json";
    }

    private String ticketToFileName(final RagDocumentContext<ZenDeskTicket> ticket) {
        return "ZenDesk-" + ticket.id() + ".md";
    }

    @Override
    public RagMultiDocumentContext<ZenDeskTicket> call(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {

        final ZenDeskTicketConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final String debugArgs = debugToolArgs.debugArgs(arguments);

        final Try<RagMultiDocumentContext<ZenDeskTicket>> result = Try.of(() -> getContext(environmentSettings, prompt, arguments))
                // Limit the content of the ticket to the configured context window
                .map(list -> list
                        .stream()
                        .map(ticket -> ticket
                                .updateDocument(ticket.document().substring(0, modelConfig.getCalculatedContextWindow(environmentSettings))))
                        .toList())
                // Combine the individual zen desk tickets into a parent RagMultiDocumentContext
                .map(tickets -> createMultiDocument(tickets, debugArgs, modelConfig.getCalculatedModel(environmentSettings)))
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
                .recover(EmptyString.class, e -> new RagMultiDocumentContext<>("The ticket is empty", List.of(), ""));

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(
                        API.Case(API.$(), throwable -> new ExternalFailure("Failed to get tickets or context: " + throwable.toString() + " " + throwable.getMessage() + debugArgs)))
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
    private String ticketToLink(final String url, final ZenDeskTicket meta, final String authHeader) {
        return Try.withResources(ClientBuilder::newClient)
                .of(client -> replaceLineBreaks(meta.subject())
                        + " - "
                        + getOrganization(client, authHeader, url, meta)
                        + " - "
                        + getUser(client, authHeader, url, meta)
                        + " [" + meta.id() + "](" + idToLink(url, meta.id()) + ")")
                .get();
    }

    private String replaceLineBreaks(final String text) {
        if (StringUtils.isBlank(text)) {
            return "";
        }

        return text.replaceAll("\\r\\n|\\r|\\n", " ");
    }

    private String getOrganization(final Client client, final String authHeader, final String url, final ZenDeskTicket meta) {
        // Best effort to get the organization name, but don't treat this as a failure
        return Try.of(() -> zenDeskClient.getOrganization(client, authHeader, url, meta.organization_id()))
                .map(ZenDeskOrganizationItemResponse::name)
                .getOrElse("Unknown Organization");
    }

    private String getUser(final Client client, final String authHeader, final String url, final ZenDeskTicket meta) {
        // Best effort to get the username, but don't treat this as a failure
        return Try.of(() -> zenDeskClient.getUser(client, authHeader, url, meta.assignee_id()))
                .map(ZenDeskUserItemResponse::name)
                .getOrElse("Unknown User");
    }

    private String ticketToText(final IndividualContext<List<String>, ZenDeskTicket> comments) {
        return comments.meta().subject() + "\n" + String.join("\n", comments.context());
    }

    @Nullable
    private String getOrganizationName(final ZenDeskTicket meta, final String authHeader, final String url) {
        return Try.withResources(ClientBuilder::newClient)
                .of(client -> Try
                        .of(() -> zenDeskClient.getOrganization(client, authHeader, url, meta.organization_id()))
                        .map(ZenDeskOrganizationItemResponse::name)
                        .get())
                // Do a best effort here - we don't want to fail the whole process because we can't get the organization name
                .getOrNull();
    }

    private String idToLink(final String url, final String id) {
        return url + "/agent/tickets/" + id;
    }

    /**
     * Create a RagMultiDocumentContext from a list of RagDocumentContexts. This combines the individual
     * documents into a single string, which is then used as the context for the prompt.
     */
    private RagMultiDocumentContext<ZenDeskTicket> createMultiDocument(final List<RagDocumentContext<ZenDeskTicket>> context, final String debug, final String customModel) {
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

    /**
     * Take a ZenDesk ticket, get all the comments associated with it, and convert them into a RagDocumentContext.
     *
     * @param client        The JAX-RS client to use for API calls
     * @param authorization The authorization header to use for API calls
     * @param numComments   The number of comments to fetch for the ticket
     * @param parsedArgs    The parsed arguments containing configuration details
     * @return A RagDocumentContext containing the ticket and its comments
     */
    private RagDocumentContext<ZenDeskTicket> ticketToComments(final Client client,
                                                               final String authorization,
                                                               final int numComments,
                                                               final ZenDeskTicketConfig.LocalArguments parsedArgs) {
        return Try.of(() -> new IndividualContext<>(
                        parsedArgs.getTicketId(),
                        zenDeskClient
                                .getComments(
                                        client,
                                        authorization,
                                        parsedArgs.getUrl(),
                                        parsedArgs.getTicketId(),
                                        parsedArgs.getSearchTTL())
                                .ticketToBody(numComments),
                        new ZenDeskTicket(parsedArgs.getTicketId(), parsedArgs.getTicketSubject())))
                // Get the LLM context string as a RAG context, complete with vectorized sentences
                .map(comments -> getDocumentContext(
                        ticketToText(comments),
                        comments.id(),
                        comments.meta(),
                        authorization,
                        parsedArgs))
                .get();
    }

    private RagDocumentContext<ZenDeskTicket> getDocumentContext(
            final String document,
            final String id,
            final ZenDeskTicket meta,
            final String authHeader,
            final ZenDeskTicketConfig.LocalArguments parsedArgs) {
        final String contextLabel = String.join(
                " ",
                Stream.of(getContextLabel(), getOrganizationName(meta, authHeader, parsedArgs.getUrl()))
                        .filter(StringUtils::isNotBlank)
                        .toList());

        return Try.of(() -> sentenceSplitter.splitDocument(document, 10))
                .map(sentences -> new RagDocumentContext<>(
                        contextLabel,
                        document,
                        sentenceVectorizer.vectorize(sentences),
                        id,
                        meta,
                        ticketToLink(parsedArgs.getUrl(), meta, authHeader)))
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                .get();
    }
}

@ApplicationScoped
class ZenDeskTicketConfig {
    private static final int MAX_TICKETS = 100;
    private static final String DEFAULT_TTL = (1000 * 60 * 60 * 24) + "";

    @Inject
    @ConfigProperty(name = "sb.zendesk.ticketid")
    private Optional<String> configTicketId;

    @Inject
    @ConfigProperty(name = "sb.zendesk.ticketsubject")
    private Optional<String> configTicketSubject;

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
    @ConfigProperty(name = "sb.zendesk.historyttl")
    private Optional<String> configHistoryttl;

    @Inject
    @ConfigProperty(name = "sb.zendesk.numcomments")
    private Optional<String> configZenDeskNumComments;

    @Inject
    @ConfigProperty(name = "sb.zendesk.contextFilterQuestion")
    private Optional<String> configContextFilterQuestion;

    @Inject
    @ConfigProperty(name = "sb.zendesk.contextFilterMinimumRating")
    private Optional<String> configContextFilterMinimumRating;

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private Encryptor textEncryptor;

    @Inject
    private ValidateString validateString;

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    public Encryptor getTextEncryptor() {
        return textEncryptor;
    }

    public ValidateString getValidateString() {
        return validateString;
    }

    public Optional<String> getConfigTicketId() {
        return configTicketId;
    }

    public Optional<String> getConfigTicketSubject() {
        return configTicketSubject;
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

    public Optional<String> getConfigHistoryttl() {
        return configHistoryttl;
    }

    public Optional<String> getConfigZenDeskNumComments() {
        return configZenDeskNumComments;
    }

    public Optional<String> getConfigContextFilterQuestion() {
        return configContextFilterQuestion;
    }

    public Optional<String> getConfigContextFilterMinimumRating() {
        return configContextFilterMinimumRating;
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

        private Try<String> getContext(final String name, final Map<String, String> context, Encryptor textEncryptor) {
            return Try.of(() -> textEncryptor.decrypt(context.get(name)))
                    .recover(e -> context.get(name))
                    .mapTry(Objects::requireNonNull);
        }

        public String getTicketId() {
            return getArgsAccessor().getArgument(
                    getConfigTicketId()::get,
                    arguments,
                    context,
                    ZenDeskIndividualTicket.ZENDESK_TICKET_ID_ARG,
                    "zendesk_ticketid",
                    "").value();

        }

        public String getTicketSubject() {
            return getArgsAccessor().getArgument(
                    getConfigTicketSubject()::get,
                    arguments,
                    context,
                    ZenDeskIndividualTicket.ZENDESK_TICKET_SUBJECT_ARG,
                    "zendesk_ticketsubject",
                    "").value();

        }

        public String getAuthHeader() {
            return "Basic " + new String(Try.of(() -> new Base64().encode(
                    (getUser() + "/token:" + getToken()).getBytes(UTF_8))).get(), UTF_8);
        }

        public String getToken() {
            final String argument = getArgsAccessor().getArgument(
                    getConfigZenDeskAccessToken()::get,
                    arguments,
                    context,
                    ZenDeskIndividualTicket.ZENDESK_TOKEN_ARG,
                    "",
                    "").value();

            // Try to decrypt the value, otherwise assume it is a plain text value, and finally
            // fall back to the value defined in the local configuration.
            final Try<String> token = Try.of(() -> getTextEncryptor().decrypt(context.get("zendesk_access_token")))
                    .recover(e -> context.get("zendesk_access_token"))
                    .mapTry(getValidateString()::throwIfEmpty)
                    .recoverWith(e -> Try.of(() -> argument));

            if (token.isFailure() || StringUtils.isBlank(token.get())) {
                throw new InternalFailure("Failed to get Zendesk access token");
            }

            return token.get();
        }

        public String getUrl() {
            final String argument = getArgsAccessor().getArgument(
                    getConfigZenDeskUrl()::get,
                    arguments,
                    context,
                    ZenDeskIndividualTicket.ZENDESK_URL_ARG,
                    "",
                    "").value();

            final Try<String> url = getContext("zendesk_url", context, getTextEncryptor())
                    .recoverWith(e -> Try.of(() -> argument));

            if (url.isFailure() || StringUtils.isBlank(url.get())) {
                throw new InternalFailure("Failed to get Zendesk URL");
            }

            return url.get();
        }

        public String getUser() {
            final String argument = getArgsAccessor().getArgument(
                    getConfigZenDeskUser()::get,
                    arguments,
                    context,
                    ZenDeskIndividualTicket.ZENDESK_EMAIL_ARG,
                    "",
                    "").value();

            final Try<String> user = Try.of(() -> getTextEncryptor().decrypt(context.get("zendesk_user")))
                    .recover(e -> context.get("zendesk_user"))
                    .mapTry(getValidateString()::throwIfEmpty)
                    .recoverWith(e -> Try.of(() -> argument));

            if (user.isFailure() || StringUtils.isBlank(user.get())) {
                throw new InternalFailure("Failed to get Zendesk User");
            }

            return user.get();
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
    }
}
