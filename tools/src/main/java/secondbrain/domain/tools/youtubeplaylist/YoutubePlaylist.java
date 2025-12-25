package secondbrain.domain.tools.youtubeplaylist;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jooq.lambda.Seq;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.*;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptionhandling.ExceptionMapping;
import secondbrain.domain.exceptions.InsufficientContext;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.hooks.HooksContainer;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.limit.DocumentTrimmer;
import secondbrain.domain.limit.TrimResult;
import secondbrain.domain.tooldefs.*;
import secondbrain.domain.tools.rating.RatingTool;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.youtube.YoutubeClient;
import secondbrain.infrastructure.youtube.api.YoutubePlaylistsItem;
import secondbrain.infrastructure.youtube.api.YoutubeSearchItem;
import secondbrain.infrastructure.youtube.model.YoutubeVideo;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Stream;

@ApplicationScoped
public class YoutubePlaylist implements Tool<YoutubeVideo> {
    public static final String YOUTUBE_FILTER_RATING_META = "FilterRating";
    public static final String YOUTUBE_API_KEY = "apiKey";
    public static final String YOUTUBE_PLAYLIST_ID_ARG = "playlistId";
    public static final String YOUTUBE_CHANNEL_ID_ARG = "channelId";
    public static final String YOUTUBE_QUERY_ARG = "query";
    public static final String YOUTUBE_KEYWORD_ARG = "keywords";
    public static final String YOUTUBE_KEYWORD_WINDOW_ARG = "keywordWindow";
    public static final String YOUTUBE_FILTER_MINIMUM_RATING_ARG = "filterMinimumRating";
    public static final String YOUTUBE_FILTER_QUESTION_ARG = "filterQuestion";
    public static final String YOUTUBE_DEFAULT_RATING_ARG = "defaultRating";
    public static final String YOUTUBE_SUMMARIZE_TRANSCRIPT_ARG = "summarizeTranscript";
    public static final String YOUTUBE_SUMMARIZE_TRANSCRIPT_PROMPT_ARG = "summarizeTranscriptPrompt";
    public static final String PREPROCESSOR_HOOKS_CONTEXT_ARG = "preProcessorHooks";
    public static final String PREINITIALIZATION_HOOKS_CONTEXT_ARG = "preInitializationHooks";
    public static final String POSTINFERENCE_HOOKS_CONTEXT_ARG = "postInferenceHooks";
    public static final String YOUTUBE_MAX_VIDEOS_ARG = "maxVideos";

    private static final String INSTRUCTIONS = """
            You are a helpful assistant.
            You are given a list of Youtube video transcripts.
            You must assume the provided content is a video transcript.
            Assume the information required to answer the question is present in the transcripts.
            Answer the question based on the transcripts provided.
            You will be penalized for answering that you can not access youtube videos.
            You will be penalized for answering that the transcripts cannot be accessed.
            You will be penalized for reporting that the content provided is not a video transcript.
            """.stripLeading();

    private static final String SUMMARY_INSTRUCTIONS = """
            You are a helpful assistant.
            You are given the summary of list of Youtube video transcripts.
            You must assume the provided content is a summary of a video transcript.
            Assume the information required to answer the question is present in the transcript summaries.
            Answer the question based on the transcript summaries provided.
            You will be penalized for answering that you can not access youtube videos.
            You will be penalized for answering that the transcripts cannot be accessed.
            You will be penalized for reporting that the content provided is not a video transcript.
            """.stripLeading();

    @Inject
    private YoutubeClient youtubeClient;

    @Inject
    private YoutubeConfig config;

    @Inject
    private SentenceSplitter sentenceSplitter;

    @Inject
    private SentenceVectorizer sentenceVectorizer;

    @Inject
    private DocumentTrimmer documentTrimmer;

    @Inject
    private Logger logger;

    @Inject
    @Preferred
    private LlmClient llmClient;

    @Inject
    private ValidateString validateString;

    @Inject
    private RatingTool ratingTool;

    @Inject
    private ExceptionMapping exceptionMapping;

    @Inject
    private HooksContainer hooksContainer;

