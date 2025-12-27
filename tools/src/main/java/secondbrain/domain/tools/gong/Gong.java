package secondbrain.domain.tools.gong;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jooq.lambda.Seq;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.config.LocalConfigFilteredItem;
import secondbrain.domain.config.LocalConfigFilteredParent;
import secondbrain.domain.config.LocalConfigKeywordsEntity;
import secondbrain.domain.config.LocalConfigSummarizer;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.date.DateParser;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptionhandling.ExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.hooks.HooksContainer;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.processing.DataToRagDoc;
import secondbrain.domain.processing.RagDocSummarizer;
import secondbrain.domain.processing.RatingFilter;
import secondbrain.domain.processing.RatingMetadata;
import secondbrain.domain.tooldefs.MetaObjectResult;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.domain.tools.gong.model.GongCallDetails;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.gong.GongClient;
import secondbrain.infrastructure.gong.api.GongCallExtensive;
import secondbrain.infrastructure.llm.LlmClient;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;

@ApplicationScoped
public class Gong implements Tool<GongCallDetails> {
    public static final String COMPANY_ARG = "company";
    public static final String CALLID_ARG = "callId";
    public static final String GONG_OBJECT_1_ARG = "object1";
    public static final String GONG_OBJECT_1_NAME_ARG = "object1Name";
    public static final String GONG_OBJECT_2_ARG = "object2";
    public static final String GONG_OBJECT_2_NAME_ARG = "object2Name";
    public static final String GONG_OBJECT_3_ARG = "object3";
    public static final String GONG_OBJECT_3_NAME_ARG = "object3Name";
    public static final String GONG_OBJECT_4_ARG = "object4";
    public static final String GONG_OBJECT_4_NAME_ARG = "object4Name";
    public static final String GONG_OBJECT_5_ARG = "object5";
    public static final String GONG_OBJECT_5_NAME_ARG = "object5Name";
    public static final String GONG_OBJECT_6_ARG = "object6";
    public static final String GONG_OBJECT_6_NAME_ARG = "object6Name";
    public static final String GONG_OBJECT_7_ARG = "object7";
    public static final String GONG_OBJECT_7_NAME_ARG = "object7Name";
    public static final String GONG_OBJECT_8_ARG = "object8";
    public static final String GONG_OBJECT_8_NAME_ARG = "object8Name";
    public static final String GONG_OBJECT_9_ARG = "object9";
    public static final String GONG_OBJECT_9_NAME_ARG = "object9Name";
    public static final String GONG_OBJECT_10_ARG = "object10";
    public static final String GONG_OBJECT_10_NAME_ARG = "object10Name";
    public static final String TTL_SECONDS_ARG = "ttlSeconds";
    private static final String INSTRUCTIONS = """
            You are a helpful assistant.
            You are given list of call transcripts from Gong.
            You must assume the information required to answer the question is present in the transcripts.
            You must answer the question based on the transcripts provided.
            You will be tipped $1000 for answering the question directly from the transcripts.
            When the user asks a question indicating that they want to know about transcripts, you must generate the answer based on the transcripts.
            You will be penalized for answering that the transcripts can not be accessed.
            """.stripLeading();

    @Inject
    private RatingMetadata ratingMetadata;

    @Inject
    private RatingFilter ratingFilter;

    @Inject
    private DataToRagDoc dataToRagDoc;

    @Inject
    private RagDocSummarizer ragDocSummarizer;

    @Inject
    private GongConfig config;

    @Inject
    @Preferred
    private GongClient gongClient;

    @Inject
    @Preferred
    private LlmClient llmClient;

    @Inject
    private ValidateString validateString;

    @Inject
    private Logger logger;

    @Inject
    private ExceptionMapping exceptionMapping;

    @Inject
    private HooksContainer hooksContainer;

    @Inject
    private DateParser dateParser;

    @Inject
    @Preferred
    private LocalStorage localStorage;

    @Override
    public String getName() {
        return Gong.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Returns the transcripts of calls recorded in Gong";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return List.of();
    }

