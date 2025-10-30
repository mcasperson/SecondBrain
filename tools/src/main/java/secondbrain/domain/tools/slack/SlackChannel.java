package secondbrain.domain.tools.slack;

import com.slack.api.Slack;
import com.slack.api.methods.AsyncMethodsClient;
import io.smallrye.common.annotation.Identifier;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jooq.lambda.Seq;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.config.LocalConfigFilteredItem;
import secondbrain.domain.config.LocalConfigFilteredParent;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.context.SentenceSplitter;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptionhandling.ExceptionMapping;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.hooks.HooksContainer;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.limit.DocumentTrimmer;
import secondbrain.domain.limit.TrimResult;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.processing.RatingFilter;
import secondbrain.domain.processing.RatingMetadata;
import secondbrain.domain.sanitize.SanitizeDocument;
import secondbrain.domain.tooldefs.IntermediateResult;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.slack.SlackClient;
import secondbrain.infrastructure.slack.api.SlackChannelResource;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.google.common.base.Predicates.instanceOf;

@ApplicationScoped
public class SlackChannel implements Tool<Void> {
    public static final String SLACK_CHANNEL_FILTER_QUESTION_ARG = "contentRatingQuestion";
    public static final String SLACK_CHANNEL_FILTER_MINIMUM_RATING_ARG = "contextFilterMinimumRating";
    public static final String SLACK_ENSURE_GREATER_THAN_PROMPT_ARG = "filterGreaterThan";
    public static final String SLACK_CHANEL_ARG = "slackChannel";
    public static final String DAYS_ARG = "days";
    public static final String API_DELAY_ARG = "apiDelay";
    public static final String HISTORY_TTL_ARG = "historyTtl";
    public static final String SLACK_KEYWORD_ARG = "keywords";
    public static final String SLACK_KEYWORD_WINDOW_ARG = "keywordWindow";
    public static final String SLACK_ENTITY_NAME_CONTEXT_ARG = "entityName";
    public static final String SLACK_SUMMARIZE_DOCUMENT_ARG = "summarizeDocument";
    public static final String SLACK_SUMMARIZE_DOCUMENT_PROMPT_ARG = "summarizeDocumentPrompt";
    public static final String SLACK_DEFAULT_RATING_ARG = "ticketDefaultRating";
    public static final String PREPROCESSOR_HOOKS_CONTEXT_ARG = "preProcessorHooks";
    public static final String PREINITIALIZATION_HOOKS_CONTEXT_ARG = "preInitializationHooks";
    public static final String POSTINFERENCE_HOOKS_CONTEXT_ARG = "postInferenceHooks";
    public static final String TTL_SECONDS_ARG = "ttlSeconds";

    private static final int MINIMUM_MESSAGE_LENGTH = 300;
    private static final String INSTRUCTIONS = """
            You are professional agent that understands Slack conversations.
            You are given the history of a Slack channel and asked to answer questions based on the messages provided.
            The tokens "<!here>" and "<!channel>" are used to notify all members of the channel.
            You must consider any message with the tokens "<!here>" or "<!channel>" to be important.
            """;

    @Inject
    private RatingMetadata ratingMetadata;

    @Inject
    private RatingFilter ratingFilter;

    @Inject
    private SlackChannelConfig config;

    @Inject
    @Identifier("removeMarkdnUrls")
    private SanitizeDocument removeMarkdnUrls;

    @Inject
    private SentenceSplitter sentenceSplitter;

    @Inject
    private SentenceVectorizer sentenceVectorizer;

    @Inject
    @Preferred
    private LlmClient llmClient;

    @Inject
    @Preferred
    private SlackClient slackClient;

    @Inject
    private ValidateString validateString;

    @Inject
    private DocumentTrimmer documentTrimmer;

    @Inject
    private Logger logger;

    @Inject
    private ExceptionMapping exceptionMapping;

    @Inject
    private HooksContainer hooksContainer;

    @Inject
    @Preferred
    private LocalStorage localStorage;