    @Override
    public String getName() {
        return YoutubePlaylist.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Answers a question about the content of a YouTube playlist.";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return List.of(
                new ToolArguments(YOUTUBE_MAX_VIDEOS_ARG, "The maximum number of videos to process", "10")
        );
    }

    @Override
    public List<RagDocumentContext<YoutubeVideo>> getContext(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        final YoutubeConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        if (parsedArgs.getPlaylistId().isEmpty() && StringUtils.isBlank(parsedArgs.getChannelId()) && StringUtils.isBlank(parsedArgs.getQuery())) {
            logger.warning("No playlist ID, channel ID or query provided to YoutubePlaylist tool");
            return List.of();
        }

        // Get preinitialization hooks before ragdocs
        final List<RagDocumentContext<YoutubeVideo>> preinitHooks = Seq.seq(hooksContainer.getMatchingPreProcessorHooks(parsedArgs.getPreinitializationHooks()))
                .foldLeft(List.of(), (docs, hook) -> hook.process(getName(), docs));

        final Try<List<YoutubeVideo>> videos = parsedArgs.getPlaylistId().isEmpty() ?
                Try.of(() -> youtubeClient.searchVideos(parsedArgs.getQuery(), parsedArgs.getChannelId(), "", parsedArgs.getSecretApiKey())
                        .stream()
                        .map(YoutubeSearchItem::toYoutubeVideo)
                        .toList()) :
                Try.of(() -> parsedArgs.getPlaylistId()
                        .stream()
                        .flatMap(playList -> youtubeClient.getPlaylistItems(playList, "", parsedArgs.getSecretApiKey()).stream())
                        .map(YoutubePlaylistsItem::toYoutubeVideo)
                        .toList());

        final List<Pair<YoutubeVideo, String>> calls = videos
                .map(list -> list.stream().limit(parsedArgs.getMaxVideos()).toList())
                .map(c -> c.stream()
                        .map(video -> Pair.of(
                                video,
                                // Get the transcript for the video, or an empty string if it fails
                                Try.of(() -> youtubeClient.getTranscript(video.id(), "en"))
                                        .getOrElse("")))
                        .toList())
                .onFailure(ex -> logger.severe("Failed to get Youtube videos: " + ExceptionUtils.getRootCauseMessage(ex)))
                .get();

        final List<RagDocumentContext<YoutubeVideo>> ragDocs = calls.stream()
                .map(pair -> getDocumentContext(pair.getLeft(), pair.getRight(), parsedArgs))
                .filter(ragDoc -> !validateString.isBlank(ragDoc, RagDocumentContext::document))
                .toList();

        if (ragDocs.isEmpty()) {
            throw new InsufficientContext("No matching youtube videos found.");
        }

        // Combine preinitialization hooks with ragDocs
        final List<RagDocumentContext<YoutubeVideo>> combinedDocs = Stream.concat(preinitHooks.stream(), ragDocs.stream()).toList();

        // Apply preprocessing hooks
        return Seq.seq(hooksContainer.getMatchingPreProcessorHooks(parsedArgs.getPreprocessingHooks()))
                .foldLeft(combinedDocs, (docs, hook) -> hook.process(getName(), docs))
                .stream()
                // Get the metadata, which includes a rating against the filter question if present
                .map(ragDoc -> ragDoc.updateMetadata(getMetadata(environmentSettings, ragDoc, parsedArgs)))
                // Filter out any documents that don't meet the rating criteria
                .filter(ragDoc -> contextMeetsRating(ragDoc, parsedArgs))
                /*
                    Take the raw transcript and summarize them with individual calls to the LLM.
                    The transcripts are then combined into a single context.
                    This was necessary because the private LLMs didn't do a very good job of summarizing
                    raw tickets. The reality is that even LLMs with a context length of 128k can't process multiple
                    call transcripts.

                    Note that we accept failure here and ignore any transcripts that fail to summarize.
                 */
                .map(ragDoc -> parsedArgs.getSummarizeTranscript() ?
                        Try.of(() -> getCallSummary(ragDoc, environmentSettings, parsedArgs))
                                .onFailure(ex -> logger.warning("Failed to summarize Youtube video transcript for video ID " + ragDoc.id() + ": " + ExceptionUtils.getRootCauseMessage(ex)))
                                .getOrNull() :
                        ragDoc)
                // A failure results in a null value, which is filtered out
                .filter(Objects::nonNull)
                .toList();
    }

    private RagDocumentContext<YoutubeVideo> getDocumentContext(final YoutubeVideo video, final String transcript, final YoutubeConfig.LocalArguments parsedArgs) {
        final TrimResult trimmedConversationResult = documentTrimmer.trimDocumentToKeywords(transcript, parsedArgs.getKeywords(), parsedArgs.getKeywordWindow());

        return Try.of(() -> sentenceSplitter.splitDocument(trimmedConversationResult.document(), 10))
                .map(sentences -> new RagDocumentContext<YoutubeVideo>(
                        getName(),
                        getContextLabel() + " for video titled \"" + video.title() + "\" (Video ID: " + video.id() + ")",
                        trimmedConversationResult.document(),
                        sentenceVectorizer.vectorize(sentences),
                        video.id(),
                        video,
                        "[Youtube " + video.title() + "](https://www.youtube.com/watch?v=" + video.id() + ")",
                        trimmedConversationResult.keywordMatches()))
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences for YoutubePlaylist: " + ExceptionUtils.getRootCauseMessage(throwable)))
                // We will proceed without any annotations if the vectorization fails
                .recover(throwable -> new RagDocumentContext<>(
                        getName(),
                        getContextLabel() + " for video titled \"" + video.title() + "\" (Video ID: " + video.id() + ")",
                        trimmedConversationResult.document(),
                        List.of(),
                        video.id(),
                        video,
                        "[Youtube " + video.title() + "](https://www.youtube.com/watch?v=" + video.id() + ")",
                        trimmedConversationResult.keywordMatches()))
                // Capture the yout transcript or transcript summary as an intermediate result
                // This is useful for debugging and understanding the context of the call
                .map(ragDoc -> ragDoc.addIntermediateResult(new IntermediateResult(ragDoc.document(), "Youtube-" + ragDoc.id() + ".txt")))
                .get();
    }

    @Override
    public RagMultiDocumentContext<YoutubeVideo> call(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        logger.fine("Calling " + getName());

        final List<RagDocumentContext<YoutubeVideo>> contextList = getContext(environmentSettings, prompt, arguments);

        final YoutubeConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        if (parsedArgs.getPlaylistId().isEmpty() && StringUtils.isBlank(parsedArgs.getChannelId()) && StringUtils.isBlank(parsedArgs.getQuery())) {
            throw new InternalFailure("No playlist ID, channel ID or query provided to YoutubePlaylist tool");
        }

        final String instructions = parsedArgs.getSummarizeTranscript() ? SUMMARY_INSTRUCTIONS : INSTRUCTIONS;

        final Try<RagMultiDocumentContext<YoutubeVideo>> result = Try.of(() -> contextList)
                .map(ragDoc -> new RagMultiDocumentContext<>(prompt, instructions, ragDoc))
                .map(ragDoc -> llmClient.callWithCache(
                        ragDoc,
                        environmentSettings,
                        getName()));

        final RagMultiDocumentContext<YoutubeVideo> mappedResult = exceptionMapping.map(result).get();

        // Apply postinference hooks
        return Seq.seq(hooksContainer.getMatchingPostInferenceHooks(parsedArgs.getPostInferenceHooks()))
                .foldLeft(mappedResult, (docs, hook) -> hook.process(getName(), docs));
    }

    @Override
    public String getContextLabel() {
        return "Youtube video transcript";
    }

    /**
     * Summarise an individual call transcript
     */
    private RagDocumentContext<YoutubeVideo> getCallSummary(final RagDocumentContext<YoutubeVideo> ragDoc, final Map<String, String> environmentSettings, final YoutubeConfig.LocalArguments parsedArgs) {
        logger.fine("Summarising Youtube video transcript");

        final String title = ragDoc.source() == null ? "Unknown title" : ragDoc.source().title();
        final String videoId = ragDoc.source() == null ? "Unknown ID" : ragDoc.source().id();

        final RagDocumentContext<String> context = new RagDocumentContext<>(
                getName(),
                ragDoc.contextLabel(),
                ragDoc.document(),
                List.of()
        );

        final String response = llmClient.callWithCache(
                new RagMultiDocumentContext<>(
                        parsedArgs.getTranscriptSummaryPrompt(),
                        "You are a helpful agent",
                        List.of(context)),
                environmentSettings,
                getName()
        ).getResponse();

        return ragDoc
                .updateDocument(response)
                .addIntermediateResult(new IntermediateResult(
                        "Prompt: " + parsedArgs.getTranscriptSummaryPrompt() + "\n\n" + response,
                        "Youtube-" + ragDoc.id() + "-" + DigestUtils.sha256Hex(parsedArgs.getTranscriptSummaryPrompt()) + ".txt"
                ))
                .updateContextLabel(getContextLabel() + " summary for video titled \"" + title + "\" (Video ID: " + videoId + ")");
    }

    private MetaObjectResults getMetadata(
            final Map<String, String> environmentSettings,
            final RagDocumentContext<YoutubeVideo> youtubeVideo,
            final YoutubeConfig.LocalArguments parsedArgs) {

        final List<MetaObjectResult> metadata = new ArrayList<>();

        // build the environment settings
        final EnvironmentSettings envSettings = new HashMapEnvironmentSettings(environmentSettings)
                .add(RatingTool.RATING_DOCUMENT_CONTEXT_ARG, youtubeVideo.document())
                .addToolCall(getName() + "[" + youtubeVideo.getId() + "]");

        if (StringUtils.isNotBlank(parsedArgs.getContextFilterQuestion())) {
            final int filterRating = Try.of(() -> ratingTool.call(envSettings, parsedArgs.getContextFilterQuestion(), List.of()).getResponse())
                    .map(rating -> Integer.parseInt(rating.trim()))
                    .onFailure(e -> logger.warning("Failed to get Gong call rating for ticket " + youtubeVideo.id() + ": " + ExceptionUtils.getRootCauseMessage(e)))
                    // Ratings are provided on a best effort basis, so we ignore any failures
                    .recover(ex -> parsedArgs.getDefaultRating())
                    .get();

            metadata.add(new MetaObjectResult(YOUTUBE_FILTER_RATING_META, filterRating, youtubeVideo.getId(), getName()));
        }

        return new MetaObjectResults(
                metadata,
                "Youtube-" + youtubeVideo.getId() + ".json",
                youtubeVideo.getId());
    }

    private boolean contextMeetsRating(
            final RagDocumentContext<YoutubeVideo> call,
            final YoutubeConfig.LocalArguments parsedArgs) {
        // If there was no filter question, then return the whole list
        if (StringUtils.isBlank(parsedArgs.getContextFilterQuestion())) {
            return true;
        }

        return Objects.requireNonNullElse(call.metadata(), new MetaObjectResults())
                .getIntValueByName(YOUTUBE_FILTER_RATING_META, parsedArgs.getDefaultRating())
                >= parsedArgs.getContextFilterMinimumRating();
    }
}