    @Override
    public List<RagDocumentContext<GongCallDetails>> getContext(
            final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        final GongConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);
        final String cacheKey = parsedArgs.toString().hashCode() + "_" + prompt.hashCode();
        return Try.of(() -> localStorage.getOrPutGeneric(
                        getName(),
                        getName(),
                        Integer.toString(cacheKey.hashCode()),
                        parsedArgs.getCacheTtl(),
                        List.class,
                        RagDocumentContext.class,
                        GongCallDetails.class,
                        () -> getContextPrivate(environmentSettings, prompt, arguments)).result())
                .filter(Objects::nonNull)
                .onFailure(NoSuchElementException.class, ex -> logger.warning("Failed to generate Gong context: " + ExceptionUtils.getRootCauseMessage(ex)))
                .get();
    }

    private List<RagDocumentContext<GongCallDetails>> getContextPrivate(
            final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        logger.fine("Getting context for " + getName());

        final GongConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        logger.fine("Settings are:\n" + parsedArgs);

        // Get preinitialization hooks before ragdocs
        final List<RagDocumentContext<GongCallDetails>> preinitHooks = Seq.seq(hooksContainer.getMatchingPreProcessorHooks(parsedArgs.getPreinitializationHooks()))
                .foldLeft(List.of(), (docs, hook) -> hook.process(getName(), docs));

        final List<GongCallDetails> calls = Try.of(() ->
                        gongClient.getCallsExtensive(
                                parsedArgs.getCompany(),
                                parsedArgs.getCallId(),
                                parsedArgs.getSecretAccessKey(),
                                parsedArgs.getSecretAccessSecretKey(),
                                parsedArgs.getStartDate(),
                                parsedArgs.getEndDate()))
                .map(e -> e.stream()
                        .map(gong -> new GongCallDetails(
                                gong.metaData().id(),
                                gong.metaData().url(),
                                gong.getSystemContext("Salesforce")
                                        .flatMap(c -> c.getObject("Name"))
                                        .map(f -> f.value().toString())
                                        .orElse("Unknown"),
                                gong.parties(),
                                gongClient.getCallTranscript(parsedArgs.getSecretAccessKey(), parsedArgs.getSecretAccessSecretKey(), gong),
                                dateParser.parseDate(gong.metaData().started()),
                                getMeta(gong, parsedArgs.getObject1Name(), parsedArgs.getObject1System(), parsedArgs.getObject1Type(), parsedArgs.getObject1Field()),
                                getMeta(gong, parsedArgs.getObject2Name(), parsedArgs.getObject2System(), parsedArgs.getObject2Type(), parsedArgs.getObject2Field()),
                                getMeta(gong, parsedArgs.getObject3Name(), parsedArgs.getObject3System(), parsedArgs.getObject3Type(), parsedArgs.getObject3Field()),
                                getMeta(gong, parsedArgs.getObject4Name(), parsedArgs.getObject4System(), parsedArgs.getObject4Type(), parsedArgs.getObject4Field()),
                                getMeta(gong, parsedArgs.getObject5Name(), parsedArgs.getObject5System(), parsedArgs.getObject5Type(), parsedArgs.getObject5Field()),
                                getMeta(gong, parsedArgs.getObject6Name(), parsedArgs.getObject6System(), parsedArgs.getObject6Type(), parsedArgs.getObject6Field()),
                                getMeta(gong, parsedArgs.getObject7Name(), parsedArgs.getObject7System(), parsedArgs.getObject7Type(), parsedArgs.getObject7Field()),
                                getMeta(gong, parsedArgs.getObject8Name(), parsedArgs.getObject8System(), parsedArgs.getObject8Type(), parsedArgs.getObject8Field()),
                                getMeta(gong, parsedArgs.getObject9Name(), parsedArgs.getObject9System(), parsedArgs.getObject9Type(), parsedArgs.getObject9Field()),
                                getMeta(gong, parsedArgs.getObject10Name(), parsedArgs.getObject10System(), parsedArgs.getObject10Type(), parsedArgs.getObject10Field())))
                        .toList())
                .onFailure(ex -> logger.severe("Failed to get Gong calls: " + ExceptionUtils.getRootCauseMessage(ex)))
                .get();

        final List<RagDocumentContext<GongCallDetails>> ragDocs = calls.stream()
                .map(call -> dataToRagDoc.getDocumentContext(call, getName(), getContextLabelWithDate(call), call.generateMetaObjectResults(), parsedArgs))
                .filter(ragDoc -> !validateString.isBlank(ragDoc, RagDocumentContext::document))
                .toList();

        // Combine preinitialization hooks with ragDocs
        final List<RagDocumentContext<GongCallDetails>> combinedDocs = Stream.concat(preinitHooks.stream(), ragDocs.stream()).toList();

        // Apply preprocessing hooks, and then rating metadata and filtering
        return Seq.seq(hooksContainer.getMatchingPreProcessorHooks(parsedArgs.getPreprocessingHooks()))
                .foldLeft(combinedDocs, (docs, hook) -> hook.process(getName(), docs))
                .stream()
                // Get the metadata, which includes a rating against the filter question if present
                .map(ragDoc -> ragDoc.addMetadata(ratingMetadata.getMetadata(getName(), environmentSettings, ragDoc, parsedArgs)))
                // Filter out any documents that don't meet the rating criteria
                .filter(ragDoc -> ratingFilter.contextMeetsRating(ragDoc, parsedArgs))
                /*
                    Take the raw transcript and summarize them with individual calls to the LLM.
                    The transcripts are then combined into a single context.
                    This was necessary because the private LLMs didn't do a very good job of summarizing
                    raw tickets. The reality is that even LLMs with a context length of 128k can't process multiple
                    call transcripts.
                 */
                .map(ragDoc -> parsedArgs.getSummarizeTranscript()
                        ? ragDocSummarizer.getDocumentSummary(getName(), getContextLabelWithDate(ragDoc.source()), "Gong", ragDoc, environmentSettings, parsedArgs)
                        : ragDoc)
                .toList();
    }

    @Nullable
    private MetaObjectResult getMeta(final GongCallExtensive gong, final String name, final String system, final String type, final String field) {
        if (StringUtils.isAnyBlank(system, type, field)) {
            return null;
        }

        return new MetaObjectResult(
                name,
                gong.getSystemContext(system, type, field)
                        .map(f -> f.value().toString())
                        .orElse("Unknown"),
                gong.metaData().id(),
                getName());
    }

    @Override
    public RagMultiDocumentContext<GongCallDetails> call(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        logger.fine("Calling " + getName());

        final List<RagDocumentContext<GongCallDetails>> contextList = getContext(environmentSettings, prompt, arguments);

        final GongConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        if (StringUtils.isBlank(parsedArgs.getCompany()) && StringUtils.isBlank(parsedArgs.getCallId())) {
            throw new InternalFailure("You must provide a company or call ID to query");
        }

        final Try<RagMultiDocumentContext<GongCallDetails>> result = Try.of(() -> contextList)
                .map(ragDoc -> new RagMultiDocumentContext<>(prompt, INSTRUCTIONS, ragDoc))
                .map(ragDoc -> llmClient.callWithCache(
                        ragDoc,
                        environmentSettings,
                        getName()));

        final RagMultiDocumentContext<GongCallDetails> mappedResult = exceptionMapping.map(result).get();

        // Apply postinference hooks
        return Seq.seq(hooksContainer.getMatchingPostInferenceHooks(parsedArgs.getPostInferenceHooks()))
                .foldLeft(mappedResult, (docs, hook) -> hook.process(getName(), docs));
    }

    @Override
    public String getContextLabel() {
        return "Gong Call";
    }

    private String getContextLabelWithDate(@Nullable final GongCallDetails call) {
        if (call == null || call.started() == null) {
            return getContextLabel();
        }
        return getContextLabel() + " " + call.started().format(ISO_OFFSET_DATE_TIME);
    }
}

