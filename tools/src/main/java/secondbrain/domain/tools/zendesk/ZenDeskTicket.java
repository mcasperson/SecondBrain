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
import secondbrain.domain.limit.ListLimiter;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.sanitize.SanitizeDocument;
import secondbrain.domain.tooldefs.MetaObjectResult;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.zendesk.ZenDeskClient;
import secondbrain.infrastructure.zendesk.api.ZenDeskOrganizationItemResponse;
import secondbrain.infrastructure.zendesk.api.ZenDeskResultsResponse;
import secondbrain.infrastructure.zendesk.api.ZenDeskUserItemResponse;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.base.Predicates.instanceOf;
import static java.nio.charset.StandardCharsets.UTF_8;

@ApplicationScoped
public class ZenDeskTicket implements Tool<ZenDeskResultsResponse> {
    public static final String ZENDESK_TICKET_ID_ARG = "ticketId";

    private static final String INSTRUCTIONS = """
            Summarise the ticket in one paragraph.
            You will be penalized for including ticket numbers or IDs, invoice numbers, purchase order numbers, or reference numbers.
            """.stripLeading();

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

    @Override
    public String getName() {
        return ZenDeskTicket.class.getSimpleName();
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
    public List<RagDocumentContext<ZenDeskResultsResponse>> getContext(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final ZenDeskTicketConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final Try<List<RagDocumentContext<ZenDeskResultsResponse>>> result = Try.withResources(ClientBuilder::newClient)
                .of(client -> getContext(
                        parsedArgs,
                        environmentSettings,
                        parsedArgs.getAuthHeader(),
                        parsedArgs.getUrl(),
                        parsedArgs.getTicketId(),
                        client));

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(
                        API.Case(API.$(instanceOf(EmptyString.class)), throwable -> new InternalFailure("The ZenDesk ticket is empty", throwable)),
                        API.Case(API.$(instanceOf(InternalFailure.class)), throwable -> throwable),
                        API.Case(API.$(instanceOf(FailedOllama.class)), throwable -> new InternalFailure(throwable.getMessage(), throwable)),
                        API.Case(API.$(), ex -> new ExternalFailure(getName() + " failed to call ZenDesk API", ex)))
                .get();

    }

    private List<RagDocumentContext<ZenDeskResultsResponse>> getContext(
            final ZenDeskTicketConfig.LocalArguments parsedArgs,
            final Map<String, String> environmentSettings,
            final String auth,
            final String url,
            final String ticketId,
            final Client client) {
        final Try<RagDocumentContext<ZenDeskResultsResponse>> context = Try.of(() -> zenDeskClient.getTicket(
                        client,
                        auth,
                        url,
                        ticketId,
                        parsedArgs.getSearchTTL()))
                // Get the ticket comments
                .map(response -> ticketToComments(
                        response,
                        client,
                        parsedArgs.getAuthHeader(),
                        parsedArgs.getNumComments(),
                        parsedArgs));

        final RagDocumentContext<ZenDeskResultsResponse> result = context.mapFailure(
                        API.Case(API.$(instanceOf(IllegalArgumentException.class)), throwable -> new InternalFailure("A required property was not defined", throwable)))
                .get();

        return List.of(result);
    }

    @Override
    public List<MetaObjectResult> getMetadata(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        return List.of();
    }

    @Override
    public RagMultiDocumentContext<ZenDeskResultsResponse> call(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {

        final ZenDeskTicketConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

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
                .recover(EmptyString.class, e -> new RagMultiDocumentContext<>("The ticket is empty", List.of(), ""));

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(
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
                        + Try.of(() -> zenDeskClient.getOrganization(client, authHeader, url, meta.organization_id()))
                        .map(ZenDeskOrganizationItemResponse::name)
                        .getOrElse("Unknown Organization")
                        + " - "
                        // Best effort to get the username, but don't treat this as a failure
                        + Try.of(() -> zenDeskClient.getUser(client, authHeader, url, meta.assignee_id()))
                        .map(ZenDeskUserItemResponse::name)
                        .getOrElse("Unknown User")
                        + " [" + meta.id() + "](" + idToLink(url, meta.id()) + ")")
                .get();
    }

    @Nullable
    private String getOrganizationName(final ZenDeskResultsResponse meta, final String authHeader, final String url) {
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

    private RagMultiDocumentContext<ZenDeskResultsResponse> mergeContext(final List<RagDocumentContext<ZenDeskResultsResponse>> context, final String debug, final String customModel) {
        return new RagMultiDocumentContext<>(
                context.stream()
                        .map(RagDocumentContext::document)
                        .map(content -> promptBuilderSelector.getPromptBuilder(customModel).buildContextPrompt("ZenDesk Ticket", content))
                        .collect(Collectors.joining("\n")),
                context,
                debug);
    }

    private RagDocumentContext<ZenDeskResultsResponse> ticketToComments(final ZenDeskResultsResponse ticket,
                                                                        final Client client,
                                                                        final String authorization,
                                                                        final int numComments,
                                                                        final ZenDeskTicketConfig.LocalArguments parsedArgs) {
        return Try.of(() -> ticket)
                // Get the context associated with the ticket
                .map(t -> new IndividualContext<>(
                        t.id(),
                        zenDeskClient
                                .getComments(
                                        client,
                                        authorization,
                                        parsedArgs.getUrl(),
                                        t.id(),
                                        parsedArgs.getSearchTTL())
                                .ticketToBody(numComments),
                        t))
                // Combine the ticket subject and body into a single string
                .map(comments -> comments.updateContext(
                        comments.meta().subject() + "\n" + String.join("\n", comments.context())))
                // Get the LLM context string as a RAG context, complete with vectorized sentences
                .map(comments -> getDocumentContext(
                        comments.context(),
                        comments.id(),
                        comments.meta(),
                        authorization,
                        parsedArgs))
                .get();
    }

    private RagDocumentContext<ZenDeskResultsResponse> getDocumentContext(
            final String document,
            final String id,
            final ZenDeskResultsResponse meta,
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
                        sentences.stream()
                                .map(sentence -> sentenceVectorizer.vectorize(sentence))
                                .collect(Collectors.toList()),
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
                    ZenDeskTicket.ZENDESK_TICKET_ID_ARG,
                    "zendesk_ticketid",
                    "").value();

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
    }
}