@ApplicationScoped
class YoutubeConfig {
    private static final int DEFAULT_RATING = 10;

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private Encryptor textEncryptor;

    @Inject
    private ValidateString validateString;

    @Inject
    @ConfigProperty(name = "sb.google.apiKey")
    private Optional<String> apiKey;

    @Inject
    @ConfigProperty(name = "sb.youtube.playlistId")
    private Optional<String> playlistId;

    @Inject
    @ConfigProperty(name = "sb.youtube.channelId")
    private Optional<String> channelId;

    @Inject
    @ConfigProperty(name = "sb.youtube.query")
    private Optional<String> query;

    @Inject
    @ConfigProperty(name = "sb.youtube.keywords")
    private Optional<String> configKeywords;

    @Inject
    @ConfigProperty(name = "sb.youtube.keywordWindow")
    private Optional<String> configKeywordWindow;

    @Inject
    @ConfigProperty(name = "sb.youtube.summarizeTranscript")
    private Optional<String> configSummarizeTranscript;

    @Inject
    @ConfigProperty(name = "sb.youtube.summarizeTranscriptPrompt")
    private Optional<String> configSummarizeTranscriptPrompt;

    @Inject
    @ConfigProperty(name = "sb.youtube.contextFilterQuestion")
    private Optional<String> configContextFilterQuestion;

