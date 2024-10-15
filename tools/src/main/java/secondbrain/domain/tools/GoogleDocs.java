package secondbrain.domain.tools;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.docs.v1.Docs;
import com.google.api.services.docs.v1.model.Document;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import io.vavr.control.Try;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jasypt.util.text.BasicTextEncryptor;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.debug.DebugToolArgs;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.infrastructure.ollama.OllamaClient;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
        return "Allows prompts to be answered against the content of a Google Docs document.";
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
        final Try<String> token = Try.of(() -> textEncryptor.decrypt(context.get("github_access_token")))
                .recover(e -> context.get("github_access_token"))
                .mapTry(Objects::requireNonNull)
                .recoverWith(e -> Try.of(() -> googleServiceAccountJson.get()));

        final Try<Document> doc = Try.of(GoogleNetHttpTransport::newTrustedTransport)
                .map(transport -> new Docs.Builder(transport, JSON_FACTORY, getCredentials(token.get()))
                        .setApplicationName(APPLICATION_NAME)
                        .build())
                .mapTry(service -> service.documents().get(documentId).execute());

        return doc.map(Document::getTitle).recover(error -> "Failed").get();
    }

    @NotNull
    private HttpRequestInitializer getCredentials(@NotNull final String accessToken) {
        final GoogleCredentials credentials = GoogleCredentials.create(new AccessToken(accessToken, null));
        return new HttpCredentialsAdapter(credentials);
    }
}
