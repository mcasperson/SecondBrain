package secondbrain.domain.tools.zendesk;

import io.smallrye.common.annotation.Identifier;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.config.LocalConfigFilteredItem;
import secondbrain.domain.config.LocalConfigKeywordsEntity;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.IndividualContext;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.debug.DebugToolArgs;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptionhandling.ExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.processing.DataToRagDoc;
import secondbrain.domain.processing.RatingMetadata;
import secondbrain.domain.sanitize.SanitizeDocument;
import secondbrain.domain.tooldefs.IntermediateResult;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateList;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.zendesk.ZenDeskClient;
import secondbrain.infrastructure.zendesk.api.IdToString;
import secondbrain.infrastructure.zendesk.api.ZenDeskOrganizationItemResponse;
import secondbrain.infrastructure.zendesk.api.ZenDeskTicket;
import secondbrain.infrastructure.zendesk.api.ZenDeskUserItemResponse;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;

@ApplicationScoped
public class ZenDeskIndividualTicket implements Tool<ZenDeskTicket> {
    public static final String ZENDESK_RATING_QUESTION_ARG = "ticketRatingQuestion";
    public static final String ZENDESK_DEFAULT_RATING_ARG = "ticketDefaultRating";
    public static final String ZENDESK_KEYWORD_ARG = "keywords";
    public static final String ZENDESK_KEYWORD_WINDOW_ARG = "keywordWindow";
    public static final String ZENDESK_ENTITY_NAME_CONTEXT_ARG = "entity";
    public static final String ZENDESK_TICKET_ID_ARG = "ticketId";
    public static final String ZENDESK_TICKET_SUBJECT_ARG = "ticketSubject";
    public static final String ZENDESK_TICKET_SUBMITTER_ARG = "ticketSubmitter";
    public static final String ZENDESK_TICKET_ORGANIZATION_ARG = "ticketOrganization";
    public static final String ZENDESK_TICKET_CREATED_AT_ARG = "ticketCreatedAt";
    public static final String ZENDESK_TICKET_ASSIGNEE_ARG = "ticketAssignee";
    public static final String ZENDESK_URL_ARG = "zendeskUrl";
    public static final String ZENDESK_EMAIL_ARG = "zendeskEmail";
    public static final String ZENDESK_TOKEN_ARG = "zendeskToken";
    public static final String ZENDESK_HISTORY_TTL_ARG = "historyTtl";

    private static final String INSTRUCTIONS = "You will be penalized for including ticket numbers or IDs, invoice numbers, purchase order numbers, or reference numbers.";

    @Inject
    private DataToRagDoc dataToRagDoc;

    @Inject
    private RatingMetadata ratingMetadata;

    @Inject
    private ZenDeskTicketConfig config;

    @Inject
    @Identifier("removeSpacing")
    private SanitizeDocument removeSpacing;

    @Inject
    @Preferred
    private LlmClient llmClient;

    @Inject
    @Preferred
    private ZenDeskClient zenDeskClient;

    @Inject
    private DebugToolArgs debugToolArgs;

    @Inject
    private ValidateList validateList;

    @Inject
    private ValidateString validateString;

    @Inject
    private Logger logger;

    @Inject
    private ExceptionMapping exceptionMapping;

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

    private String getContextLabelWithDate(@Nullable final ZenDeskTicket ticket) {
        if (ticket == null || ticket.createdAt() == null) {
            return getContextLabel();
        }
        return getContextLabel() + " " + ticket.createdAt();
    }

    @Override
    public List<RagDocumentContext<ZenDeskTicket>> getContext(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final ZenDeskTicketConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final Try<List<RagDocumentContext<ZenDeskTicket>>> result = Try.of(() -> ticketToComments(
                        parsedArgs.getSecretAuthHeader(),
                        parsedArgs.getNumComments(),
                        parsedArgs))
                .filter(ragDoc -> !validateString.isBlank(ragDoc, RagDocumentContext::document))
                .map(ticket -> ticket.addMetadata(ratingMetadata.getMetadata(getName(), environmentSettings, ticket, parsedArgs)))
                .map(ticket -> ticket.addIntermediateResult(
                        new IntermediateResult(ticket.document(), ticketToFileName(ticket))))
                .map(List::of)
                // deal with the filter failing
                .recover(NoSuchElementException.class, ex -> List.of());

        return exceptionMapping.map(result).get();
    }

    private String ticketToFileName(final RagDocumentContext<ZenDeskTicket> ticket) {
        return "ZenDesk-" + ticket.id() + ".txt";
    }