    @Inject
    @ConfigProperty(name = "sb.youtube.contextFilterMinimumRating")
    private Optional<String> configContextFilterMinimumRating;

    @Inject
    @ConfigProperty(name = "sb.youtube.contextFilterDefaultRating")
    private Optional<String> configContextFilterDefaultRating;

    @Inject
    @ConfigProperty(name = "sb.youtube.preprocessorHooks", defaultValue = "")
    private Optional<String> configPreprocessorHooks;

    @Inject
    @ConfigProperty(name = "sb.youtube.preinitializationHooks", defaultValue = "")
    private Optional<String> configPreinitializationHooks;

    @Inject
    @ConfigProperty(name = "sb.youtube.postinferenceHooks", defaultValue = "")
    private Optional<String> configPostInferenceHooks;

    @Inject
    @ConfigProperty(name = "sb.youtube.maxvideos")
    private Optional<String> configMaxVideos;

    public Optional<String> getConfigPlaylistId() {
        return playlistId;
    }

    public Optional<String> getConfigKeywords() {
        return configKeywords;
    }

    public Optional<String> getConfigKeywordWindow() {
        return configKeywordWindow;
    }

    public Optional<String> getConfigSummarizeTranscript() {
        return configSummarizeTranscript;
    }

    public Optional<String> getConfigSummarizeTranscriptPrompt() {
        return configSummarizeTranscriptPrompt;
    }