@ApplicationScoped
class GongConfig {
    private static final int DEFAULT_RATING = 10;
    private static final int DEFAULT_TTL_SECONDS = 60 * 60 * 24; // 24 hours

    @Inject
    private ToStringGenerator toStringGenerator;

    @Inject
    @ConfigProperty(name = "sb.gong.ttlSeconds")
    private Optional<String> configTtlSeconds;

    @Inject
    @ConfigProperty(name = "sb.gong.accessKey")
    private Optional<String> configAccessKey;

    @Inject
    @ConfigProperty(name = "sb.gong.accessSecretKey")
    private Optional<String> configAccessSecretKey;

    @Inject
    @ConfigProperty(name = "sb.gong.days")
    private Optional<String> configDays;

    @Inject
    @ConfigProperty(name = "sb.gong.company")
    private Optional<String> configCompany;

    @Inject
    @ConfigProperty(name = "sb.gong.callId")
    private Optional<String> configCallId;

    @Inject
    @ConfigProperty(name = "sb.gong.keywords")
    private Optional<String> configKeywords;

    @Inject
    @ConfigProperty(name = "sb.gong.keywordwindow")
    private Optional<String> configKeywordWindow;

    @Inject
    @ConfigProperty(name = "sb.gong.summarizetranscript")
    private Optional<String> configSummarizeTranscript;

    @Inject
    @ConfigProperty(name = "sb.gong.summarizetranscriptprompt")
    private Optional<String> configSummarizeTranscriptPrompt;

    @Inject
    @ConfigProperty(name = "sb.gong.contextFilterQuestion")
    private Optional<String> configContextFilterQuestion;

    @Inject
    @ConfigProperty(name = "sb.gong.contextFilterGreaterThan")
    private Optional<String> configContextFilterGreaterThan;

    @Inject
    @ConfigProperty(name = "sb.gong.contextFilterMinimumRating")
    private Optional<String> configContextFilterMinimumRating;

    @Inject
    @ConfigProperty(name = "sb.gong.contextFilterDefaultRating")
    private Optional<String> configContextFilterDefaultRating;

    @Inject
    @ConfigProperty(name = "sb.gong.preprocessorHooks", defaultValue = "")
    private Optional<String> configPreprocessorHooks;

    @Inject
    @ConfigProperty(name = "sb.gong.preinitializationHooks", defaultValue = "")
    private Optional<String> configPreinitializationHooks;

    @Inject
    @ConfigProperty(name = "sb.gong.postinferenceHooks", defaultValue = "")
    private Optional<String> configPostInferenceHooks;

    @Inject
    @ConfigProperty(name = "sb.gong.object1name", defaultValue = "")
    private Optional<String> configGongObject1Name;