    @Override
    public RagMultiDocumentContext<ZenDeskTicket> call(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {

        final ZenDeskTicketConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final String debugArgs = debugToolArgs.debugArgs(arguments);

        final Try<RagMultiDocumentContext<ZenDeskTicket>> result = Try.of(() -> getContext(environmentSettings, prompt, arguments))
                .map(validateList::throwIfEmpty)
                // Combine the individual zen desk tickets into a parent RagMultiDocumentContext
                .map(tickets -> new RagMultiDocumentContext<ZenDeskTicket>(prompt, INSTRUCTIONS, tickets))
                // Call Ollama with the final prompt
                .map(ragDoc -> llmClient.callWithCache(ragDoc, environmentSettings, getName()))
                // Clean up the response
                .map(response -> response.updateResponse(removeSpacing.sanitize(response.getResponse())));

        return exceptionMapping.map(result).get();
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
        return replaceLineBreaks(meta.subject())
                + " - "
                + getOrganization(authHeader, url, meta)
                + " - "
                + getUser(authHeader, url, meta)
                + " [" + meta.id() + "](" + idToLink(url, meta.id()) + ")";
    }

    private String replaceLineBreaks(final String text) {
        if (StringUtils.isBlank(text)) {
            return "";
        }

        return text.replaceAll("\\r\\n|\\r|\\n", " ");
    }

    private String getOrganization(final String authHeader, final String url, final ZenDeskTicket meta) {
        // Best effort to get the organization name, but don't treat this as a failure
        return Try.of(() -> zenDeskClient.getOrganization(authHeader, url, meta.getOrganizationId()))
                .map(ZenDeskOrganizationItemResponse::name)
                .getOrElse("Unknown Organization");
    }

    private String getUser(final String authHeader, final String url, final ZenDeskTicket meta) {
        // Best effort to get the username, but don't treat this as a failure
        return Try.of(() -> zenDeskClient.getUser(authHeader, url, meta.getAssigneeId()))
                .map(ZenDeskUserItemResponse::name)
                .getOrElse("Unknown User");
    }

    private String ticketToText(final IndividualContext<List<String>, ZenDeskTicket> comments) {
        return comments.meta().subject() + "\n" + String.join("\n", comments.context());
    }

    @Nullable
    private String getOrganizationName(final ZenDeskTicket meta, final String authHeader, final String url) {
        return Try
                .of(() -> zenDeskClient.getOrganization(authHeader, url, meta.getOrganizationId()))
                .map(ZenDeskOrganizationItemResponse::name)
                // Do a best effort here - we don't want to fail the whole process because we can't get the organization name
                .getOrNull();
    }

    private String idToLink(final String url, final String id) {
        return url + "/agent/tickets/" + id;
    }

    /**
     * Take a ZenDesk ticket, get all the comments associated with it, and convert them into a RagDocumentContext.
     *
     * @param authorization The authorization header to use for API calls
     * @param numComments   The number of comments to fetch for the ticket
     * @param parsedArgs    The parsed arguments containing configuration details
     * @return A RagDocumentContext containing the ticket and its comments
     */
    private RagDocumentContext<ZenDeskTicket> ticketToComments(final String authorization,
                                                               final int numComments,
                                                               final ZenDeskTicketConfig.LocalArguments parsedArgs) {
        final IdToString idToName = id -> Try.of(() -> zenDeskClient.getUser(
                        authorization,
                        parsedArgs.getUrl(),
                        id.toString()).name())
                .getOrElse("Unknown User");

        final IdToString idToEmail = id -> Try.of(() -> zenDeskClient.getUser(
                        authorization,
                        parsedArgs.getUrl(),
                        id.toString()).email())
                .getOrElse("Unknown Email");

        final IdToString authorIdToOrganizationName = id -> Try.of(() -> zenDeskClient.getUser(
                        authorization,
                        parsedArgs.getUrl(),
                        id.toString()))
                .map(author -> zenDeskClient.getOrganization(
                        authorization,
                        parsedArgs.getUrl(),
                        author.organization_id().toString()))
                .map(ZenDeskOrganizationItemResponse::name)
                .getOrElse("Unknown Organization");

        return Try.of(() -> new IndividualContext<>(
                        parsedArgs.getTicketId(),
                        zenDeskClient
                                .getComments(
                                        authorization,
                                        parsedArgs.getUrl(),
                                        parsedArgs.getTicketId(),
                                        parsedArgs.getSearchTTL())
                                .toProcessedCommentsResponse(idToName, idToEmail, authorIdToOrganizationName)
                                .ticketToBody(numComments),
                        new ZenDeskTicket(parsedArgs.getTicketId(),
                                parsedArgs.getTicketSubmitter(),
                                parsedArgs.getTicketAssignee(),
                                parsedArgs.getTicketSubject(),
                                parsedArgs.getTicketOrganization(),
                                "",
                                null,
                                null,
                                parsedArgs.getCreatedAt())))
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
                Stream.of(getContextLabelWithDate(meta), getOrganizationName(meta, authHeader, parsedArgs.getUrl()))
                        .filter(StringUtils::isNotBlank)
                        .toList());

        return dataToRagDoc.getDocumentContext(meta
                        .updateComments(document)
                        .updateUrl(ticketToLink(parsedArgs.getUrl(), meta, authHeader)),
                getName(),
                contextLabel,
                parsedArgs);
    }


}