    @Override
    public String getName() {
        return SlackChannel.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Returns messages from a Slack channel";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return List.of(
                new ToolArguments(SLACK_CHANEL_ARG, "The Slack channel to read", "general"),
                new ToolArguments(SLACK_KEYWORD_ARG, "The keywords to limit the Slack messages to", ""),
                new ToolArguments(SLACK_KEYWORD_WINDOW_ARG, "The window size around any matching keywords", ""),
                new ToolArguments(DAYS_ARG, "The number of days worth of messages to return", "7")
        );
    }

    @Override
    public String getContextLabel() {
        return "Slack Messages";
    }

    @Override
    public List<RagDocumentContext<Void>> getContext(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {
        final SlackChannelConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);
        final String cacheKey = parsedArgs.toString().hashCode() + "_" + prompt.hashCode();
        return localStorage.getOrPutGeneric(
                        getName(),
                        getName(),
                        Integer.toString(cacheKey.hashCode()),
                        parsedArgs.getCacheTtl(),
                        List.class,
                        RagDocumentContext.class,
                        Void.class,
                        () -> getContextPrivate(environmentSettings, prompt, arguments))
                .result();
    }

    private List<RagDocumentContext<Void>> getContextPrivate(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        logger.fine("Getting context for " + getName());

        final SlackChannelConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        // Get preinitialization hooks before ragdocs
        final List<RagDocumentContext<Void>> preinitHooks = Seq.seq(hooksContainer.getMatchingPreProcessorHooks(parsedArgs.getPreinitializationHooks()))
                .foldLeft(List.of(), (docs, hook) -> hook.process(getName(), docs));

        /*
            Get the oldest date to search from, starting from the starr of the current day.
            This improves the cache if we have to rerun the tool multiple times in a day, as the
            oldest date will be the same.
        */
        final String oldest = Long.valueOf(LocalDateTime.now(ZoneId.systemDefault())
                        .toLocalDate()
                        .atStartOfDay()
                        .minusDays(parsedArgs.getDays())
                        .atZone(ZoneId.systemDefault())
                        .toEpochSecond())
                .toString();

        // you can get this instance via ctx.client() in a Bolt app
        final AsyncMethodsClient client = Slack.getInstance().methodsAsync();

        final SlackChannelResource channel = Try.of(() -> slackClient.findChannelId(
                        client,
                        parsedArgs.getSecretAccessToken(),
                        parsedArgs.getChannel(),
                        parsedArgs.getApiDelay()))
                .getOrElseThrow(() -> new InternalFailure("Channel not found"));

        final Try<TrimResult> messagesTry = Try.of(() -> slackClient.conversationHistory(
                        client,
                        parsedArgs.getSecretAccessToken(),
                        channel.channelId(),
                        oldest,
                        parsedArgs.getSearchTTL(),
                        parsedArgs.getApiDelay()))
                .map(document -> documentTrimmer.trimDocumentToKeywords(
                        document,
                        parsedArgs.getKeywords(),
                        parsedArgs.getKeywordWindow()))
                .map(trimResult -> validateString.throwIfBlank(trimResult, TrimResult::document));

        final TrimResult messages = messagesTry
                .mapFailure(
                        API.Case(API.$(instanceOf(EmptyString.class)), throwable -> new InternalFailure("The Slack channel had no matching messages")),
                        API.Case(API.$(instanceOf(ExternalFailure.class)), ex -> ex),
                        API.Case(API.$(), ex -> new InternalFailure("Failed to get messages", ex))
                )
                .get();

        if (StringUtils.length(messages.document()) < MINIMUM_MESSAGE_LENGTH) {
            throw new InternalFailure("Not enough messages found in channel " + parsedArgs.getChannel()
                    + System.lineSeparator() + System.lineSeparator()
                    + "* [Slack Channel](https://app.slack.com/client/" + channel.teamId() + "/" + channel.channelId() + ")");
        }

        final TrimResult messagesWithUsersReplaced = replaceUserIds(client, parsedArgs.getSecretAccessToken(), messages.document(), parsedArgs)
                .flatMap(message -> replaceChannelIds(client, parsedArgs.getSecretAccessToken(), message, parsedArgs))
                .map(messages::replaceDocument)
                .getOrElseThrow(() -> new InternalFailure("The user and channel IDs could not be replaced"));

        final List<RagDocumentContext<Void>> ragDocs = List.of(getDocumentContext(messagesWithUsersReplaced, channel, environmentSettings, parsedArgs));

        // Combine preinitialization hooks with ragDocs
        final List<RagDocumentContext<Void>> combinedDocs = Stream.concat(preinitHooks.stream(), ragDocs.stream()).toList();

        // Apply preprocessing hooks
        return Seq.seq(hooksContainer.getMatchingPreProcessorHooks(parsedArgs.getPreprocessingHooks()))
                .foldLeft(combinedDocs, (docs, hook) -> hook.process(getName(), docs))
                .stream()
                // Get the metadata, which includes a rating against the filter question if present
                .map(ragDoc -> ragDoc.addMetadata(ratingMetadata.getMetadata(getName(), environmentSettings, ragDoc, parsedArgs)))
                // Filter out any documents that don't meet the rating criteria
                .filter(ragDoc -> ratingFilter.contextMeetsRating(ragDoc, parsedArgs))
                .toList();
    }

    @Override
    public RagMultiDocumentContext<Void> call(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {
        logger.fine("Calling " + getName());

        final SlackChannelConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> getContext(environmentSettings, prompt, arguments))
                .map(ragDoc -> new RagMultiDocumentContext<>(prompt, INSTRUCTIONS, ragDoc))
                .map(ragDoc -> llmClient.callWithCache(
                        ragDoc,
                        environmentSettings,
                        getName()));

        final RagMultiDocumentContext<Void> mappedResult = exceptionMapping.map(result).get();

        // Apply postinference hooks
        return Seq.seq(hooksContainer.getMatchingPostInferenceHooks(parsedArgs.getPostInferenceHooks()))
                .foldLeft(mappedResult, (docs, hook) -> hook.process(getName(), docs));
    }

    private RagDocumentContext<Void> getDocumentContext(final TrimResult trimResult, final SlackChannelResource channelDetails, final Map<String, String> environmentSettings, final SlackChannelConfig.LocalArguments parsedArgs) {
        return Try.of(() -> sentenceSplitter.splitDocument(trimResult.document(), 10))
                // Strip out any URLs from the sentences
                .map(sentences -> sentences.stream().map(sentence -> removeMarkdnUrls.sanitize(sentence)).toList())
                .map(sentences -> new RagDocumentContext<Void>(
                        getName(),
                        getContextLabel(),
                        trimResult.document(),
                        sentenceVectorizer.vectorize(sentences, parsedArgs.getEntity()),
                        channelDetails.channelName(),
                        null,
                        matchToUrl(channelDetails),
                        trimResult.keywordMatches()))
                .map(ragDoc -> ragDoc.addIntermediateResult(new IntermediateResult(ragDoc.document(), "Slack" + ragDoc.id() + ".txt")))
                .map(doc -> parsedArgs.getSummarizeDocument()
                        ? getDocumentSummary(doc, environmentSettings, parsedArgs)
                        : doc)
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                .get();
    }

    private Try<String> replaceUserIds(final AsyncMethodsClient client, final String token, final String messages, final SlackChannelConfig.LocalArguments parsedArgs) {
        final Pattern userPattern = Pattern.compile("<@(?<username>\\w+)>");

        /*
            Start with a pairing of the original messages the results of the regex matching for user IDs.
         */
        return Try.of(() -> userPattern.matcher(messages).results())
                /*
                We map the original message and the list of regex matches for user IDs to a string with
                the user IDs replaced with their usernames.
                 */
                .map(results ->
                        // We want to take each username ID match, replace it with a username, and reduce the results down to a single string.
                        Seq.seq(results).foldLeft(
                                // The starting point is the original message.
                                messages,
                                // Each user id match is replaced with the username retrieved from the Slack API.
                                // The original message with the user IDs replaced is returned.
                                (m, match) -> m.replace(match.group(), slackClient.username(client, token, match.group("username"), parsedArgs.getApiDelay()))))
                /*
                 If any of the previous steps failed, we return the original messages.
                 */
                .recover(error -> messages);
    }

    private Try<String> replaceChannelIds(final AsyncMethodsClient client, final String token, final String messages, final SlackChannelConfig.LocalArguments parsedArgs) {
        final Pattern channelPattern = Pattern.compile("<#(?<channelname>\\w+)\\|?>");

        /*
            Start with a pairing of the original messages the results of the regex matching for user IDs.
         */
        return Try.of(() -> channelPattern.matcher(messages).results())
                /*
                We map the original message and the list of regex matches for user IDs to a string with
                the user IDs replaced with their usernames.
                 */
                .map(results ->
                        // We want to take each username ID match, replace it with a username, and reduce the results down to a single string.
                        Seq.seq(results).foldLeft(
                                // The starting point is the original message.
                                messages,
                                // Each user id match is replaced with the username retrieved from the Slack API.
                                // The original message with the user IDs replaced is returned.
                                (m, match) -> m.replace(match.group(), slackClient.channel(client, token, match.group("channelname"), parsedArgs.getApiDelay()).channelName())))
                /*
                 If any of the previous steps failed, we return the original messages.
                 */
                .recover(error -> messages);
    }

    private RagDocumentContext<Void> getDocumentSummary(final RagDocumentContext<Void> ragDoc, final Map<String, String> environmentSettings, final SlackChannelConfig.LocalArguments parsedArgs) {
        final RagDocumentContext<String> context = new RagDocumentContext<>(
                getName(),
                getContextLabel(),
                ragDoc.document(),
                List.of()
        );

        final String response = llmClient.callWithCache(
                new RagMultiDocumentContext<>(parsedArgs.getDocumentSummaryPrompt(),
                        "You are a helpful agent",
                        List.of(context)),
                environmentSettings,
                getName()
        ).getResponse();

        return ragDoc.updateDocument(response)
                .addIntermediateResult(new IntermediateResult(
                        "Prompt: " + parsedArgs.getDocumentSummaryPrompt() + "\n\n" + response,
                        "Slack" + ragDoc.id() + "-" + DigestUtils.sha256Hex(parsedArgs.getDocumentSummaryPrompt()) + ".txt"
                ));
    }

    private String matchToUrl(final SlackChannelResource channel) {
        return "[Slack " + channel.channelName() + "](https://app.slack.com/client/" + channel.teamId() + "/" + channel.channelId() + ")";
    }
}