    public Optional<String> getConfigContextFilterQuestion() {
        return configContextFilterQuestion;
    }

    public Optional<String> getConfigContextFilterMinimumRating() {
        return configContextFilterMinimumRating;
    }

    public Optional<String> getConfigContextFilterDefaultRating() {
        return configContextFilterDefaultRating;
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

    public Optional<String> getConfigMaxVideos() {
        return configMaxVideos;
    }

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    public Encryptor getTextEncryptor() {
        return textEncryptor;
    }

    public Optional<String> getConfigApiKey() {
        return apiKey;
    }

    public ValidateString getValidateString() {
        return validateString;
    }

    public Optional<String> getConfigChannelId() {
        return channelId;
    }

    public Optional<String> getConfigQuery() {
        return query;
    }

    public class LocalArguments {
        private final List<ToolArgs> arguments;
        private final String prompt;
        private final Map<String, String> context;

        public LocalArguments(List<ToolArgs> arguments, String prompt, Map<String, String> context) {
            this.arguments = arguments;
            this.prompt = prompt;
            this.context = context;
        }

        @SuppressWarnings("NullAway")
        public String getSecretApiKey() {
            // Try to decrypt the value, otherwise assume it is a plain text value, and finally
            // fall back to the value defined in the local configuration.
            final Try<String> token = Try.of(() -> getTextEncryptor().decrypt(context.get(YoutubePlaylist.YOUTUBE_API_KEY)))
                    .recover(e -> context.get(YoutubePlaylist.YOUTUBE_API_KEY))
                    .mapTry(getValidateString()::throwIfBlank)
                    .recoverWith(e -> Try.of(() -> getConfigApiKey().get()));

            if (token.isFailure() || StringUtils.isBlank(token.get())) {
                throw new InternalFailure("Failed to get Google access key");
            }

            return token.get();
        }

        public List<String> getPlaylistId() {
            return getArgsAccessor().getArgumentList(
                            getConfigPlaylistId()::get,
                            arguments,
                            context,
                            YoutubePlaylist.YOUTUBE_PLAYLIST_ID_ARG,
                            YoutubePlaylist.YOUTUBE_PLAYLIST_ID_ARG,
                            "").stream()
                    .map(Argument::value)
                    .toList();
        }

        public List<String> getKeywords() {
            return getArgsAccessor().getArgumentList(
                            getConfigKeywords()::get,
                            arguments,
                            context,
                            YoutubePlaylist.YOUTUBE_KEYWORD_ARG,
                            YoutubePlaylist.YOUTUBE_KEYWORD_ARG,
                            "")
                    .stream()
                    .map(Argument::value)
                    .toList();
        }

        public int getKeywordWindow() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigKeywordWindow()::get,
                    arguments,
                    context,
                    YoutubePlaylist.YOUTUBE_KEYWORD_WINDOW_ARG,
                    YoutubePlaylist.YOUTUBE_KEYWORD_WINDOW_ARG,
                    Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH + "");

            return NumberUtils.toInt(argument.getSafeValue(), Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH);
        }