    /**
     * Format is system:type:field e.g. Salesforce:Account:BillingCity. This is used to match the values in the context
     * array. An example array is shown below:
     * <p>
     * {
     * "context": [
     * {
     * "system": "Salesforce",
     * "objects": [
     * {
     * "objectType": "Account",
     * "objectId": "1234567890",
     * "fields": [
     * {
     * "name": "BillingCity",
     * "value": "Burwood East VIC"
     * }
     * ]
     * },
     * {
     * "objectType": "Opportunity",
     * "objectId": "1234567890",
     * "fields": [
     * {
     * "name": "LeadSource",
     * "value": "Sales - Outreach"
     * }
     * ]
     * }
     * ]
     * }
     * ]
     * }
     */
    @Inject
    @ConfigProperty(name = "sb.gong.object1", defaultValue = "")
    private Optional<String> configGongObject1;

    @Inject
    @ConfigProperty(name = "sb.gong.object2name", defaultValue = "")
    private Optional<String> configGongObject2Name;

    @Inject
    @ConfigProperty(name = "sb.gong.object2", defaultValue = "")
    private Optional<String> configGongObject2;

    @Inject
    @ConfigProperty(name = "sb.gong.object3name", defaultValue = "")
    private Optional<String> configGongObject3Name;

    @Inject
    @ConfigProperty(name = "sb.gong.object3", defaultValue = "")
    private Optional<String> configGongObject3;

    @Inject
    @ConfigProperty(name = "sb.gong.object4name", defaultValue = "")
    private Optional<String> configGongObject4Name;

    @Inject
    @ConfigProperty(name = "sb.gong.object4", defaultValue = "")
    private Optional<String> configGongObject4;

    @Inject
    @ConfigProperty(name = "sb.gong.object5name", defaultValue = "")
    private Optional<String> configGongObject5Name;

    @Inject
    @ConfigProperty(name = "sb.gong.object5", defaultValue = "")
    private Optional<String> configGongObject5;

    @Inject
    @ConfigProperty(name = "sb.gong.object6name", defaultValue = "")
    private Optional<String> configGongObject6Name;

    @Inject
    @ConfigProperty(name = "sb.gong.object6", defaultValue = "")
    private Optional<String> configGongObject6;

    @Inject
    @ConfigProperty(name = "sb.gong.object7name", defaultValue = "")
    private Optional<String> configGongObject7Name;

    @Inject
    @ConfigProperty(name = "sb.gong.object7", defaultValue = "")
    private Optional<String> configGongObject7;

    @Inject
    @ConfigProperty(name = "sb.gong.object8name", defaultValue = "")
    private Optional<String> configGongObject8Name;

    @Inject
    @ConfigProperty(name = "sb.gong.object8", defaultValue = "")
    private Optional<String> configGongObject8;

    @Inject
    @ConfigProperty(name = "sb.gong.object9name", defaultValue = "")
    private Optional<String> configGongObject9Name;

    @Inject
    @ConfigProperty(name = "sb.gong.object9", defaultValue = "")
    private Optional<String> configGongObject9;

    @Inject
    @ConfigProperty(name = "sb.gong.object10name", defaultValue = "")
    private Optional<String> configGongObject10Name;

    @Inject
    @ConfigProperty(name = "sb.gong.object10", defaultValue = "")
    private Optional<String> configGongObject10;

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private Encryptor textEncryptor;

    @Inject
    private ValidateString validateString;

    public Optional<String> getConfigDays() {
        return configDays;
    }

    public Optional<String> getConfigAccessKey() {
        return configAccessKey;
    }

    public Optional<String> getConfigAccessSecretKey() {
        return configAccessSecretKey;
    }

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    public Encryptor getTextEncryptor() {
        return textEncryptor;
    }

    public ValidateString getValidateString() {
        return validateString;
    }

    public Optional<String> getConfigCompany() {
        return configCompany;
    }

    public Optional<String> getConfigKeywords() {
        return configKeywords;
    }

    public Optional<String> getConfigKeywordWindow() {
        return configKeywordWindow;
    }