@ApplicationScoped
class SlackChannelConfig {
    private static final String DEFAULT_TTL = (1000 * 60 * 60 * 24) + "";
    private static final int DEFAULT_API_DELAY = (1000 * 120);
    private static final int DEFAULT_RATING = 10;
    private static final int DEFAULT_TTL_SECONDS = 60 * 60 * 24; // 24 hours

    @Inject
    private ToStringGenerator toStringGenerator;

    @Inject
    @ConfigProperty(name = "sb.slack.accesstoken")
    private Optional<String> configSlackAccessToken;

    @Inject
    @ConfigProperty(name = "sb.slack.channel")
    private Optional<String> configChannel;

    @Inject
    @ConfigProperty(name = "sb.slack.days")
    private Optional<String> configDays;

    @Inject
    @ConfigProperty(name = "sb.slack.historytto")
    private Optional<String> configHistoryttl;

    @Inject
    @ConfigProperty(name = "sb.slack.keywords")
    private Optional<String> configKeywords;

    @Inject
    @ConfigProperty(name = "sb.slack.keywordwindow")
    private Optional<String> configKeywordWindow;

    @Inject
    @ConfigProperty(name = "sb.slack.apidelay")
    private Optional<String> configApiDelay;

    @Inject
    @ConfigProperty(name = "sb.slack.summarizedocument", defaultValue = "false")
    private Optional<String> configSummarizeDocument;

