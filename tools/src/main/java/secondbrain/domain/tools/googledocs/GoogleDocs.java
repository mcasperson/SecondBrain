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
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.config.ModelConfig;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.context.SentenceSplitter;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptions.FailedTool;
import secondbrain.domain.limit.DocumentTrimmer;
import secondbrain.domain.prompt.PromptBuilderSelector;
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

@ApplicationScoped
public class GoogleDocs implements Tool<Void> {
    public static final String GOOGLE_DOC_ID_ARG = "googleDocumentId";
    public static final String GOOGLE_KEYWORD_ARG = "keywords";
    public static final String GOOGLE_DISABLE_LINKS_ARG = "disableLinks";

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
    private ModelConfig modelConfig;

    @Inject
    private Arguments parsedArgs;

    @Inject
    private DocumentTrimmer documentTrimmer;

    @Inject
    private Encryptor textEncryptor;

    @Inject
    private OllamaClient ollamaClient;

    @Inject
    private SentenceSplitter sentenceSplitter;

    @Inject
    private SentenceVectorizer sentenceVectorizer;

    @Inject
    private PromptBuilderSelector promptBuilderSelector;

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
                new ToolArguments(GOOGLE_DOC_ID_ARG, "The ID of the Google Docs document to use.", ""),
                new ToolArguments(GOOGLE_KEYWORD_ARG, "An optional list of keywords used to trim the document", "")
        );
    }

    @Override
    public String getContextLabel() {
        return "Google Document";
    }

    @Override
    public List<RagDocumentContext<Void>> getContext(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        parsedArgs.setInputs(arguments, prompt, context);

        final long defaultExpires = LocalDateTime.now(ZoneId.systemDefault()).plusSeconds(3600).toEpochSecond(ZoneOffset.UTC);
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
                .recoverWith(e -> Try.of(() -> parsedArgs.getGoogleServiceAccountJson())
                        .map(b64 -> new String(new Base64().decode(b64.getBytes(UTF_8)), UTF_8))
                        .mapTry(this::getServiceAccountCredentials))
                // Finally see if the existing gcloud login can be used
                .recoverWith(e -> Try.of(this::getDefaultCredentials));

        if (token.isFailure()) {
            throw new FailedTool("Failed to get Google access token: " + token.getCause().getMessage());
        }

        return Try.of(GoogleNetHttpTransport::newTrustedTransport)
                .map(transport -> new Docs.Builder(transport, JSON_FACTORY, token.get())
                        .setApplicationName(APPLICATION_NAME)
                        .build())
                .mapTry(service -> service.documents().get(parsedArgs.getDocumentId()).execute())
                .map(this::getDocumentText)
                .map(document -> documentTrimmer.trimDocument(
                        document, parsedArgs.getKeywords(), Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH))
                .map(document -> getDocumentContext(document, parsedArgs.getDocumentId()))
                .map(List::of)
                .get();
    }

    @Override
    public RagMultiDocumentContext<Void> call(final Map<String, String> context, final String prompt, final List<ToolArgs> arguments) {
        parsedArgs.setInputs(arguments, prompt, context);

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> getContext(context, prompt, arguments))
                .map(ragDoc -> mergeContext(ragDoc, modelConfig.getCalculatedModel(context)))
                .map(ragContext -> ragContext.updateDocument(promptBuilderSelector
                        .getPromptBuilder(modelConfig.getCalculatedModel(context))
                        .buildFinalPrompt(
                                INSTRUCTIONS,
                                // I've opted to get the end of the document if it is larger than the context window.
                                // The end of the document is typically given more weight by LLMs, and so any long
                                // document being processed should place the most relevant content twoards the end.
                                ragContext.getDocumentRight(modelConfig.getCalculatedContextWindowChars()),
                                prompt)))
                .map(ragDoc -> ollamaClient.callOllamaWithCache(
                        ragDoc,
                        modelConfig.getCalculatedModel(context),
                        GoogleDocs.class.getSimpleName(),
                        modelConfig.getCalculatedContextWindow()));

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(API.Case(API.$(), ex -> new FailedTool(getName() + " failed to call Ollama", ex)))
                .get();
    }

    private RagMultiDocumentContext<Void> mergeContext(final List<RagDocumentContext<Void>> context, final String customModel) {
        return new RagMultiDocumentContext<>(
                context.stream()
                        .map(ragDoc -> promptBuilderSelector
                                .getPromptBuilder(customModel)
                                .buildContextPrompt(
                                        getContextLabel() + " " + ragDoc.id(),
                                        ragDoc.document()))
                        .collect(Collectors.joining("\n")),
                context);
    }


    private RagDocumentContext<Void> getDocumentContext(final String document, final String documentId) {
        if (parsedArgs.getDisableLinks()) {
            return new RagDocumentContext<>(getContextLabel(), document, List.of());
        }

        return Try.of(() -> sentenceSplitter.splitDocument(document, 10))
                .map(sentences -> new RagDocumentContext<Void>(
                        getContextLabel(),
                        document,
                        sentences.stream()
                                .map(sentenceVectorizer::vectorize)
                                .collect(Collectors.toList()),
                        documentId,
                        null,
                        idToLink(documentId)))
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                // If we can't vectorize the sentences, just return the document
                .recover(e -> new RagDocumentContext<>(getContextLabel(), document, List.of(), documentId, null, idToLink(documentId)))
                .get();
    }

    private String idToLink(final String documentId) {
        return "[Document](https://docs.google.com/document/d/" + documentId + ")";
    }

    @SuppressWarnings("JavaUtilDate")
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


}