@ApplicationScoped
class ZenDeskTicketConfig {
    private static final int MAX_TICKETS = 100;
    private static final int DEFAULT_RATING = 10;
    private static final String DEFAULT_TTL_SECONDS = (60 * 60 * 24 * 90) + "";

    @Inject
    @ConfigProperty(name = "sb.zendesk.ticketid")
    private Optional<String> configTicketId;

    @Inject
    @ConfigProperty(name = "sb.zendesk.ticketsubject")
    private Optional<String> configTicketSubject;

    @Inject
    @ConfigProperty(name = "sb.zendesk.ticketsubmitter")
    private Optional<String> configTicketSubmitter;

    @Inject
    @ConfigProperty(name = "sb.zendesk.ticketorganization")
    private Optional<String> configTicketOrganization;

    @Inject
    @ConfigProperty(name = "sb.zendesk.tickercreatedat")
    private Optional<String> configTicketCreatedAt;

    @Inject
    @ConfigProperty(name = "sb.zendesk.ticketassignee")
    private Optional<String> configTicketAssignee;

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
    @ConfigProperty(name = "sb.zendesk.contextFilterDefaultRating")
    private Optional<String> configContextFilterDefaultRating;

    @Inject
    @ConfigProperty(name = "sb.zendesk.keywords")
    private Optional<String> configKeywords;

    @Inject
    @ConfigProperty(name = "sb.zendesk.keywordwindow")
    private Optional<String> configKeywordWindow;

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

    public Optional<String> getConfigTicketSubmitter() {
        return configTicketSubmitter;
    }

    public Optional<String> getConfigTicketOrganization() {
        return configTicketOrganization;
    }

    public Optional<String> getConfigTicketAssignee() {
        return configTicketAssignee;
    }

    public Optional<String> getConfigContextFilterDefaultRating() {
        return configContextFilterDefaultRating;
    }

    public Optional<String> getConfigKeywords() {
        return configKeywords;
    }

    public Optional<String> getConfigKeywordWindow() {
        return configKeywordWindow;
    }

    public Optional<String> getConfigTicketCreatedAt() {
        return configTicketCreatedAt;
    }

    public class LocalArguments implements LocalConfigFilteredItem, LocalConfigKeywordsEntity {
        private final List<ToolArgs> arguments;

        private final String prompt;

        private final Map<String, String> context;

        public LocalArguments(final List<ToolArgs> arguments, final String prompt, final Map<String, String> context) {
            this.arguments = arguments;
            this.prompt = prompt;
            this.context = context;
        }

