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
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.concurrency.SemaphoreLender;
import secondbrain.domain.config.ModelConfig;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.context.SentenceSplitter;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.FailedOllama;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.limit.DocumentTrimmer;
import secondbrain.domain.limit.TrimResult;
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
import java.util.*;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.google.common.base.Predicates.instanceOf;
import static java.nio.charset.StandardCharsets.UTF_8;

@ApplicationScoped
public class GoogleDocs implements Tool<Void> {
    public static final String GOOGLE_DOC_ID_ARG = "googleDocumentId";
    public static final String GOOGLE_KEYWORD_ARG = "keywords";
    public static final String GOOGLE_KEYWORD_WINDOW_ARG = "keywordWindow";
    public static final String GOOGLE_ENTITY_NAME_CONTEXT_ARG = "entityName";
    public static final String GOOGLE_SUMMARIZE_DOCUMENT_ARG = "summarizeDocument";
    public static final String GOOGLE_SUMMARIZE_DOCUMENT_PROMPT_ARG = "summarizeDocumentPrompt";
    private static final SemaphoreLender SEMAPHORE_LENDER = new SemaphoreLender(Constants.DEFAULT_SEMAPHORE_COUNT);
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
    private GoogleDocsConfig config;

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
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final GoogleDocsConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final long defaultExpires = LocalDateTime.now(ZoneId.systemDefault()).plusSeconds(3600).toEpochSecond(ZoneOffset.UTC);
        final Long expires = Try.of(() -> textEncryptor.decrypt(environmentSettings.get("google_access_token_expires")))
                .recover(e -> environmentSettings.get("google_access_token_expires"))
                .mapTry(Objects::requireNonNull)
                .map(value -> NumberUtils.toLong(value, defaultExpires))
                .recover(error -> defaultExpires)
                .get();

        final Try<HttpRequestInitializer> token = Try
                // Start assuming an encrypted access token was sent from the browser
                .of(() -> textEncryptor.decrypt(environmentSettings.get("google_access_token")))
                // Then try an unencrypted token
                .recover(e -> environmentSettings.get("google_access_token"))
                .mapTry(Objects::requireNonNull)
                .map(accessToken -> getCredentials(accessToken, new Date(expires * 1000L)))
                // Next try the service account JSON file from the browser
                .recoverWith(e -> Try.of(() -> environmentSettings.get("google_service_account_json"))
                        .mapTry(Objects::requireNonNull)
                        .mapTry(this::getServiceAccountCredentials))
                // The try the service account passed in as a config setting
                .recoverWith(e -> Try.of(parsedArgs::getGoogleServiceAccountJson)
                        .map(b64 -> new String(new Base64().decode(b64.getBytes(UTF_8)), UTF_8))
                        .mapTry(this::getServiceAccountCredentials))
                // Finally see if the existing gcloud login can be used
                .recoverWith(e -> Try.of(this::getDefaultCredentials));

        if (token.isFailure()) {
            throw new InternalFailure("Failed to get Google access token: " + token.getCause().getMessage());
        }

        final Try<List<RagDocumentContext<Void>>> result = Try.withResources(SEMAPHORE_LENDER::lend)
                .of(s -> Try.of(GoogleNetHttpTransport::newTrustedTransport)
                        .map(transport -> new Docs.Builder(transport, JSON_FACTORY, token.get())
                                .setApplicationName(APPLICATION_NAME)
                                .build())
                        .mapTry(service -> service.documents().get(parsedArgs.getDocumentId()).execute())
                        .map(this::getDocumentText)
                        .map(document -> documentTrimmer.trimDocumentToKeywords(
                                document, parsedArgs.getKeywords(), parsedArgs.getKeywordWindow()))
                        .map(trimResult -> validateString.throwIfEmpty(trimResult, TrimResult::document))
                        .map(trimResult -> getDocumentContext(trimResult, parsedArgs))
                        .map(doc -> parsedArgs.getSummarizeDocument()
                                ? doc.updateDocument(getDocumentSummary(doc.document(), environmentSettings, parsedArgs))
                                : doc)
                        .map(List::of))
                .get();

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(
                        API.Case(API.$(instanceOf(EmptyString.class)), throwable -> new InternalFailure("The Google document " + parsedArgs.getDocumentId() + " is empty or had no keyword matches", throwable)),
                        API.Case(API.$(instanceOf(InternalFailure.class)), throwable -> throwable),
                        API.Case(API.$(), ex -> new ExternalFailure(getName() + " failed to call Google API", ex)))
                .get();
    }

    @Override
    public RagMultiDocumentContext<Void> call(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> getContext(environmentSettings, prompt, arguments))
                .map(ragDoc -> mergeContext(ragDoc, modelConfig.getCalculatedModel(environmentSettings)))
                .map(ragContext -> ragContext.updateDocument(promptBuilderSelector
                        .getPromptBuilder(modelConfig.getCalculatedModel(environmentSettings))
                        .buildFinalPrompt(
                                INSTRUCTIONS,
                                // I've opted to get the end of the document if it is larger than the context window.
                                // The end of the document is typically given more weight by LLMs, and so any long
                                // document being processed should place the most relevant content towards the end.
                                ragContext.getDocumentRight(modelConfig.getCalculatedContextWindowChars(environmentSettings)),
                                prompt)))
                .map(ragDoc -> ollamaClient.callOllamaWithCache(
                        ragDoc,
                        modelConfig.getCalculatedModel(environmentSettings),
                        GoogleDocs.class.getSimpleName(),
                        modelConfig.getCalculatedContextWindow(environmentSettings)));

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(
                        API.Case(API.$(instanceOf(EmptyString.class)), throwable -> new InternalFailure("The Google document is empty", throwable)),
                        API.Case(API.$(instanceOf(InternalFailure.class)), throwable -> throwable),
                        API.Case(API.$(instanceOf(FailedOllama.class)), throwable -> new InternalFailure(throwable.getMessage(), throwable)),
                        API.Case(API.$(), ex -> new ExternalFailure(getName() + " failed to call Ollama", ex)))
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


    private RagDocumentContext<Void> getDocumentContext(final TrimResult trimResult, final GoogleDocsConfig.LocalArguments parsedArgs) {
        return Try.of(() -> sentenceSplitter.splitDocument(trimResult.document(), 10))
                .map(sentences -> new RagDocumentContext<Void>(
                        getContextLabel(),
                        trimResult.document(),
                        sentenceVectorizer.vectorize(sentences),
                        parsedArgs.getDocumentId(),
                        null,
                        idToLink(parsedArgs.getDocumentId()),
                        trimResult.keywordMatches()))
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                .get();
    }

    private String idToLink(final String documentId) {
        return "[Document " + documentId + "](https://docs.google.com/document/d/" + documentId + ")";
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

    private String getDocumentSummary(final String document, final Map<String, String> environmentSettings, final GoogleDocsConfig.LocalArguments parsedArgs) {
        final String ticketContext = promptBuilderSelector
                .getPromptBuilder(modelConfig.getCalculatedModel(environmentSettings))
                .buildContextPrompt(this.getContextLabel(), document);

        final String prompt = promptBuilderSelector
                .getPromptBuilder(modelConfig.getCalculatedModel(environmentSettings))
                .buildFinalPrompt("You are a helpful agent", ticketContext, parsedArgs.getDocumentSummaryPrompt());

        return ollamaClient.callOllamaWithCache(
                new RagMultiDocumentContext<>(prompt),
                modelConfig.getCalculatedModel(environmentSettings),
                getName(),
                modelConfig.getCalculatedContextWindow(environmentSettings)
        ).combinedDocument();
    }
}