@ApplicationScoped
class Arguments {
    @Inject
    @ConfigProperty(name = "sb.google.serviceaccountjson")
    private Optional<String> googleServiceAccountJson;

    @Inject
    @ConfigProperty(name = "sb.google.disablelinks")
    private Optional<String> disableLinks;

    @Inject
    private ArgsAccessor argsAccessor;

    private List<ToolArgs> arguments;

    private String prompt;

    private Map<String, String> context;

    @Inject
    private ValidateString validateString;

    @Inject
    @ConfigProperty(name = "sb.google.doc")
    private Optional<String> googleDoc;

    @Inject
    @ConfigProperty(name = "sb.google.keywords")
    private Optional<String> googleKeywords;

    public void setInputs(final List<ToolArgs> arguments, final String prompt, final Map<String, String> context) {
        this.arguments = arguments;
        this.prompt = prompt;
        this.context = context;
    }

    public String getDocumentId() {
        return Try.of(googleDoc::get)
                .mapTry(validateString::throwIfEmpty)
                .recover(e -> argsAccessor.getArgument(arguments, GoogleDocs.GOOGLE_DOC_ID_ARG, ""))
                .mapTry(validateString::throwIfEmpty)
                .recover(e -> context.get("google_document_id"))
                .mapTry(validateString::throwIfEmpty)
                .recover(e -> "")
                .get();
    }

    public List<String> getKeywords() {
        return Try.of(googleKeywords::get)
                .mapTry(validateString::throwIfEmpty)
                .recover(e -> argsAccessor.getArgument(arguments, GoogleDocs.GOOGLE_KEYWORD_ARG, ""))
                .mapTry(validateString::throwIfEmpty)
                .recover(e -> context.get("google_keywords"))
                .mapTry(validateString::throwIfEmpty)
                .recover(e -> "")
                .map(k -> List.of(k.split(",")))
                .get();
    }


    public String getGoogleServiceAccountJson() {
        return googleServiceAccountJson.get();
    }

    public boolean getDisableLinks() {
        final String stringValue = argsAccessor.getArgument(
                disableLinks::get,
                arguments,
                context,
                GoogleDocs.GOOGLE_DISABLE_LINKS_ARG,
                "google_disablelinks",
                "false");

        return BooleanUtils.toBoolean(stringValue);
    }
}
