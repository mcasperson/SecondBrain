package secondbrain.domain.tools.zendesk;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jasypt.util.text.BasicTextEncryptor;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.IndividualContext;
import secondbrain.domain.context.MergedContext;
import secondbrain.domain.debug.DebugToolArgs;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.limit.ListLimiter;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.ollama.OllamaGenerateBodyWithContext;
import secondbrain.infrastructure.zendesk.ZenDeskClient;
import secondbrain.infrastructure.zendesk.ZenDeskCommentResponse;
import secondbrain.infrastructure.zendesk.ZenDeskCommentsResponse;
import secondbrain.infrastructure.zendesk.ZenDeskResponse;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;

@ApplicationScoped
public class ZenDeskOrganization implements Tool {
    private static final int DEFAULT_DURATION = 30;

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
                new ToolArguments("days", "The number of days worth of tickets to return", DEFAULT_DURATION + ""));
    }

    @Override
    public String call(final Map<String, String> context, final String prompt, final List<ToolArgs> arguments) {
        final String owner = argsAccessor.getArgument(arguments, "organization", "");

        final int days = Try.of(() -> Integer.parseInt(argsAccessor.getArgument(arguments, "days", "30")))
                .recover(throwable -> DEFAULT_DURATION)
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
        query.add("created>" + LocalDateTime.now(ZoneId.systemDefault()).minusDays(days).format(ISO_LOCAL_DATE));

        if (!owner.isEmpty()) {
            query.add("organization:" + owner);
        }

        return Try.withResources(ClientBuilder::newClient)
                .of(client -> Try.of(() -> zenDeskClient.getTickets(client, authHeader, url.get(), String.join(" ", query)))
                        .map(response -> ticketToFirstComment(response, client, authHeader))
                        .map(list -> listLimiter.limitIndividualContextListContent(
                                list,
                                NumberUtils.toInt(limit, Constants.MAX_CONTEXT_LENGTH)))
                        .map(this::mergeContext)
                        .mapTry(mergedContext -> validateString.throwIfEmpty(mergedContext, MergedContext::context))
                        .map(contextString -> buildToolPrompt(contextString, prompt))
                        .map(llmPrompt -> ollamaClient.getTools(
                                client,
                                new OllamaGenerateBodyWithContext(model, llmPrompt, false)))
                        .map(response -> response.ollamaResponse().response()
                                + System.lineSeparator() + System.lineSeparator()
                                + "Tickets:" + System.lineSeparator()
                                + idsToLinks(url.get(), response.ids())
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
     * @param url The ZenDesk url
     * @param ids The list of ticket IDs
     * @return A Markdown list of source tickets
     */
    private String idsToLinks(final String url, final List<String> ids) {
        return ids.stream()
                .map(id -> "* [" + id + "](" + idToLink(url, id) + ")")
                .collect(Collectors.joining("\n"));
    }

    private String idToLink(final String url, final String id) {
        return url + "/agent/tickets/" + id;
    }

    private MergedContext mergeContext(final List<IndividualContext<String>> context) {
        return new MergedContext(
                context.stream()
                        .map(IndividualContext::id)
                        .collect(Collectors.toList()),
                context.stream()
                        .map(IndividualContext::context)
                        .collect(Collectors.joining("\n")));
    }


    private Try<String> getContext(final String name, final Map<String, String> context) {
        final BasicTextEncryptor textEncryptor = new BasicTextEncryptor();
        textEncryptor.setPassword(encryptionPassword);

        return Try.of(() -> textEncryptor.decrypt(context.get(name)))
                .recover(e -> context.get(name))
                .mapTry(Objects::requireNonNull);
    }


    private List<IndividualContext<String>> ticketToFirstComment(final ZenDeskResponse response,
                                                                 final Client client,
                                                                 final String authorization) {
        return response.results().stream()
                // Get the context associated with the ticket
                .map(ticket -> new IndividualContext<>(ticket.id(), zenDeskClient.getComments(client, authorization, zenDeskUrl.get(), ticket.id())))
                // Get the first comment, or an empty list
                .map(comments -> new IndividualContext<>(comments.id(), ticketToBody(comments.context(), 1)))
                // Get the comment body as a LLM context string
                .map(comments -> new IndividualContext<>(comments.id(), diffToContext(comments.context())))
                // Get a list of context strings
                .collect(Collectors.toList());
    }

    private List<String> ticketToBody(final ZenDeskCommentsResponse comments, final int limit) {
        return comments
                .getResults()
                .stream()
                .limit(limit)
                .map(ZenDeskCommentResponse::body)
                .collect(Collectors.toList());
    }

    private String diffToContext(final List<String> comment) {
        /*
        See https://github.com/meta-llama/llama-recipes/issues/450 for a discussion
        on the preferred format (or lack thereof) for RAG context.
        */
        return "<|start_header_id|>system<|end_header_id|>\n"
                + "ZenDesk Ticket:\n"
                + String.join("\n", comment)
                + "\n<|eot_id|>";
    }


    public MergedContext buildToolPrompt(final MergedContext context, final String prompt) {
        return new MergedContext(context.ids(),
                """
                        <|begin_of_text|>
                        <|start_header_id|>system<|end_header_id|>
                        You are an expert in reading help desk tickets.
                        You are given a question and a list of ZenDesk Tickets related to the question.
                        You must assume the information required to answer the question is present in the ZenDesk Tickets.
                        You must answer the question based on the ZenDesk Tickets provided.
                        When the user asks a question indicating that they want to know about ZenDesk Tickets, you must generate the answer based on the ZenDesk Tickets.
                        You will be penalized for suggesting manual steps to generate the answer.
                        You will be penalized for responding that you don't have access to real-time data or zen desk instances.
                        You will be penalized for referencing issues that are not present in the ZenDesk Tickets.
                        If there are no ZenDesk Tickets, you must indicate that in the answer.
                        <|eot_id|>
                        """
                        + context.context()
                        + "\n<|start_header_id|>user<|end_header_id|>"
                        + prompt
                        + "<|eot_id|>"
                        + "\n<|start_header_id|>assistant<|end_header_id|>".stripLeading());
    }
}