@ApplicationScoped
class GoogleDocsConfig {
    @Inject
    @ConfigProperty(name = "sb.google.serviceaccountjson")
    private Optional<String> configGoogleServiceAccountJson;

    @Inject
    @ConfigProperty(name = "sb.google.doc")
    private Optional<String> configGoogleDoc;

    @Inject
    @ConfigProperty(name = "sb.google.keywords")
    private Optional<String> configGoogleKeywords;

    @Inject
    @ConfigProperty(name = "sb.google.keywordwindow")
    private Optional<String> configKeywordWindow;

    @Inject
    @ConfigProperty(name = "sb.google.summarizedocument", defaultValue = "false")
    private Optional<String> configSummarizeDocument;

    @Inject
    @ConfigProperty(name = "sb.google.summarizedocumentprompt")
    private Optional<String> configSummarizeDocumentPrompt;

    @Inject
    private ArgsAccessor argsAccessor;

    public Optional<String> getConfigGoogleServiceAccountJson() {
        return configGoogleServiceAccountJson;
    }

    public Optional<String> getConfigGoogleDoc() {
        return configGoogleDoc;
    }

    public Optional<String> getConfigGoogleKeywords() {
        return configGoogleKeywords;
    }

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    public Optional<String> getConfigKeywordWindow() {
        return configKeywordWindow;
    }

    public Optional<String> getConfigSummarizeDocument() {
        return configSummarizeDocument;
    }

    public Optional<String> getConfigSummarizeDocumentPrompt() {
        return configSummarizeDocumentPrompt;
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

        public String getDocumentId() {
            final String documentId = getArgsAccessor().getArgument(
                    getConfigGoogleDoc()::get,
                    arguments,
                    context,
                    GoogleDocs.GOOGLE_DOC_ID_ARG,
                    "google_document_id",
                    "").value();

            if (StringUtils.isBlank(documentId)) {
                throw new InternalFailure("Google document ID is required");
            }

            return documentId;
        }

        public List<String> getKeywords() {
            return getArgsAccessor().getArgumentList(
                            getConfigGoogleKeywords()::get,
                            arguments,
                            context,
                            GoogleDocs.GOOGLE_KEYWORD_ARG,
                            "google_keywords",
                            "")
                    .stream()
                    .map(Argument::value)
                    .toList();
        }

        public String getGoogleServiceAccountJson() {
            return getConfigGoogleServiceAccountJson().get();
        }

        public String getEntity() {
            return getArgsAccessor().getArgument(
                    null,
                    null,
                    context,
                    null,
                    GoogleDocs.GOOGLE_ENTITY_NAME_CONTEXT_ARG,
                    "").value();
        }

        public int getKeywordWindow() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigKeywordWindow()::get,
                    arguments,
                    context,
                    GoogleDocs.GOOGLE_KEYWORD_WINDOW_ARG,
                    "google_keyword_window",
                    Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH + "");

            return org.apache.commons.lang.math.NumberUtils.toInt(argument.value(), Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH);
        }

        public boolean getSummarizeDocument() {
            final String value = getArgsAccessor().getArgument(
                    getConfigSummarizeDocument()::get,
                    arguments,
                    context,
                    GoogleDocs.GOOGLE_SUMMARIZE_DOCUMENT_ARG,
                    "google_summarizedocument",
                    "").value();

            return BooleanUtils.toBoolean(value);
        }

        public String getDocumentSummaryPrompt() {
            return getArgsAccessor()
                    .getArgument(
                            getConfigSummarizeDocumentPrompt()::get,
                            arguments,
                            context,
                            GoogleDocs.GOOGLE_SUMMARIZE_DOCUMENT_PROMPT_ARG,
                            "google_summarizedocument_prompt",
                            "Summarise the document in three paragraphs")
                    .value();
        }
    }
}