    @Inject
    @ConfigProperty(name = "sb.slack.summarizedocumentprompt")
    private Optional<String> configSummarizeDocumentPrompt;

    @Inject
    @ConfigProperty(name = "sb.slack.contextFilterQuestion")
    private Optional<String> configContextFilterQuestion;

    @Inject
    @ConfigProperty(name = "sb.slack.contextFilterMinimumRating")
    private Optional<String> configContextFilterMinimumRating;

    @Inject
    @ConfigProperty(name = "sb.slack.contextFilterDefaultRating")
    private Optional<String> configContextFilterDefaultRating;

    @Inject
    @ConfigProperty(name = "sb.slack.contextFilterGreaterThan")
    private Optional<String> configContextFilterGreaterThan;

    @Inject
    @ConfigProperty(name = "sb.slackchannel.preprocessorHooks", defaultValue = "")
    private Optional<String> configPreprocessorHooks;

    @Inject
    @ConfigProperty(name = "sb.slackchannel.preinitializationHooks", defaultValue = "")
    private Optional<String> configPreinitializationHooks;

    @Inject
    @ConfigProperty(name = "sb.slackchannel.postinferenceHooks", defaultValue = "")
    private Optional<String> configPostInferenceHooks;

    @Inject
    @ConfigProperty(name = "sb.slackchannel.ttlSeconds")
    private Optional<String> configTtlSeconds;

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private Encryptor textEncryptor;

