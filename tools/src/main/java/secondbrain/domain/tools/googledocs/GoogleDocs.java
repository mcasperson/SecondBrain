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
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jooq.lambda.Seq;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.concurrency.SemaphoreLender;
import secondbrain.domain.config.LocalConfigKeywordsEntity;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.EnvironmentSettings;
import secondbrain.domain.context.HashMapEnvironmentSettings;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptionhandling.ExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.hooks.HooksContainer;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.processing.DataToRagDoc;
import secondbrain.domain.tooldefs.*;
import secondbrain.domain.tools.googledocs.model.GoogleDoc;
import secondbrain.domain.tools.rating.RatingTool;
import secondbrain.infrastructure.llm.LlmClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;

@ApplicationScoped
public class GoogleDocs implements Tool<Void> {
    public static final String GOOGLE_DOC_FILTER_RATING_META = "FilterRating";
    public static final String GOOGLE_DOC_FILTER_QUESTION_ARG = "contentRatingQuestion";
    public static final String GOOGLE_DOC_FILTER_MINIMUM_RATING_ARG = "contextFilterMinimumRating";
    public static final String GOOGLE_DOC_ID_ARG = "googleDocumentId";
    public static final String GOOGLE_KEYWORD_ARG = "keywords";
    public static final String GOOGLE_KEYWORD_WINDOW_ARG = "keywordWindow";
    public static final String GOOGLE_ENTITY_NAME_CONTEXT_ARG = "entityName";
    public static final String GOOGLE_SUMMARIZE_DOCUMENT_ARG = "summarizeDocument";
    public static final String GOOGLE_SUMMARIZE_DOCUMENT_PROMPT_ARG = "summarizeDocumentPrompt";
    public static final String PREPROCESSOR_HOOKS_CONTEXT_ARG = "preProcessorHooks";
    public static final String PREINITIALIZATION_HOOKS_CONTEXT_ARG = "preInitializationHooks";
    public static final String POSTINFERENCE_HOOKS_CONTEXT_ARG = "postInferenceHooks";
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
    private GoogleDocsConfig config;

    @Inject
    private Encryptor textEncryptor;

    @Inject
    @Preferred
    private LlmClient llmClient;

    @Inject
    private RatingTool ratingTool;

    @Inject
    private ExceptionMapping exceptionMapping;

    @Inject
    private HooksContainer hooksContainer;

    @Inject
    private DataToRagDoc dataToRagDoc;

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

        // Get preinitialization hooks before ragdocs
        final List<RagDocumentContext<Void>> preinitHooks = Seq.seq(hooksContainer.getMatchingPreProcessorHooks(parsedArgs.getPreinitializationHooks()))
                .foldLeft(List.of(), (docs, hook) -> hook.process(getName(), docs));

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

        final Try<List<RagDocumentContext<Void>>> result = SEMAPHORE_LENDER.lendAutoClose()
                .of(s -> Try.of(GoogleNetHttpTransport::newTrustedTransport)
                        .map(transport -> new Docs.Builder(transport, JSON_FACTORY, token.get())
                                .setApplicationName(APPLICATION_NAME)
                                .build())
                        .mapTry(service -> service.documents().get(parsedArgs.getDocumentId()).execute())
                        .map(this::getDocumentText)
                        .map(docText -> new GoogleDoc(parsedArgs.getDocumentId(), "Google Doc " + parsedArgs.getDocumentId(), "https://docs.google.com/document/d/" + parsedArgs.getDocumentId(), docText))
                        .map(docText -> dataToRagDoc.getDocumentContext(docText, getName(), getContextLabel(), parsedArgs))
                        // Get the metadata, which includes a rating against the filter question if present
                        .map(ragDoc -> ragDoc.updateMetadata(getMetadata(environmentSettings, ragDoc, parsedArgs)))
                        // Filter out any documents that don't meet the rating criteria
                        .filter(ragDoc -> contextMeetsRating(ragDoc, parsedArgs))
                        .map(doc -> parsedArgs.getSummarizeDocument()
                                ? doc.updateDocument(getDocumentSummary(doc.document(), environmentSettings, parsedArgs))
                                : doc)
                        .map(RagDocumentContext::getRagDocumentContextVoid)
                        .map(List::of)
                        // This catches the case where the document does not meet the context filter criteria
                        .recover(NoSuchElementException.class, ex -> List.of()))
                .get();

