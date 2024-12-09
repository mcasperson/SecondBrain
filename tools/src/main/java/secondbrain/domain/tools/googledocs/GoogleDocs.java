package secondbrain.domain.tools.googledocs;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.docs.v1.Docs;
import com.google.api.services.docs.v1.model.*;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import io.smallrye.common.annotation.Identifier;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.context.SentenceSplitter;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.debug.DebugToolArgs;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptions.FailedTool;
import secondbrain.domain.limit.DocumentTrimmer;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.sanitize.SanitizeArgument;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.ollama.OllamaClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

@Dependent
public class GoogleDocs implements Tool {

    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final String APPLICATION_NAME = "SecondBrain";
    private static final String INSTRUCTIONS = """
            You are an expert in reading technical Google Documents.
            You are given a prompt and the contents of a Google Document related to the prompt.
            You must assume the information required to answer the prompt is present in the Google Document.
            You must assume the contents of the document with the ID in the prompt is present in the Google Document.
            You must answer the prompt based on the Google Document provided.
            When the user provides a prompt indicating that they want to query a document, you must generate the answer based on the supplied Google Document.
            You will be penalized for suggesting manual steps to generate the answer.
            You will be penalized for responding that you don't have access to real-time data or Google Documents.
            If there is no Google Document, you must indicate that in the answer.
            """;

    @Inject
    @ConfigProperty(name = "sb.google.serviceaccountjson")
    Optional<String> googleServiceAccountJson;

    @Inject
    @ConfigProperty(name = "sb.ollama.model", defaultValue = "llama3.2")
    String model;

    @Inject
    @ConfigProperty(name = "sb.ollama.contentlength", defaultValue = "" + Constants.MAX_CONTEXT_LENGTH)
    String limit;

    @Inject
    @Identifier("sanitizeList")
    private SanitizeArgument sanitizeList;

    @Inject
    private DocumentTrimmer documentTrimmer;

    @Inject
    private Encryptor textEncryptor;

    @Inject
    private OllamaClient ollamaClient;

    @Inject
    private DebugToolArgs debugToolArgs;