    public Optional<String> getConfigSlackAccessToken() {
        return configSlackAccessToken;
    }

    public Optional<String> getConfigChannel() {
        return configChannel;
    }

    public Optional<String> getConfigDays() {
        return configDays;
    }

    public Optional<String> getConfigHistoryttl() {
        return configHistoryttl;
    }

    public Optional<String> getConfigKeywords() {
        return configKeywords;
    }

    public Optional<String> getConfigKeywordWindow() {
        return configKeywordWindow;
    }

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    public Encryptor getTextEncryptor() {
        return textEncryptor;
    }

    public Optional<String> getConfigApiDelay() {
        return configApiDelay;
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

    public Optional<String> getConfigContextFilterDefaultRating() {
        return configContextFilterDefaultRating;
    }

    public Optional<String> getConfigContextFilterGreaterThan() {
        return configContextFilterGreaterThan;
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

    public ToStringGenerator getToStringGenerator() {
        return toStringGenerator;
    }

    public Optional<String> getConfigTtlSeconds() {
        return configTtlSeconds;
    }

    public class LocalArguments implements LocalConfigFilteredItem, LocalConfigFilteredParent {
        private final List<ToolArgs> arguments;

        private final String prompt;

        private final Map<String, String> context;

        public LocalArguments(final List<ToolArgs> arguments, final String prompt, final Map<String, String> context) {
            this.arguments = arguments;
            this.prompt = prompt;
            this.context = context;
        }

        public String toString() {
            return getToStringGenerator().generateGetterConfig(this);
        }

        public String getChannel() {
            return getArgsAccessor().getArgument(
                            getConfigChannel()::get,
                            arguments,
                            context,
                            SlackChannel.SLACK_CHANEL_ARG,
                            SlackChannel.SLACK_CHANEL_ARG,
                            "")
                    .value()
                    .replaceFirst("^#", "");
        }

        public int getDays() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigDays()::get,
                    arguments,
                    context,
                    SlackChannel.DAYS_ARG,
                    SlackChannel.DAYS_ARG,
                    "30");

            return Try.of(argument::value)
                    .map(i -> Math.max(0, Integer.parseInt(i)))
                    .get();
        }

        public String getSecretAccessToken() {
            return Try.of(() -> getTextEncryptor().decrypt(context.get("slack_access_token")))
                    .recover(e -> context.get("slack_access_token"))
                    .mapTry(Objects::requireNonNull)
                    .recoverWith(e -> Try.of(() -> getConfigSlackAccessToken().get()))
                    .getOrElseThrow(() -> new InternalFailure("Slack access token not found"));
        }

        public int getSearchTTL() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigHistoryttl()::get,
                    arguments,
                    context,
                    SlackChannel.HISTORY_TTL_ARG,
                    SlackChannel.HISTORY_TTL_ARG,
                    DEFAULT_TTL);