    public Optional<String> getConfigCallId() {
        return configCallId;
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

    public Optional<String> getConfigContextFilterGreaterThan() {
        return configContextFilterGreaterThan;
    }

    public Optional<String> getConfigGongObject1() {
        return configGongObject1;
    }

    public Optional<String> getConfigGongObject1Name() {
        return configGongObject1Name;
    }

    public Optional<String> getConfigGongObject2() {
        return configGongObject2;
    }

    public Optional<String> getConfigGongObject2Name() {
        return configGongObject2Name;
    }

    public Optional<String> getConfigGongObject3() {
        return configGongObject3;
    }

    public Optional<String> getConfigGongObject3Name() {
        return configGongObject3Name;
    }

    public Optional<String> getConfigGongObject4() {
        return configGongObject4;
    }

    public Optional<String> getConfigGongObject4Name() {
        return configGongObject4Name;
    }

    public Optional<String> getConfigGongObject5() {
        return configGongObject5;
    }

    public Optional<String> getConfigGongObject5Name() {
        return configGongObject5Name;
    }

    public Optional<String> getConfigGongObject6() {
        return configGongObject6;
    }

    public Optional<String> getConfigGongObject6Name() {
        return configGongObject6Name;
    }

    public Optional<String> getConfigGongObject7() {
        return configGongObject7;
    }

    public Optional<String> getConfigGongObject7Name() {
        return configGongObject7Name;
    }

    public Optional<String> getConfigGongObject8() {
        return configGongObject8;
    }

    public Optional<String> getConfigGongObject8Name() {
        return configGongObject8Name;
    }

    public Optional<String> getConfigGongObject9() {
        return configGongObject9;
    }

    public Optional<String> getConfigGongObject9Name() {
        return configGongObject9Name;
    }

    public Optional<String> getConfigGongObject10() {
        return configGongObject10;
    }

    public Optional<String> getConfigGongObject10Name() {
        return configGongObject10Name;
    }

    public ToStringGenerator getToStringGenerator() {
        return toStringGenerator;
    }

    public Optional<String> getConfigTtlSeconds() {
        return configTtlSeconds;
    }

    public class LocalArguments implements LocalConfigFilteredItem, LocalConfigFilteredParent, LocalConfigKeywordsEntity, LocalConfigSummarizer {
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

        @SuppressWarnings("NullAway")
        public String getSecretAccessKey() {
            // Try to decrypt the value, otherwise assume it is a plain text value, and finally
            // fall back to the value defined in the local configuration.
            final Try<String> token = Try.of(() -> getTextEncryptor().decrypt(context.get("gong_access_key")))
                    .recover(e -> context.get("gong_access_key"))
                    .mapTry(getValidateString()::throwIfBlank)
                    .recoverWith(e -> Try.of(() -> getConfigAccessKey().get()));

            if (token.isFailure() || StringUtils.isBlank(token.get())) {
                throw new InternalFailure("Failed to get Gong access key");
            }

            return token.get();
        }

        @SuppressWarnings("NullAway")
        public String getSecretAccessSecretKey() {
            // Try to decrypt the value, otherwise assume it is a plain text value, and finally
            // fall back to the value defined in the local configuration.
            final Try<String> token = Try.of(() -> getTextEncryptor().decrypt(context.get("gong_access_secret_key")))
                    .recover(e -> context.get("gong_access_secret_key"))
                    .mapTry(getValidateString()::throwIfBlank)
                    .recoverWith(e -> Try.of(() -> getConfigAccessSecretKey().get()));

            if (token.isFailure() || StringUtils.isBlank(token.get())) {
                throw new InternalFailure("Failed to get Gong access secret key");
            }

            return token.get();
        }

        public String getCompany() {
            return getArgsAccessor().getArgument(
                    getConfigCompany()::get,
                    arguments,
                    context,
                    Gong.COMPANY_ARG,
                    Gong.COMPANY_ARG,
                    "").getSafeValue();
        }

        public String getCallId() {
            return getArgsAccessor().getArgument(
                    getConfigCallId()::get,
                    arguments,
                    context,
                    Gong.CALLID_ARG,
                    Gong.CALLID_ARG,
                    "").getSafeValue();
        }

        public int getDays() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigDays()::get,
                    arguments,
                    context,
                    CommonArguments.DAYS_ARG,
                    CommonArguments.DAYS_ARG,
                    "0").getSafeValue();

            return Try.of(() -> Integer.parseInt(stringValue))
                    .recover(throwable -> 0)
                    .map(i -> Math.max(0, i))
                    .get();
        }

        @Nullable
        public String getStartDate() {
            if (getDays() == 0) {
                return null;
            }

            return OffsetDateTime.now(ZoneId.systemDefault())
                    // truncate to the day to increase the chances of getting a cache hit
                    .truncatedTo(ChronoUnit.DAYS)
                    // Assume one day if nothing was specified
                    .minusDays(getDays())
                    .format(ISO_OFFSET_DATE_TIME);
        }

        @Nullable
        public String getEndDate() {
            if (getDays() == 0) {
                return null;
            }

            return OffsetDateTime.now(ZoneId.systemDefault())
                    // truncate to the day to increase the chances of getting a cache hit
                    .truncatedTo(ChronoUnit.DAYS)
                    .format(ISO_OFFSET_DATE_TIME);
        }

        @Override
        public List<String> getKeywords() {
            return getArgsAccessor().getArgumentList(
                            getConfigKeywords()::get,
                            arguments,
                            context,
                            CommonArguments.KEYWORDS_ARG,
                            CommonArguments.KEYWORDS_ARG,
                            "")
                    .stream()
                    .map(Argument::value)
                    .toList();
        }