    @Inject
    private ArgsAccessor argsAccessor;

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
        return GoogleDocs.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Queries a Google Docs document";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return List.of(
                new ToolArguments("documentId", "The ID of the Google Docs document to use.", ""),
                new ToolArguments("keywords", "An optional list of keywords used to trim the document", "")
        );
    }

    @Override
    public RagMultiDocumentContext<?> call(final Map<String, String> context, final String prompt, final List<ToolArgs> arguments) {
        final Arguments parsedArgs = Arguments.fromToolArgs(arguments, argsAccessor, sanitizeList, prompt);

        final long defaultExpires = LocalDateTime.now(ZoneId.systemDefault()).plusSeconds(3600).toEpochSecond(ZoneOffset.UTC);
        final Long expires = Try.of(() -> textEncryptor.decrypt(context.get("google_access_token_expires")))
                .recover(e -> context.get("google_access_token_expires"))
                .mapTry(Objects::requireNonNull)
                .map(value -> NumberUtils.toLong(value, defaultExpires))
                .recover(error -> defaultExpires)
                .get();

        final String customModel = Try.of(() -> context.get("custom_model"))
                .mapTry(validateString::throwIfEmpty)
                .recover(e -> model)
                .get();

        final boolean argumentDebugging = Try.of(() -> context.get("argument_debugging"))
                .mapTry(Boolean::parseBoolean)
                .recover(e -> false)
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
                        .map(b64 -> new String(new Base64().decode(b64.getBytes(UTF_8)), UTF_8))
                        .mapTry(this::getServiceAccountCredentials))
                // Finally see if the existing gcloud login can be used
                .recoverWith(e -> Try.of(this::getDefaultCredentials));

        if (token.isFailure()) {
            throw new FailedTool("Failed to get Google access token: " + token.getCause().getMessage());
        }

        final Try<RagMultiDocumentContext<Void>> result = Try.of(GoogleNetHttpTransport::newTrustedTransport)
                .map(transport -> new Docs.Builder(transport, JSON_FACTORY, token.get())
                        .setApplicationName(APPLICATION_NAME)
                        .build())
                .mapTry(service -> service.documents().get(parsedArgs.documentId()).execute())
                .map(this::getDocumentText)
                .map(document -> documentTrimmer.trimDocument(
                        document, parsedArgs.keywords(), Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH))
                .map(document -> getDocumentContext(document, parsedArgs.documentId()))
                .map(doc -> doc.updateDocument(promptBuilderSelector
                        .getPromptBuilder(customModel)
                        .buildContextPrompt("Google Document " + parsedArgs.documentId(), doc.document())))
                .map(ragDoc -> new RagMultiDocumentContext<>(ragDoc.document(), List.of(ragDoc)))
                .map(ragContext -> ragContext.updateDocument(promptBuilderSelector
                        .getPromptBuilder(customModel)
                        .buildFinalPrompt(
                                INSTRUCTIONS,
                                // I've opted to get the end of the document if it is larger than the context window.
                                // The end of the document is typically given more weight by LLMs, and so any long
                                // document being processed should place the most relevant content twoards the end.
                                ragContext.getDocumentRight(NumberUtils.toInt(limit, Constants.MAX_CONTEXT_LENGTH)),
                                prompt)))
                .map(ragDoc -> ollamaClient.callOllama(ragDoc, customModel));

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(API.Case(API.$(), ex -> new FailedTool("Failed to call Ollama", ex)))
                .get();
    }


    private RagDocumentContext<Void> getDocumentContext(final String document, final String documentId) {
        return Try.of(() -> sentenceSplitter.splitDocument(document, 10))
                .map(sentences -> new RagDocumentContext<Void>(
                        document,
                        sentences.stream()
                                .map(sentenceVectorizer::vectorize)
                                .collect(Collectors.toList()),
                        null,
                        null,
                        "[Document](https://docs.google.com/document/d/" + documentId + ")"))
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                // If we can't vectorize the sentences, just return the document
                .recover(e -> new RagDocumentContext<>(document, List.of()))
                .get();
    }

    private HttpRequestInitializer getCredentials(final String accessToken, final Date expires) {
        final GoogleCredentials credentials = GoogleCredentials.create(new AccessToken(accessToken, expires));
        return new HttpCredentialsAdapter(credentials);
    }


    private HttpRequestInitializer getServiceAccountCredentials(final String serviceAccountJson) throws IOException {
        final GoogleCredentials credentials = GoogleCredentials.fromStream(new ByteArrayInputStream(serviceAccountJson.getBytes(UTF_8)));
        return new HttpCredentialsAdapter(credentials);
    }

    /**
     * To use this method, you must have the gcloud SDK installed and authenticated with Application Default Credentials:
     * ./google-cloud-sdk/bin/gcloud auth application-default login --scopes https://www.googleapis.com/auth/documents.readonly,https://www.googleapis.com/auth/cloud-platform --client-id-file ~/Downloads/client.json
     * client.json is the download a desktop app from https://console.cloud.google.com/apis/credentials
     */
    private HttpRequestInitializer getDefaultCredentials() throws IOException {
        final GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
        return new HttpCredentialsAdapter(credentials);
    }


    private String getDocumentText(final Document doc) {
        return doc.getBody().getContent()
                .stream()
                .reduce("",
                        (acc, content) -> acc + getParagraphText(content.getParagraph()),
                        String::concat);
    }


    private String getParagraphText(@Nullable final Paragraph paragraph) {
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


    private List<String> paragraphToString(@Nullable final Paragraph paragraph, final Function<ParagraphElement, String> paraToStringFunction) {
        if (paragraph == null) {
            return List.of();
        }

        return paragraph.getElements()
                .stream()
                .map(paraToStringFunction)
                .filter(x -> !x.isEmpty())
                .collect(Collectors.toList());
    }

    private String autoTextToString(final AutoText content) {
        return Try.of(() -> content).mapTry(AutoText::toPrettyString).getOrElse("");
    }

    private String peopleToString(final Person content) {
        return Optional.ofNullable(content).map(Person::getPersonId).orElse("");
    }

    private String richLinkToString(final RichLink content) {
        return Optional.ofNullable(content).map(RichLink::getRichLinkId).orElse("");
    }

    private String textRunToString(final TextRun textRun) {
        return Optional.ofNullable(textRun).map(TextRun::getContent).orElse("");
    }

    /**
     * A record that hold the arguments used by the tool. This centralizes the logic for extracting, validating, and sanitizing
     * the various inputs to the tool.
     */
    record Arguments(String documentId, List<String> keywords) {
        public static Arguments fromToolArgs(final List<ToolArgs> arguments, final ArgsAccessor argsAccessor, final SanitizeArgument sanitizeList, final String prompt) {
            final String documentId = argsAccessor.getArgument(arguments, "documentId", "");
            final List<String> keywords = List.of(sanitizeList.sanitize(
                    argsAccessor.getArgument(arguments, "keywords", ""),
                    prompt).split(","));

            return new Arguments(documentId, keywords);
        }
    }
}