        public boolean getSummarizeTranscript() {
            final String value = getArgsAccessor().getArgument(
                    getConfigSummarizeTranscript()::get,
                    arguments,
                    context,
                    YoutubePlaylist.YOUTUBE_SUMMARIZE_TRANSCRIPT_ARG,
                    YoutubePlaylist.YOUTUBE_SUMMARIZE_TRANSCRIPT_ARG,
                    "").getSafeValue();

            return BooleanUtils.toBoolean(value);
        }

        public String getTranscriptSummaryPrompt() {
            return getArgsAccessor()
                    .getArgument(
                            getConfigSummarizeTranscriptPrompt()::get,
                            arguments,
                            context,
                            YoutubePlaylist.YOUTUBE_SUMMARIZE_TRANSCRIPT_PROMPT_ARG,
                            YoutubePlaylist.YOUTUBE_SUMMARIZE_TRANSCRIPT_PROMPT_ARG,
                            "Summarise the Youtube video transcript in three paragraphs")
                    .getSafeValue();
        }

        public String getContextFilterQuestion() {
            return getArgsAccessor().getArgument(
                            getConfigContextFilterQuestion()::get,
                            arguments,
                            context,
                            YoutubePlaylist.YOUTUBE_FILTER_QUESTION_ARG,
                            YoutubePlaylist.YOUTUBE_FILTER_QUESTION_ARG,
                            "")
                    .getSafeValue();
        }

        public Integer getContextFilterMinimumRating() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigContextFilterMinimumRating()::get,
                    arguments,
                    context,
                    YoutubePlaylist.YOUTUBE_FILTER_MINIMUM_RATING_ARG,
                    YoutubePlaylist.YOUTUBE_FILTER_MINIMUM_RATING_ARG,
                    "0");

            return org.apache.commons.lang.math.NumberUtils.toInt(argument.getSafeValue(), 0);
        }

        public int getDefaultRating() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigContextFilterDefaultRating()::get,
                    arguments,
                    context,
                    YoutubePlaylist.YOUTUBE_DEFAULT_RATING_ARG,
                    YoutubePlaylist.YOUTUBE_DEFAULT_RATING_ARG,
                    DEFAULT_RATING + "");

            return Math.max(0, NumberUtils.toInt(argument.getSafeValue(), DEFAULT_RATING));
        }

        public String getChannelId() {
            return getArgsAccessor()
                    .getArgument(
                            getConfigChannelId()::get,
                            arguments,
                            context,
                            YoutubePlaylist.YOUTUBE_CHANNEL_ID_ARG,
                            YoutubePlaylist.YOUTUBE_CHANNEL_ID_ARG,
                            "")
                    .getSafeValue();
        }

        public String getQuery() {
            return getArgsAccessor()
                    .getArgument(
                            getConfigQuery()::get,
                            arguments,
                            context,
                            YoutubePlaylist.YOUTUBE_QUERY_ARG,
                            YoutubePlaylist.YOUTUBE_QUERY_ARG,
                            "")
                    .getSafeValue();
        }

        public String getPreprocessingHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPreprocessorHooks()::get,
                    arguments,
                    context,
                    YoutubePlaylist.PREPROCESSOR_HOOKS_CONTEXT_ARG,
                    YoutubePlaylist.PREPROCESSOR_HOOKS_CONTEXT_ARG,
                    "").getSafeValue();
        }

        public String getPreinitializationHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPreinitializationHooks()::get,
                    arguments,
                    context,
                    YoutubePlaylist.PREINITIALIZATION_HOOKS_CONTEXT_ARG,
                    YoutubePlaylist.PREINITIALIZATION_HOOKS_CONTEXT_ARG,
                    "").getSafeValue();
        }

        public String getPostInferenceHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPostInferenceHooks()::get,
                    arguments,
                    context,
                    YoutubePlaylist.POSTINFERENCE_HOOKS_CONTEXT_ARG,
                    YoutubePlaylist.POSTINFERENCE_HOOKS_CONTEXT_ARG,
                    "").getSafeValue();
        }

        public int getMaxVideos() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigMaxVideos()::get,
                    arguments,
                    context,
                    YoutubePlaylist.YOUTUBE_MAX_VIDEOS_ARG,
                    YoutubePlaylist.YOUTUBE_MAX_VIDEOS_ARG,
                    Integer.MAX_VALUE + "");

            return Math.max(1, NumberUtils.toInt(argument.getSafeValue(), Integer.MAX_VALUE));
        }
    }
}