        @Override
        public int getKeywordWindow() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigKeywordWindow()::get,
                    arguments,
                    context,
                    CommonArguments.KEYWORD_WINDOW_ARG,
                    CommonArguments.KEYWORD_WINDOW_ARG,
                    Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH + "");

            return NumberUtils.toInt(argument.getSafeValue(), Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH);
        }

        @Override
        public String getEntity() {
            return getArgsAccessor().getArgument(
                    null,
                    null,
                    context,
                    CommonArguments.ENTITY_NAME_CONTEXT_ARG,
                    CommonArguments.ENTITY_NAME_CONTEXT_ARG,
                    "").getSafeValue();
        }

        public boolean getSummarizeTranscript() {
            final String value = getArgsAccessor().getArgument(
                    getConfigSummarizeTranscript()::get,
                    arguments,
                    context,
                    CommonArguments.SUMMARIZE_DOCUMENT_ARG,
                    CommonArguments.SUMMARIZE_DOCUMENT_ARG,
                    "").getSafeValue();

            return BooleanUtils.toBoolean(value);
        }

        @Override
        public String getDocumentSummaryPrompt() {
            return getArgsAccessor()
                    .getArgument(
                            getConfigSummarizeTranscriptPrompt()::get,
                            arguments,
                            context,
                            CommonArguments.SUMMARIZE_DOCUMENT_PROMPT_ARG,
                            CommonArguments.SUMMARIZE_DOCUMENT_PROMPT_ARG,
                            "Summarise the Gong call transcript in three paragraphs")
                    .getSafeValue();
        }

        @Override
        public String getContextFilterQuestion() {
            return getArgsAccessor().getArgument(
                            getConfigContextFilterQuestion()::get,
                            arguments,
                            context,
                            CommonArguments.CONTENT_RATING_QUESTION_ARG,
                            CommonArguments.CONTENT_RATING_QUESTION_ARG,
                            "")
                    .getSafeValue();
        }

        @Override
        public Integer getContextFilterMinimumRating() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigContextFilterMinimumRating()::get,
                    arguments,
                    context,
                    CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG,
                    CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG,
                    "0");

            return org.apache.commons.lang.math.NumberUtils.toInt(argument.getSafeValue(), 0);
        }

        @Override
        public Integer getDefaultRating() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigContextFilterDefaultRating()::get,
                    arguments,
                    context,
                    CommonArguments.DEFAULT_RATING_ARG,
                    CommonArguments.DEFAULT_RATING_ARG,
                    DEFAULT_RATING + "");

            return Math.max(0, NumberUtils.toInt(argument.getSafeValue(), DEFAULT_RATING));
        }

        @Override
        public boolean isContextFilterUpperLimit() {
            final String value = getArgsAccessor().getArgument(
                    getConfigContextFilterGreaterThan()::get,
                    arguments,
                    context,
                    CommonArguments.FILTER_GREATER_THAN_ARG,
                    CommonArguments.FILTER_GREATER_THAN_ARG,
                    "").getSafeValue();

            return BooleanUtils.toBoolean(value);
        }

        public String getPreprocessingHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPreprocessorHooks()::get,
                    arguments,
                    context,
                    CommonArguments.PREPROCESSOR_HOOKS_ARG,
                    CommonArguments.PREPROCESSOR_HOOKS_ARG,
                    "").getSafeValue();
        }

        public String getPreinitializationHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPreinitializationHooks()::get,
                    arguments,
                    context,
                    CommonArguments.PREINITIALIZATION_HOOKS_ARG,
                    CommonArguments.PREINITIALIZATION_HOOKS_ARG,
                    "").getSafeValue();
        }

        public String getPostInferenceHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPostInferenceHooks()::get,
                    arguments,
                    context,
                    CommonArguments.POSTINFERENCE_HOOKS_ARG,
                    CommonArguments.POSTINFERENCE_HOOKS_ARG,
                    "").getSafeValue();
        }

        public String getObject1Name() {
            return getArgsAccessor().getArgument(
                    getConfigGongObject1Name()::get,
                    arguments,
                    context,
                    Gong.GONG_OBJECT_1_NAME_ARG,
                    Gong.GONG_OBJECT_1_NAME_ARG,
                    "").getSafeValue();
        }

        public String getObject1() {
            return getArgsAccessor().getArgument(
                    getConfigGongObject1()::get,
                    arguments,
                    context,
                    Gong.GONG_OBJECT_1_ARG,
                    Gong.GONG_OBJECT_1_ARG,
                    "").getSafeValue();
        }

        public String getObject1System() {
            final String[] object1 = getObject1().split(":");
            if (object1.length == 3) {
                return object1[0];
            }
            return "";
        }

        public String getObject1Type() {
            final String[] object1 = getObject1().split(":");
            if (object1.length == 3) {
                return object1[1];
            }
            return "";
        }

        public String getObject1Field() {
            final String[] object1 = getObject1().split(":");
            if (object1.length == 3) {
                return object1[2];
            }
            return "";
        }

        public String getObject2Name() {
            return getArgsAccessor().getArgument(
                    getConfigGongObject2Name()::get,
                    arguments,
                    context,
                    Gong.GONG_OBJECT_2_NAME_ARG,
                    Gong.GONG_OBJECT_2_NAME_ARG,
                    "").getSafeValue();
        }

        public String getObject2() {
            return getArgsAccessor().getArgument(
                    getConfigGongObject2()::get,
                    arguments,
                    context,
                    Gong.GONG_OBJECT_2_ARG,
                    Gong.GONG_OBJECT_2_ARG,
                    "").getSafeValue();
        }

        public String getObject2System() {
            final String[] object2 = getObject2().split(":");
            if (object2.length == 3) {
                return object2[0];
            }
            return "";
        }

        public String getObject2Type() {
            final String[] object2 = getObject2().split(":");
            if (object2.length == 3) {
                return object2[1];
            }
            return "";
        }

        public String getObject2Field() {
            final String[] object2 = getObject2().split(":");
            if (object2.length == 3) {
                return object2[2];
            }
            return "";
        }

        public String getObject3Name() {
            return getArgsAccessor().getArgument(
                    getConfigGongObject3Name()::get,
                    arguments,
                    context,
                    Gong.GONG_OBJECT_3_NAME_ARG,
                    Gong.GONG_OBJECT_3_NAME_ARG,
                    "").getSafeValue();
        }

        public String getObject3() {
            return getArgsAccessor().getArgument(
                    getConfigGongObject3()::get,
                    arguments,
                    context,
                    Gong.GONG_OBJECT_3_ARG,
                    Gong.GONG_OBJECT_3_ARG,
                    "").getSafeValue();
        }

        public String getObject3System() {
            final String[] object3 = getObject3().split(":");
            if (object3.length == 3) {
                return object3[0];
            }
            return "";
        }

        public String getObject3Type() {
            final String[] object3 = getObject3().split(":");
            if (object3.length == 3) {
                return object3[1];
            }
            return "";
        }

        public String getObject3Field() {
            final String[] object3 = getObject3().split(":");
            if (object3.length == 3) {
                return object3[2];
            }
            return "";
        }

        public String getObject4Name() {
            return getArgsAccessor().getArgument(
                    getConfigGongObject4Name()::get,
                    arguments,
                    context,
                    Gong.GONG_OBJECT_4_NAME_ARG,
                    Gong.GONG_OBJECT_4_NAME_ARG,
                    "").getSafeValue();
        }

        public String getObject4() {
            return getArgsAccessor().getArgument(
                    getConfigGongObject4()::get,
                    arguments,
                    context,
                    Gong.GONG_OBJECT_4_ARG,
                    Gong.GONG_OBJECT_4_ARG,
                    "").getSafeValue();
        }

        public String getObject4System() {
            final String[] object4 = getObject4().split(":");
            if (object4.length == 3) {
                return object4[0];
            }
            return "";
        }

        public String getObject4Type() {
            final String[] object4 = getObject4().split(":");
            if (object4.length == 3) {
                return object4[1];
            }
            return "";
        }

        public String getObject4Field() {
            final String[] object4 = getObject4().split(":");
            if (object4.length == 3) {
                return object4[2];
            }
            return "";
        }

        public String getObject5Name() {
            return getArgsAccessor().getArgument(
                    getConfigGongObject5Name()::get,
                    arguments,
                    context,
                    Gong.GONG_OBJECT_5_NAME_ARG,
                    Gong.GONG_OBJECT_5_NAME_ARG,
                    "").getSafeValue();
        }

        public String getObject5() {
            return getArgsAccessor().getArgument(
                    getConfigGongObject5()::get,
                    arguments,
                    context,
                    Gong.GONG_OBJECT_5_ARG,
                    Gong.GONG_OBJECT_5_ARG,
                    "").getSafeValue();
        }

        public String getObject5System() {
            final String[] object5 = getObject5().split(":");
            if (object5.length == 3) {
                return object5[0];
            }
            return "";
        }

        public String getObject5Type() {
            final String[] object5 = getObject5().split(":");
            if (object5.length == 3) {
                return object5[1];
            }
            return "";
        }

        public String getObject5Field() {
            final String[] object5 = getObject5().split(":");
            if (object5.length == 3) {
                return object5[2];
            }
            return "";
        }

        public String getObject6Name() {
            return getArgsAccessor().getArgument(
                    getConfigGongObject6Name()::get,
                    arguments,
                    context,
                    Gong.GONG_OBJECT_6_NAME_ARG,
                    Gong.GONG_OBJECT_6_NAME_ARG,
                    "").getSafeValue();
        }

        public String getObject6() {
            return getArgsAccessor().getArgument(
                    getConfigGongObject6()::get,
                    arguments,
                    context,
                    Gong.GONG_OBJECT_6_ARG,
                    Gong.GONG_OBJECT_6_ARG,
                    "").getSafeValue();
        }

        public String getObject6System() {
            final String[] object6 = getObject6().split(":");
            if (object6.length == 3) {
                return object6[0];
            }
            return "";
        }

        public String getObject6Type() {
            final String[] object6 = getObject6().split(":");
            if (object6.length == 3) {
                return object6[1];
            }
            return "";
        }

        public String getObject6Field() {
            final String[] object6 = getObject6().split(":");
            if (object6.length == 3) {
                return object6[2];
            }
            return "";
        }

        public String getObject7Name() {
            return getArgsAccessor().getArgument(
                    getConfigGongObject7Name()::get,
                    arguments,
                    context,
                    Gong.GONG_OBJECT_7_NAME_ARG,
                    Gong.GONG_OBJECT_7_NAME_ARG,
                    "").getSafeValue();
        }

        public String getObject7() {
            return getArgsAccessor().getArgument(
                    getConfigGongObject7()::get,
                    arguments,
                    context,
                    Gong.GONG_OBJECT_7_ARG,
                    Gong.GONG_OBJECT_7_ARG,
                    "").getSafeValue();
        }

        public String getObject7System() {
            final String[] object7 = getObject7().split(":");
            if (object7.length == 3) {
                return object7[0];
            }
            return "";
        }

        public String getObject7Type() {
            final String[] object7 = getObject7().split(":");
            if (object7.length == 3) {
                return object7[1];
            }
            return "";
        }

        public String getObject7Field() {
            final String[] object7 = getObject7().split(":");
            if (object7.length == 3) {
                return object7[2];
            }
            return "";
        }

        public String getObject8Name() {
            return getArgsAccessor().getArgument(
                    getConfigGongObject8Name()::get,
                    arguments,
                    context,
                    Gong.GONG_OBJECT_8_NAME_ARG,
                    Gong.GONG_OBJECT_8_NAME_ARG,
                    "").getSafeValue();
        }

        public String getObject8() {
            return getArgsAccessor().getArgument(
                    getConfigGongObject8()::get,
                    arguments,
                    context,
                    Gong.GONG_OBJECT_8_ARG,
                    Gong.GONG_OBJECT_8_ARG,
                    "").getSafeValue();
        }

        public String getObject8System() {
            final String[] object8 = getObject8().split(":");
            if (object8.length == 3) {
                return object8[0];
            }
            return "";
        }

        public String getObject8Type() {
            final String[] object8 = getObject8().split(":");
            if (object8.length == 3) {
                return object8[1];
            }
            return "";
        }

        public String getObject8Field() {
            final String[] object8 = getObject8().split(":");
            if (object8.length == 3) {
                return object8[2];
            }
            return "";
        }

        public String getObject9Name() {
            return getArgsAccessor().getArgument(
                    getConfigGongObject9Name()::get,
                    arguments,
                    context,
                    Gong.GONG_OBJECT_9_NAME_ARG,
                    Gong.GONG_OBJECT_9_NAME_ARG,
                    "").getSafeValue();
        }

        public String getObject9() {
            return getArgsAccessor().getArgument(
                    getConfigGongObject9()::get,
                    arguments,
                    context,
                    Gong.GONG_OBJECT_9_ARG,
                    Gong.GONG_OBJECT_9_ARG,
                    "").getSafeValue();
        }

        public String getObject9System() {
            final String[] object9 = getObject9().split(":");
            if (object9.length == 3) {
                return object9[0];
            }
            return "";
        }

        public String getObject9Type() {
            final String[] object9 = getObject9().split(":");
            if (object9.length == 3) {
                return object9[1];
            }
            return "";
        }

        public String getObject9Field() {
            final String[] object9 = getObject9().split(":");
            if (object9.length == 3) {
                return object9[2];
            }
            return "";
        }

        public String getObject10Name() {
            return getArgsAccessor().getArgument(
                    getConfigGongObject10Name()::get,
                    arguments,
                    context,
                    Gong.GONG_OBJECT_10_NAME_ARG,
                    Gong.GONG_OBJECT_10_NAME_ARG,
                    "").getSafeValue();
        }

        public String getObject10() {
            return getArgsAccessor().getArgument(
                    getConfigGongObject10()::get,
                    arguments,
                    context,
                    Gong.GONG_OBJECT_10_ARG,
                    Gong.GONG_OBJECT_10_ARG,
                    "").getSafeValue();
        }

        public String getObject10System() {
            final String[] object10 = getObject10().split(":");
            if (object10.length == 3) {
                return object10[0];
            }
            return "";
        }

        public String getObject10Type() {
            final String[] object10 = getObject10().split(":");
            if (object10.length == 3) {
                return object10[1];
            }
            return "";
        }

        public String getObject10Field() {
            final String[] object10 = getObject10().split(":");
            if (object10.length == 3) {
                return object10[2];
            }
            return "";
        }

        public int getCacheTtl() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigTtlSeconds()::get,
                    arguments,
                    context,
                    Gong.TTL_SECONDS_ARG,
                    Gong.TTL_SECONDS_ARG,
                    DEFAULT_TTL_SECONDS + "");

            return Math.max(0, org.apache.commons.lang3.math.NumberUtils.toInt(argument.getSafeValue(), DEFAULT_RATING));
        }
    }
}
