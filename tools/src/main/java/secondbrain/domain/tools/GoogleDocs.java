package secondbrain.domain.tools;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.docs.v1.Docs;
import com.google.api.services.docs.v1.model.*;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import io.vavr.control.Try;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jasypt.util.text.BasicTextEncryptor;
import org.jspecify.annotations.NonNull;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.debug.DebugToolArgs;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.ollama.OllamaGenerateBody;
import secondbrain.infrastructure.ollama.OllamaResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Dependent
public class GoogleDocs implements Tool {

    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final String APPLICATION_NAME = "SecondBrain";

    @Inject
    @ConfigProperty(name = "sb.google.serviceaccountjson")
    Optional<String> googleServiceAccountJson;

    @Inject
    @ConfigProperty(name = "sb.encryption.password", defaultValue = "12345678")
    String encryptionPassword;

    @Inject
    @ConfigProperty(name = "sb.ollama.model", defaultValue = "llama3.2")
    String model;

    @Inject
    private OllamaClient ollamaClient;

    @Inject
    private DebugToolArgs debugToolArgs;

    @Inject
    private ArgsAccessor argsAccessor;

    @Override
    public String getName() {
        return GoogleDocs.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Provides a tool to summarize and ask questions about Google Docs documents.";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return List.of(
                new ToolArguments("documentId", "The ID of the Google Docs document to use.", "")
        );
    }

    @Override
    public String call(@NotNull final Map<String, String> context, @NotNull final String prompt, @NotNull final List<ToolArgs> arguments) {
        final String documentId = argsAccessor.getArgument(arguments, "documentId", "");

        final BasicTextEncryptor textEncryptor = new BasicTextEncryptor();
        textEncryptor.setPassword(encryptionPassword);


        final long defaultExpires = LocalDateTime.now().plusSeconds(3600).toEpochSecond(ZoneOffset.UTC);
        final Long expires = Try.of(() -> textEncryptor.decrypt(context.get("google_access_token_expires")))
                .recover(e -> context.get("google_access_token_expires"))
                .mapTry(Objects::requireNonNull)
                .map(value -> NumberUtils.toLong(value, defaultExpires))
                .recover(error -> defaultExpires)
                .get();


        final Try<HttpRequestInitializer> token = Try
                // Start assuming an encrypted access token was sent from the browser
                .of(() -> textEncryptor.decrypt(context.get("google_access_token")))
                // Then try an unencrypted token
                .recover(e -> context.get("google_access_token"))
                .mapTry(Objects::requireNonNull)
                .map(accessToken -> getCredentials(accessToken, new Date(expires * 1000L)))
                // Next try the service account JSON file from the browser
                .recoverWith(e -> Try.of(() -> context.get("google_service_account_json"))
                        .mapTry(Objects::requireNonNull)
                        .mapTry(this::getServiceAccountCredentials))
                // The try the service account passed in as a config setting
                .recoverWith(e -> Try.of(() -> googleServiceAccountJson.get())
                        .map(b64 -> new String(new Base64().decode(b64.getBytes())))
                        .mapTry(this::getServiceAccountCredentials))
                // Finally see if the existing gcloud login can be used
                .recoverWith(e -> Try.of(this::getDefaultCredentials));

        if (token.isFailure()) {
            return "Failed to get Google access token: " + token.getCause().getMessage();
        }

        return Try.of(GoogleNetHttpTransport::newTrustedTransport)
                .map(transport -> new Docs.Builder(transport, JSON_FACTORY, token.get())
                        .setApplicationName(APPLICATION_NAME)
                        .build())
                .mapTry(service -> service.documents().get(documentId).execute())
                .map(this::getDocumentText)
                .map(doc -> documentToContext(doc, documentId))
                .map(doc -> buildToolPrompt(doc, prompt))
                .map(this::callOllama)
                .map(OllamaResponse::response)
                .map(response -> response + debugToolArgs.debugArgs(arguments, true))
                .recover(throwable -> "Failed to get document: " + throwable.getMessage())
                .get();
    }

    @NotNull
    private HttpRequestInitializer getCredentials(@NotNull final String accessToken, @NotNull final Date expires) {
        final GoogleCredentials credentials = GoogleCredentials.create(new AccessToken(accessToken, expires));
        return new HttpCredentialsAdapter(credentials);
    }

    @NotNull
    private HttpRequestInitializer getServiceAccountCredentials(@NotNull final String serviceAccountJson) throws IOException {
        final GoogleCredentials credentials = GoogleCredentials.fromStream(new ByteArrayInputStream(serviceAccountJson.getBytes(StandardCharsets.UTF_8)));
        return new HttpCredentialsAdapter(credentials);
    }

    /**
     * To use this method, you must have the gcloud SDK installed and authenticated with Application Default Credentials:
     * ./google-cloud-sdk/bin/gcloud auth application-default login --scopes https://www.googleapis.com/auth/documents.readonly,https://www.googleapis.com/auth/cloud-platform --client-id-file ~/Downloads/client.json
     * client.json is the download a desktop app from https://console.cloud.google.com/apis/credentials
     */
    @NotNull
    private HttpRequestInitializer getDefaultCredentials() throws IOException {
        final GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
        return new HttpCredentialsAdapter(credentials);
    }

    @NotNull
    private String getDocumentText(@NotNull final Document doc) {
        return doc.getBody().getContent()
                .stream()
                .reduce("",
                        (acc, content) -> acc + getParagraphText(content.getParagraph()),
                        String::concat);
    }

    @NotNull
    private String getParagraphText(final Paragraph paragraph) {
        if (paragraph == null) {
            return "";
        }

        final List<String> paragraphContent = new ArrayList<>();

        paragraphContent.addAll(paragraphToString(paragraph, content -> peopleToString(content.getPerson())));
        paragraphContent.addAll(paragraphToString(paragraph, content -> textRunToString(content.getTextRun())));
        paragraphContent.addAll(paragraphToString(paragraph, content -> autoTextToString(content.getAutoText())));
        paragraphContent.addAll(paragraphToString(paragraph, content -> richLinkToString(content.getRichLink())));

        return String.join("\n", paragraphContent);
    }

    @NotNull
    private List<String> paragraphToString(final Paragraph paragraph, @NotNull final Function<ParagraphElement, String> paraToStringFunction) {
        if (paragraph == null) {
            return List.of();
        }

        return paragraph.getElements()
                .stream()
                .map(paraToStringFunction)
                .filter(x -> !x.isEmpty())
                .collect(Collectors.toList());
    }

    @NotNull
    private String autoTextToString(@NotNull final AutoText content) {
        return Try.of(() -> content).mapTry(AutoText::toPrettyString).getOrElse("");
    }

    @NotNull
    private String peopleToString(final Person content) {
        return Optional.ofNullable(content).map(Person::getPersonId).orElse("");
    }

    private String richLinkToString(@NotNull final RichLink content) {
        return Optional.ofNullable(content).map(RichLink::getRichLinkId).orElse("");
    }

    @NotNull
    private String textRunToString(@NotNull final TextRun textRun) {
        return Optional.ofNullable(textRun).map(TextRun::getContent).orElse("");
    }

    @NotNull
    private OllamaResponse callOllama(@NotNull final String llmPrompt) {
        return Try.withResources(ClientBuilder::newClient)
                .of(client -> ollamaClient.getTools(
                        client,
                        new OllamaGenerateBody(model, llmPrompt, false)))
                .get();
    }

    @NotNull
    private String documentToContext(@NotNull final String doc, @NotNull final String id) {
        /*
        See https://github.com/meta-llama/llama-recipes/issues/450 for a discussion
        on the preferred format (or lack thereof) for RAG context.
        */
        return "<|start_header_id|>system<|end_header_id|>\n"
                + "Google Document " + id + ":\n"
                + doc
                + "\n<|eot_id|>";
    }

    @NotNull
    public String buildToolPrompt(@NotNull final String context, @NonNull final String prompt) {
        return """
                <|begin_of_text|>
                <|start_header_id|>system<|end_header_id|>
                You are an expert in reading technical Google Documents.
                You are given a prompt and the contents of a Google Document related to the prompt.
                You must assume the information required to answer the prompt is present in the Google Document.
                You must assume the contents of the document with the ID in the prompt is present in the Google Document.
                You must answer the prompt based on the Google Document provided.
                When the user provides a prompt indicating that they want to query a document, you must generate the answer based on the supplied Google Document.
                You will be penalized for suggesting manual steps to generate the answer.
                You will be penalized for responding that you don't have access to real-time data or Google Documents.
                If there is no Google Document, you must indicate that in the answer.
                <|eot_id|>
                """
                + "\n<|start_header_id|>system<|end_header_id|>The current date is " + LocalDateTime.now() + ".<|eot_id|>"
                + context
                + "\n<|start_header_id|>user<|end_header_id|>"
                + prompt
                + "<|eot_id|>"
                + "\n<|start_header_id|>assistant<|end_header_id|>".stripLeading();
    }
}