        final List<RagDocumentContext<Void>> ragDocs = exceptionMapping.map(result).get();

        // Combine preinitialization hooks with ragDocs
        final List<RagDocumentContext<Void>> combinedDocs = Stream.concat(preinitHooks.stream(), ragDocs.stream()).toList();

        // Apply preprocessing hooks
        return Seq.seq(hooksContainer.getMatchingPreProcessorHooks(parsedArgs.getPreprocessingHooks()))
                .foldLeft(combinedDocs, (docs, hook) -> hook.process(getName(), docs));
    }

    @Override
    public RagMultiDocumentContext<Void> call(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        final GoogleDocsConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> getContext(environmentSettings, prompt, arguments))
                .map(ragDoc -> mergeContext(prompt, INSTRUCTIONS, ragDoc))
                .map(ragDoc -> llmClient.callWithCache(
                        ragDoc,
                        environmentSettings,
                        getName()));

        final RagMultiDocumentContext<Void> mappedResult = exceptionMapping.map(result).get();

        // Apply postinference hooks
        return Seq.seq(hooksContainer.getMatchingPostInferenceHooks(parsedArgs.getPostInferenceHooks()))
                .foldLeft(mappedResult, (docs, hook) -> hook.process(getName(), docs));
    }

    private RagMultiDocumentContext<Void> mergeContext(final String prompt, final String instructions, final List<RagDocumentContext<Void>> context) {
        return new RagMultiDocumentContext<>(
                prompt,
                instructions,
                context);
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
        final RagDocumentContext<String> context = new RagDocumentContext<>(
                getName(),
                "Document",
                document,
                List.of()
        );

        final RagMultiDocumentContext<String> multiDoc = new RagMultiDocumentContext<>(
                "You are a helpful agent",
                parsedArgs.getDocumentSummaryPrompt(),
                List.of(context)
        );

        return llmClient.callWithCache(
                multiDoc,
                environmentSettings,
                getName()
        ).response();
    }

    private MetaObjectResults getMetadata(
            final Map<String, String> environmentSettings,
            final RagDocumentContext<GoogleDoc> document,
            final GoogleDocsConfig.LocalArguments parsedArgs) {

        final List<MetaObjectResult> metadata = new ArrayList<>();

        // build the environment settings
        final EnvironmentSettings envSettings = new HashMapEnvironmentSettings(environmentSettings)
                .add(RatingTool.RATING_DOCUMENT_CONTEXT_ARG, document.document())
                .addToolCall(getName() + "[" + document.id() + "]");

        if (StringUtils.isNotBlank(parsedArgs.getContextFilterQuestion())) {
            final int filterRating = Try.of(() -> ratingTool.call(envSettings, parsedArgs.getContextFilterQuestion(), List.of()).getResponse())
                    .map(rating -> org.apache.commons.lang3.math.NumberUtils.toInt(rating.trim(), 0))
                    // Ratings are provided on a best effort basis, so we ignore any failures
                    .recover(ex -> 10)
                    .get();

            metadata.add(new MetaObjectResult(GOOGLE_DOC_FILTER_RATING_META, filterRating, document.id(), getName()));
        }

        return new MetaObjectResults(
                metadata,
                "GoogleDoc-" + document.id() + ".json",
                document.id());
    }

    private boolean contextMeetsRating(
            final RagDocumentContext<GoogleDoc> document,
            final GoogleDocsConfig.LocalArguments parsedArgs) {
        // If there was no filter question, then return the whole list
        if (StringUtils.isBlank(parsedArgs.getContextFilterQuestion())) {
            return true;
        }

        return Objects.requireNonNullElse(document.metadata(), new MetaObjectResults())
                .getIntValueByName(GOOGLE_DOC_FILTER_RATING_META, 10)
                >= parsedArgs.getContextFilterMinimumRating();
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
    @ConfigProperty(name = "sb.google.contextFilterQuestion")
    private Optional<String> configContextFilterQuestion;

    @Inject
    @ConfigProperty(name = "sb.google.contextFilterMinimumRating")
    private Optional<String> configContextFilterMinimumRating;

    @Inject
    @ConfigProperty(name = "sb.google.preprocessorHooks", defaultValue = "")
    private Optional<String> configPreprocessorHooks;

    @Inject
    @ConfigProperty(name = "sb.google.preinitializationHooks", defaultValue = "")
    private Optional<String> configPreinitializationHooks;

    @Inject
    @ConfigProperty(name = "sb.google.postinferenceHooks", defaultValue = "")
    private Optional<String> configPostInferenceHooks;

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

    public Optional<String> getConfigContextFilterQuestion() {
        return configContextFilterQuestion;
    }

    public Optional<String> getConfigContextFilterMinimumRating() {
        return configContextFilterMinimumRating;
    }

    public Optional<String> getConfigPreprocessorHooks() {
        return configPreprocessorHooks;
    }

    public Optional<String> getConfigPreinitializationHooks() {
        return configPreinitializationHooks;
    }

    public Optional<String> getConfigPostInferenceHooks() {
        return configPostInferenceHooks;
    }

    public class LocalArguments implements LocalConfigKeywordsEntity {
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
                    GoogleDocs.GOOGLE_DOC_ID_ARG,
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
                            GoogleDocs.GOOGLE_KEYWORD_ARG,
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
                    GoogleDocs.GOOGLE_KEYWORD_WINDOW_ARG,
                    Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH + "");

            return org.apache.commons.lang.math.NumberUtils.toInt(argument.value(), Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH);
        }

        public boolean getSummarizeDocument() {
            final String value = getArgsAccessor().getArgument(
                    getConfigSummarizeDocument()::get,
                    arguments,
                    context,
                    GoogleDocs.GOOGLE_SUMMARIZE_DOCUMENT_ARG,
                    GoogleDocs.GOOGLE_SUMMARIZE_DOCUMENT_ARG,
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
                            GoogleDocs.GOOGLE_SUMMARIZE_DOCUMENT_PROMPT_ARG,
                            "Summarise the document in three paragraphs")
                    .value();
        }

        public String getContextFilterQuestion() {
            return getArgsAccessor().getArgument(
                            getConfigContextFilterQuestion()::get,
                            arguments,
                            context,
                            GoogleDocs.GOOGLE_DOC_FILTER_QUESTION_ARG,
                            GoogleDocs.GOOGLE_DOC_FILTER_QUESTION_ARG,
                            "")
                    .value();
        }

        public Integer getContextFilterMinimumRating() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigContextFilterMinimumRating()::get,
                    arguments,
                    context,
                    GoogleDocs.GOOGLE_DOC_FILTER_MINIMUM_RATING_ARG,
                    GoogleDocs.GOOGLE_DOC_FILTER_MINIMUM_RATING_ARG,
                    "0");

            return org.apache.commons.lang.math.NumberUtils.toInt(argument.value(), 0);
        }

        public String getPreprocessingHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPreprocessorHooks()::get,
                    arguments,
                    context,
                    GoogleDocs.PREPROCESSOR_HOOKS_CONTEXT_ARG,
                    GoogleDocs.PREPROCESSOR_HOOKS_CONTEXT_ARG,
                    "").value();
        }

        public String getPreinitializationHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPreinitializationHooks()::get,
                    arguments,
                    context,
                    GoogleDocs.PREINITIALIZATION_HOOKS_CONTEXT_ARG,
                    GoogleDocs.PREINITIALIZATION_HOOKS_CONTEXT_ARG,
                    "").value();
        }

        public String getPostInferenceHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPostInferenceHooks()::get,
                    arguments,
                    context,
                    GoogleDocs.POSTINFERENCE_HOOKS_CONTEXT_ARG,
                    GoogleDocs.POSTINFERENCE_HOOKS_CONTEXT_ARG,
                    "").value();
        }
    }
}
