package secondbrain.domain.tools;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jasypt.util.text.BasicTextEncryptor;
import org.jspecify.annotations.NonNull;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.debug.DebugToolArgs;
import secondbrain.domain.limit.ListLimiter;
import secondbrain.domain.strings.ValidateString;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.ollama.OllamaGenerateBody;
import secondbrain.infrastructure.ollama.OllamaResponse;
import secondbrain.infrastructure.zendesk.ZenDeskClient;
import secondbrain.infrastructure.zendesk.ZenDeskCommentResponse;
import secondbrain.infrastructure.zendesk.ZenDeskResponse;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;

@ApplicationScoped
public class ZenDeskOrganization implements Tool {
    private static final int DEFAULT_DURATION = 30;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

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
                new ToolArguments("days", "The number of days worth of tickets to return", "30"));
    }

    @Override
    public String call(Map<String, String> context, String prompt, List<ToolArgs> arguments) {
        final String owner = argsAccessor.getArgument(arguments, "organization", "");

        final int days = Try.of(() -> Integer.parseInt(argsAccessor.getArgument(arguments, "days", "30")))
                .recover(throwable -> 30)
                .get();

        final BasicTextEncryptor textEncryptor = new BasicTextEncryptor();
        textEncryptor.setPassword(encryptionPassword);

        // Try to decrypt the value, otherwise assume it is a plain text value, and finally
        // fall back to the value defined in the local configuration.
        final Try<String> token = Try.of(() -> textEncryptor.decrypt(context.get("zendesk_access_token")))
                .recover(e -> context.get("zendesk_access_token"))
                .mapTry(Objects::requireNonNull)
                .recoverWith(e -> Try.of(() -> zenDeskAccessToken.get()));

        if (token.isFailure() || StringUtils.isBlank(token.get())) {
            return "Failed to get Zendesk access token";
        }

        final Try<String> user = Try.of(() -> textEncryptor.decrypt(context.get("zendesk_user")))
                .recover(e -> context.get("zendesk_user"))
                .mapTry(Objects::requireNonNull)
                .recoverWith(e -> Try.of(() -> zenDeskUser.get()));

        if (token.isFailure() || StringUtils.isBlank(token.get())) {
            return "Failed to get Zendesk User";
        }

        final String authHeader = "Basic " + new String(Try.of(() -> new Base64().encode(
                (user.get() + "/token:" + token.get()).getBytes())).get());

        final String query = "type:ticket created>"
                + LocalDateTime.now().minusDays(days).format(ISO_LOCAL_DATE)
                + " organization:" + owner;

        return Try.withResources(ClientBuilder::newClient)
                .of(client -> Try.of(() -> zenDeskClient.getTickets(client, authHeader, zenDeskUrl.get(), query))
                        .map(response -> ticketToFirstComment(response, client, authHeader))
                        .map(list -> listLimiter.limitListContent(list, NumberUtils.toInt(limit, Constants.MAX_CONTEXT_LENGTH)))
                        .map(list -> String.join("\n", list))
                        .mapTry(validateString::throwIfEmpty)
                        .map(contextString -> buildToolPrompt(contextString, prompt))
                        .map(llmPrompt -> ollamaClient.getTools(
                                client,
                                new OllamaGenerateBody(model, llmPrompt, false)))
                        .map(OllamaResponse::response)
                        .map(response -> response + debugToolArgs.debugArgs(arguments, true))
                        .recover(throwable -> "Failed to get tickets or comments: " + throwable.getMessage())
                        .get())
                .get();
    }

    @NotNull
    private List<String> ticketToFirstComment(@NotNull final ZenDeskResponse response,
                                              @NotNull final Client client,
                                              @NotNull final String authorization) {
        return response.results().stream()
                // Get the comments associated with the ticket
                .map(ticket -> zenDeskClient.getComments(client, authorization, zenDeskUrl.get(), ticket.id()))
                // Get the first comment, or an empty list
                .flatMap(comments -> comments.getResults().stream().limit(1))
                // get the comment body
                .map(ZenDeskCommentResponse::body)
                // Get the comment body as a LLM context string
                .map(this::diffToContext)
                // Get a list of context strings
                .collect(Collectors.toList());
    }

    private String diffToContext(@NotNull final String comment) {
        /*
        See https://github.com/meta-llama/llama-recipes/issues/450 for a discussion
        on the preferred format (or lack thereof) for RAG context.
        */
        return "<|start_header_id|>system<|end_header_id|>\n"
                + "ZenDesk Ticket:\n"
                + comment
                + "\n<|eot_id|>";
    }


    @NotNull
    public String buildToolPrompt(@NotNull final String context, @NonNull final String prompt) {
        return """
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
                + context
                + "\n<|start_header_id|>user<|end_header_id|>"
                + prompt
                + "<|eot_id|>"
                + "\n<|start_header_id|>assistant<|end_header_id|>".stripLeading();
    }
}