            return Try.of(argument::value)
                    .map(i -> Math.max(0, Integer.parseInt(i)))
                    .get();
        }

        public int getApiDelay() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigApiDelay()::get,
                    arguments,
                    context,
                    SlackChannel.API_DELAY_ARG,
                    SlackChannel.API_DELAY_ARG,
                    DEFAULT_API_DELAY + "").value();

            return Try.of(() -> stringValue)
                    .map(i -> Math.max(0, NumberUtils.toInt(i, DEFAULT_API_DELAY)))
                    .get();
        }

        public List<String> getKeywords() {
            return getArgsAccessor().getArgumentList(
                            getConfigKeywords()::get,
                            arguments,
                            context,
                            SlackChannel.SLACK_KEYWORD_ARG,
                            SlackChannel.SLACK_KEYWORD_ARG,
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
                    SlackChannel.SLACK_KEYWORD_WINDOW_ARG,
                    SlackChannel.SLACK_KEYWORD_WINDOW_ARG,
                    Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH + "");

            return NumberUtils.toInt(argument.value(), Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH);
        }

        public String getEntity() {
            return getArgsAccessor().getArgument(
                    null,
                    null,
                    context,
                    null,
                    SlackChannel.SLACK_ENTITY_NAME_CONTEXT_ARG,
                    "").value();
        }

        public boolean getSummarizeDocument() {
            final String value = getArgsAccessor().getArgument(
                    getConfigSummarizeDocument()::get,
                    arguments,
                    context,
                    SlackChannel.SLACK_SUMMARIZE_DOCUMENT_ARG,
                    SlackChannel.SLACK_SUMMARIZE_DOCUMENT_ARG,
                    "").value();

            return BooleanUtils.toBoolean(value);
        }

        public String getDocumentSummaryPrompt() {
            return getArgsAccessor()
                    .getArgument(
                            getConfigSummarizeDocumentPrompt()::get,
                            arguments,
                            context,
                            SlackChannel.SLACK_SUMMARIZE_DOCUMENT_PROMPT_ARG,
                            SlackChannel.SLACK_SUMMARIZE_DOCUMENT_PROMPT_ARG,
                            "Summarise the document in three paragraphs")
                    .value();
        }

        public String getContextFilterQuestion() {
            return getArgsAccessor().getArgument(
                            getConfigContextFilterQuestion()::get,
                            arguments,
                            context,
                            SlackChannel.SLACK_CHANNEL_FILTER_QUESTION_ARG,
                            SlackChannel.SLACK_CHANNEL_FILTER_QUESTION_ARG,
                            "")
                    .value();
        }

        public Integer getContextFilterMinimumRating() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigContextFilterMinimumRating()::get,
                    arguments,
                    context,
                    SlackChannel.SLACK_CHANNEL_FILTER_MINIMUM_RATING_ARG,
                    SlackChannel.SLACK_CHANNEL_FILTER_MINIMUM_RATING_ARG,
                    "0");

            return org.apache.commons.lang.math.NumberUtils.toInt(argument.value(), 0);
        }

        public Integer getDefaultRating() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigContextFilterDefaultRating()::get,
                    arguments,
                    context,
                    SlackChannel.SLACK_DEFAULT_RATING_ARG,
                    SlackChannel.SLACK_DEFAULT_RATING_ARG,
                    DEFAULT_RATING + "");

            return Math.max(0, org.apache.commons.lang3.math.NumberUtils.toInt(argument.value(), DEFAULT_RATING));
        }

        public boolean isContextFilterUpperLimit() {
            final String value = getArgsAccessor().getArgument(
                    getConfigContextFilterGreaterThan()::get,
                    arguments,
                    context,
                    SlackChannel.SLACK_ENSURE_GREATER_THAN_PROMPT_ARG,
                    SlackChannel.SLACK_ENSURE_GREATER_THAN_PROMPT_ARG,
                    "").value();

            return BooleanUtils.toBoolean(value);
        }

        public String getPreprocessingHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPreprocessorHooks()::get,
                    arguments,
                    context,
                    SlackChannel.PREPROCESSOR_HOOKS_CONTEXT_ARG,
                    SlackChannel.PREPROCESSOR_HOOKS_CONTEXT_ARG,
                    "").value();
        }

        public String getPreinitializationHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPreinitializationHooks()::get,
                    arguments,
                    context,
                    SlackChannel.PREINITIALIZATION_HOOKS_CONTEXT_ARG,
                    SlackChannel.PREINITIALIZATION_HOOKS_CONTEXT_ARG,
                    "").value();
        }

        public String getPostInferenceHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPostInferenceHooks()::get,
                    arguments,
                    context,
                    SlackChannel.POSTINFERENCE_HOOKS_CONTEXT_ARG,
                    SlackChannel.POSTINFERENCE_HOOKS_CONTEXT_ARG,
                    "").value();
        }

        public int getCacheTtl() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigTtlSeconds()::get,
                    arguments,
                    context,
                    SlackChannel.TTL_SECONDS_ARG,
                    SlackChannel.TTL_SECONDS_ARG,
                    DEFAULT_TTL_SECONDS + "");

            return Math.max(0, org.apache.commons.lang3.math.NumberUtils.toInt(argument.value(), DEFAULT_TTL_SECONDS));
        }
    }
}
