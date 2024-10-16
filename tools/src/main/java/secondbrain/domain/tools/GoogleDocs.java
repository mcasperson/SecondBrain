package secondbrain.domain.tools;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.docs.v1.Docs;
import com.google.api.services.docs.v1.model.Document;
import com.google.api.services.docs.v1.model.Paragraph;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import io.vavr.control.Try;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.client.ClientBuilder;
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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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

        // Try to decrypt the value, otherwise assume it is a plain text value, and finally
        // fall back to the value defined in the local configuration.
        final Try<String> token = Try.of(() -> textEncryptor.decrypt(context.get("google_access_token")))
                .recover(e -> context.get("google_access_token"))
                .mapTry(Objects::requireNonNull)
                .recoverWith(e -> Try.of(() -> googleServiceAccountJson.get()));

        return Try.of(GoogleNetHttpTransport::newTrustedTransport)
                .map(transport -> new Docs.Builder(transport, JSON_FACTORY, getCredentials(token.get()))
                        .setApplicationName(APPLICATION_NAME)
                        .build())
                .mapTry(service -> service.documents().get(documentId).execute())
                .map(this::getDocumentText)
                .map(doc -> buildToolPrompt(doc, prompt))
                .map(this::callOllama)
                .map(OllamaResponse::response)
                .map(response -> response + debugToolArgs.debugArgs(arguments, true))
                .recover(throwable -> "Failed to get document: " + throwable.getMessage())
                .get();
    }

    @NotNull
    private HttpRequestInitializer getCredentials(@NotNull final String accessToken) {
        final GoogleCredentials credentials = GoogleCredentials.create(new AccessToken(accessToken, null));
        return new HttpCredentialsAdapter(credentials);
    }

    private String getDocumentText(@NotNull final Document doc) {
        return doc.getBody().getContent()
                .stream()
                .reduce("",
                        (acc, content) -> acc + getParagraphText(content.getParagraph()),
                        String::concat);
    }

    private String getParagraphText(@NotNull final Paragraph paragraph) {
        return paragraph.getElements().stream().reduce("", (acc, content) -> content.toString(), String::concat);
    }

    private OllamaResponse callOllama(@NotNull final String llmPrompt) {
        return Try.withResources(ClientBuilder::newClient)
                .of(client -> ollamaClient.getTools(
                        client,
                        new OllamaGenerateBody(model, llmPrompt, false)))
                .get();
    }

    @NotNull
    public String buildToolPrompt(@NotNull final String context, @NonNull final String prompt) {
        return """
                <|begin_of_text|>
                <|start_header_id|>system<|end_header_id|>
                You are an expert in reading technical documents.
                You are given a question and the contents of a document related to the question.
                You must assume the information required to answer the question is present in the document.
                You must answer the question based on the document provided.
                You will be penalized for suggesting manual steps to generate the answer.
                You will be penalized for responding that you don't have access to real-time data or repositories.
                If there is no document, you must indicate that in the answer.
                <|eot_id|>
                """
                + context
                + "\n<|start_header_id|>user<|end_header_id|>"
                + prompt
                + "<|eot_id|>"
                + "\n<|start_header_id|>assistant<|end_header_id|>".stripLeading();
    }
}