        @SuppressWarnings("NullAway")
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
                    ZenDeskIndividualTicket.ZENDESK_TICKET_ID_ARG,
                    "").getSafeValue();

        }

        public String getTicketSubject() {
            return getArgsAccessor().getArgument(
                    getConfigTicketSubject()::get,
                    arguments,
                    context,
                    ZenDeskIndividualTicket.ZENDESK_TICKET_SUBJECT_ARG,
                    ZenDeskIndividualTicket.ZENDESK_TICKET_SUBJECT_ARG,
                    "").getSafeValue();

        }

        public String getTicketSubmitter() {
            return getArgsAccessor().getArgument(
                    getConfigTicketSubmitter()::get,
                    arguments,
                    context,
                    ZenDeskIndividualTicket.ZENDESK_TICKET_SUBMITTER_ARG,
                    ZenDeskIndividualTicket.ZENDESK_TICKET_SUBMITTER_ARG,
                    "").getSafeValue();

        }

        public String getTicketOrganization() {
            return getArgsAccessor().getArgument(
                    getConfigTicketOrganization()::get,
                    arguments,
                    context,
                    ZenDeskIndividualTicket.ZENDESK_TICKET_ORGANIZATION_ARG,
                    ZenDeskIndividualTicket.ZENDESK_TICKET_ORGANIZATION_ARG,
                    "").getSafeValue();

        }

        public String getCreatedAt() {
            return getArgsAccessor().getArgument(
                    getConfigTicketCreatedAt()::get,
                    arguments,
                    context,
                    ZenDeskIndividualTicket.ZENDESK_TICKET_CREATED_AT_ARG,
                    ZenDeskIndividualTicket.ZENDESK_TICKET_CREATED_AT_ARG,
                    "").getSafeValue();

        }

        public String getTicketAssignee() {
            return getArgsAccessor().getArgument(
                    getConfigTicketAssignee()::get,
                    arguments,
                    context,
                    ZenDeskIndividualTicket.ZENDESK_TICKET_ASSIGNEE_ARG,
                    ZenDeskIndividualTicket.ZENDESK_TICKET_ASSIGNEE_ARG,
                    "").getSafeValue();

        }

        public String getSecretAuthHeader() {
            return "Basic " + new String(Try.of(() -> new Base64().encode(
                    (getUser() + "/token:" + getSecretToken()).getBytes(UTF_8))).get(), UTF_8);
        }

        @SuppressWarnings("NullAway")
        public String getSecretToken() {
            final String argument = getArgsAccessor().getArgument(
                    getConfigZenDeskAccessToken()::get,
                    arguments,
                    context,
                    ZenDeskIndividualTicket.ZENDESK_TOKEN_ARG,
                    "",
                    "").getSafeValue();

            // Try to decrypt the value, otherwise assume it is a plain text value, and finally
            // fall back to the value defined in the local configuration.
            final Try<String> token = Try.of(() -> getTextEncryptor().decrypt(context.get(ZenDeskIndividualTicket.ZENDESK_TOKEN_ARG)))
                    .recover(e -> context.get(ZenDeskIndividualTicket.ZENDESK_TOKEN_ARG))
                    .mapTry(getValidateString()::throwIfBlank)
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
                    "").getSafeValue();

            final Try<String> url = getContext("zendesk_url", context, getTextEncryptor())
                    .recoverWith(e -> Try.of(() -> argument));

            if (url.isFailure() || StringUtils.isBlank(url.get())) {
                throw new InternalFailure("Failed to get Zendesk URL");
            }

            return url.get();
        }

        @SuppressWarnings("NullAway")
        public String getUser() {
            final String argument = getArgsAccessor().getArgument(
                    getConfigZenDeskUser()::get,
                    arguments,
                    context,
                    ZenDeskIndividualTicket.ZENDESK_EMAIL_ARG,
                    "",
                    "").getSafeValue();

            final Try<String> user = Try.of(() -> getTextEncryptor().decrypt(context.get("zendesk_user")))
                    .recover(e -> context.get("zendesk_user"))
                    .mapTry(getValidateString()::throwIfBlank)
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
                    ZenDeskIndividualTicket.ZENDESK_HISTORY_TTL_ARG,
                    ZenDeskIndividualTicket.ZENDESK_HISTORY_TTL_ARG,
                    DEFAULT_TTL_SECONDS);

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
                    ZenDeskOrganization.NUM_COMMENTS_ARG,
                    MAX_TICKETS + "").getSafeValue();

            return Try.of(() -> Integer.parseInt(stringValue))
                    .recover(throwable -> MAX_TICKETS)
                    // Must be at least 1
                    .map(i -> Math.max(1, i))
                    .get();
        }

        @Override
        public String getContextFilterQuestion() {
            return getArgsAccessor().getArgument(
                            getConfigContextFilterQuestion()::get,
                            arguments,
                            context,
                            ZenDeskIndividualTicket.ZENDESK_RATING_QUESTION_ARG,
                            ZenDeskIndividualTicket.ZENDESK_RATING_QUESTION_ARG,
                            "")
                    .getSafeValue();
        }

        @Override
        public Integer getDefaultRating() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigContextFilterDefaultRating()::get,
                    arguments,
                    context,
                    ZenDeskIndividualTicket.ZENDESK_DEFAULT_RATING_ARG,
                    ZenDeskIndividualTicket.ZENDESK_DEFAULT_RATING_ARG,
                    DEFAULT_RATING + "");

            return Math.max(0, NumberUtils.toInt(argument.getSafeValue(), DEFAULT_RATING));
        }

        @Override
        public List<String> getKeywords() {
            return getArgsAccessor().getArgumentList(
                            getConfigKeywords()::get,
                            arguments,
                            context,
                            ZenDeskIndividualTicket.ZENDESK_KEYWORD_ARG,
                            ZenDeskIndividualTicket.ZENDESK_KEYWORD_ARG,
                            "")
                    .stream()
                    .map(Argument::value)
                    .toList();
        }

        @Override
        public int getKeywordWindow() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigKeywordWindow()::get,
                    arguments,
                    context,
                    ZenDeskIndividualTicket.ZENDESK_KEYWORD_WINDOW_ARG,
                    ZenDeskIndividualTicket.ZENDESK_KEYWORD_WINDOW_ARG,
                    Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH + "");

            return NumberUtils.toInt(argument.getSafeValue(), Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH);
        }

        @Override
        public String getEntity() {
            return getArgsAccessor().getArgument(
                    null,
                    null,
                    context,
                    null,
                    ZenDeskIndividualTicket.ZENDESK_ENTITY_NAME_CONTEXT_ARG,
                    "").getSafeValue();
        }
    }
}
