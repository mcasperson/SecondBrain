package secondbrain.domain.tools.keyword;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.common.annotation.Identifier;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jooq.lambda.Seq;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptionhandling.ExceptionMapping;
import secondbrain.domain.hooks.HooksContainer;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.json.JsonDeserializer;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.sanitize.SanitizeDocument;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.infrastructure.llm.LlmClient;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Keywords generates a JSON collection of keywords from a prompt.
 */
@ApplicationScoped
public class Keywords implements Tool<Void> {

    public static final String EXCLUDE_KEYWORDS_ARG = "excludeKeywords";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int KEYWORDS_TTL = 60 * 60 * 24 * 90;
    private static final ConcurrentHashMap<String, ReentrantLock> promptLocks = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, List<String>> keywordCache = new ConcurrentHashMap<>();


    private static final String INSTRUCTIONS = """          
            You are given a prompt.
            You must generate a list of broad, relevant keywords from the prompt.
            The keywords are expected to be found in video transcripts, conversations, emails, and internal messages, so you must select terms that people are likely to literally say or write.
            The keywords are used as partial matches, for example, the keyword "blue" will match "Bluetooth".
            Keywords are case insensitive.
            Prefer single words over phrases with multiple words.
            Two word keywords must also appear as individual words, for example, "Microsoft Authenticator", "Microsoft", and "Authenticator".
            Any hyphenated words must also appear as two individual words, for example: "multi-factor", "multi", and "factor".
            Prefer the base word of a keyword, for example, prefer "Debug" instead of "Debuggable".
            You will be penalized for selecting terms that describe conversations, emails, and internal messages but are unlikely to be literally used in them.
            Aim for 50 keywords.
            Keywords should be specific terms, abbreviations, and acronyms useful for document retrieval.
            If the prompt includes acronyms, platform names, product names, companies, or tools, those must be present in the list.
            If the prompt includes unambigious acronyms, include the important words in the expanded form as keywords, for example: "AKS" and "Azure" and "Kubernetes", or "AWS" and "Amazon". 
            Notice the keywords from an exanded acronym do not include generic terms like "service".
            You will be penalized for returning generic or irrelevant keywords.
            You will be penalized for returning common words like "the", "a", "then", "when" etc.
            You will be penalized for returning markdown or any other formatting.
            The response must be a JSON array of strings, with each string being a keyword.
            You will be penalized for returning any text in the response that is not a valid JSON array.
            For example, if the prompt is "How do I deploy to Azure Kubernetes Service?",
            you might return ["Azure", "Kubernetes", "AKS", "deploy"].
            If there are no useful keywords, return an empty array: [].""".stripLeading();

    @Inject
    private HooksContainer hooksContainer;

    @Inject
    private KeywordsConfig config;

    @Inject
    @Preferred
    private LlmClient llmClient;

    @Inject
    @Identifier("findFirstMarkdownBlock")
    private SanitizeDocument findFirstMarkdownBlock;

    @Inject
    private ExceptionMapping exceptionMapping;

    @Inject
    private JsonDeserializer jsonDeserializer;

    @Inject
    @Preferred
    private LocalStorage localStorage;

    @Inject
    private Logger logger;

    @Override
    public String getName() {
        return Keywords.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Generates a list of keywords from a prompt";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return List.of(
                new ToolArguments(EXCLUDE_KEYWORDS_ARG, "Comma-separated list of keywords to remove from generated output", "")
        );
    }

    public List<String> getKeywords(final Map<String, String> environmentSettings, final List<String> prompts, final List<ToolArgs> arguments) {
        final String combinedPrompt = String.join("\n", prompts);
        final List<String> cachedKeywords = keywordCache.get(combinedPrompt);
        if (cachedKeywords != null) {
            return cachedKeywords;
        }

        final ReentrantLock lock = promptLocks.computeIfAbsent(combinedPrompt, k -> new ReentrantLock());
        lock.lock();
        try {
            final List<String> doubleCheckedCachedKeywords = keywordCache.get(combinedPrompt);
            if (doubleCheckedCachedKeywords != null) {
                return doubleCheckedCachedKeywords;
            }

            final List<String> generatedKeywords = Try.of(() -> call(environmentSettings, List.of(combinedPrompt), List.of()))
                    .map(result -> result.getResponses().get(0))
                    .map(list -> jsonDeserializer.deserializeCollection(list, String.class))
                    .map(List::copyOf)
                    .getOrElse(List.of());

            keywordCache.put(combinedPrompt, generatedKeywords);
            return generatedKeywords;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<RagDocumentContext<Void>> getContext(final Map<String, String> environmentSettings, final List<String> prompts, final List<ToolArgs> arguments) {
        final String prompt = prompts.isEmpty() ? "" : prompts.getFirst();

        // We expect this tool to be called by multiple threads with the same prompt.
        // The first one should generate the value, and others should get the cached result.

        final ReentrantLock lock = promptLocks.computeIfAbsent(prompt, k -> new ReentrantLock());
        lock.lock();
        try {
            return Try.of(() -> localStorage.getOrPutGeneric(
                                    getName(),
                                    getName(),
                                    Integer.toString((INSTRUCTIONS + prompt).hashCode()),
                                    KEYWORDS_TTL,
                                    List.class,
                                    RagDocumentContext.class,
                                    Void.class,
                                    () -> getContextPrivate(environmentSettings, prompt, arguments))
                            .result())
                    .filter(Objects::nonNull)
                    .onFailure(NoSuchElementException.class, ex -> logger.warning("Failed to get Keywords context from cache: " + ExceptionUtils.getRootCauseMessage(ex)))
                    .getOrElse(List::of);
        } finally {
            lock.unlock();
        }
    }

    private List<RagDocumentContext<Void>> getContextPrivate(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        final KeywordsConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, List.of(prompt), environmentSettings);

        // Get preinitialization hooks before ragdocs
        final List<RagDocumentContext<Void>> preinitHooks = Seq.seq(hooksContainer.getMatchingPreProcessorHooks(parsedArgs.getPreinitializationHooks()))
                .foldLeft(List.of(), (docs, hook) -> hook.process(getName(), docs));

        final List<RagDocumentContext<Void>> ragDocs = List.of();

        // Combine preinitialization hooks with ragDocs
        final List<RagDocumentContext<Void>> combinedDocs = Stream.concat(preinitHooks.stream(), ragDocs.stream()).toList();

        // Apply preprocessing hooks
        return Seq.seq(hooksContainer.getMatchingPreProcessorHooks(parsedArgs.getPreprocessingHooks()))
                .foldLeft(combinedDocs, (docs, hook) -> hook.process(getName(), docs));
    }

    @Override
    public RagMultiDocumentContext<Void> call(final Map<String, String> environmentSettings, final List<String> prompts, final List<ToolArgs> arguments) {
        final KeywordsConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompts, environmentSettings);

        final List<RagDocumentContext<Void>> contextList = getContext(environmentSettings, prompts, arguments);

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> contextList)
                .map(ragDoc -> new RagMultiDocumentContext<>(prompts, INSTRUCTIONS, ragDoc))
                .map(ragDoc -> llmClient.callWithCache(
                        ragDoc,
                        environmentSettings,
                        getName()))
                // Some models return markdown wrappers around the JSON array.
                .map(ragDoc -> ragDoc.updateResponses(ragDoc.getResponses().stream()
                        .map(r -> StringUtils.trim(findFirstMarkdownBlock.sanitize(r)))
                        .map(r -> applyExclusionsToResponse(r, parsedArgs.getExcludeKeywords()))
                        .toList()));

        final RagMultiDocumentContext<Void> mappedResult = exceptionMapping.map(result).get();

        // Apply postinference hooks
        return Seq.seq(hooksContainer.getMatchingPostInferenceHooks(parsedArgs.getPostInferenceHooks()))
                .foldLeft(mappedResult, (docs, hook) -> hook.process(getName(), docs));
    }

    static List<String> parseKeywords(final String jsonResponse) {
        return parseKeywords(jsonResponse, List.of());
    }

    static List<String> parseKeywords(final String jsonResponse, final List<String> excludedKeywords) {
        if (StringUtils.isBlank(jsonResponse)) {
            return List.of();
        }

        return Try.of(() -> OBJECT_MAPPER.readValue(jsonResponse, new TypeReference<List<String>>() {
                }))
                .map(keywords -> normalizeKeywords(keywords, excludedKeywords))
                .getOrElse(List.of());
    }

    static List<String> normalizeKeywords(final List<String> keywords, final List<String> excludedKeywords) {
        if (keywords == null) {
            return List.of();
        }

        final List<String> normalizedKeywords = normalizeSimpleKeywords(keywords);
        final List<String> normalizedExcludes = normalizeSimpleKeywords(excludedKeywords);

        return normalizedKeywords.stream()
                .filter(keyword -> normalizedExcludes.stream().noneMatch(excluded -> excluded.equalsIgnoreCase(keyword)))
                .toList();
    }

    private static List<String> normalizeSimpleKeywords(final List<String> keywords) {
        if (keywords == null) {
            return List.of();
        }

        return keywords.stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .distinct()
                .toList();
    }

    static String applyExclusionsToResponse(final String jsonResponse, final List<String> excludedKeywords) {
        return Try.of(() -> OBJECT_MAPPER.writeValueAsString(parseKeywords(jsonResponse, excludedKeywords)))
                .getOrElse(jsonResponse);
    }

    @Override
    public String getContextLabel() {
        return "Prompt";
    }

    @Override
    public int contextHashCode(final Map<String, String> environmentSettings, final List<String> prompts, final List<ToolArgs> arguments) {
        final KeywordsConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompts, environmentSettings);
        return 31 * parsedArgs.hashCode() + prompts.hashCode();
    }
}

@ApplicationScoped
class KeywordsConfig {
    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    @ConfigProperty(name = "sb.keywords.preprocessorHooks", defaultValue = "")
    private Optional<String> configPreprocessorHooks;

    @Inject
    @ConfigProperty(name = "sb.keywords.preinitializationHooks", defaultValue = "")
    private Optional<String> configPreinitializationHooks;

    @Inject
    @ConfigProperty(name = "sb.keywords.postinferenceHooks", defaultValue = "")
    private Optional<String> configPostInferenceHooks;

    @Inject
    @ConfigProperty(name = "sb.keywords.excludeKeywords", defaultValue = "")
    private Optional<String> configExcludeKeywords;

    @Inject
    private ToStringGenerator toStringGenerator;

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
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

    public Optional<String> getConfigExcludeKeywords() {
        return configExcludeKeywords;
    }

    public ToStringGenerator getToStringGenerator() {
        return toStringGenerator;
    }

    public class LocalArguments {
        private final List<ToolArgs> arguments;

        private final List<String> prompts;

        private final Map<String, String> environmentSettings;

        @Override
        public String toString() {
            return getToStringGenerator().generateGetterConfig(this);
        }

        @Override
        public int hashCode() {
            return getToStringGenerator().generateHashGetterConfig(this);
        }

        public LocalArguments(final List<ToolArgs> arguments, final List<String> prompts, final Map<String, String> environmentSettings) {
            this.arguments = List.copyOf(arguments);
            this.prompts = List.copyOf(prompts);
            this.environmentSettings = Map.copyOf(environmentSettings);
        }

        public String getPreprocessingHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPreprocessorHooks()::get,
                    arguments,
                    environmentSettings,
                    CommonArguments.PREPROCESSOR_HOOKS_ARG,
                    CommonArguments.PREPROCESSOR_HOOKS_ARG,
                    "").getSafeValue();
        }

        public String getPreinitializationHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPreinitializationHooks()::get,
                    arguments,
                    environmentSettings,
                    CommonArguments.PREINITIALIZATION_HOOKS_ARG,
                    CommonArguments.PREINITIALIZATION_HOOKS_ARG,
                    "").getSafeValue();
        }

        public String getPostInferenceHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPostInferenceHooks()::get,
                    arguments,
                    environmentSettings,
                    CommonArguments.POSTINFERENCE_HOOKS_ARG,
                    CommonArguments.POSTINFERENCE_HOOKS_ARG,
                    "").getSafeValue();
        }

        public List<String> getExcludeKeywords() {
            return getArgsAccessor().getArgumentList(
                            getConfigExcludeKeywords()::get,
                            arguments,
                            environmentSettings,
                            Keywords.EXCLUDE_KEYWORDS_ARG,
                            Keywords.EXCLUDE_KEYWORDS_ARG,
                            "")
                    .stream()
                    .map(Argument::value)
                    .filter(StringUtils::isNotBlank)
                    .map(String::trim)
                    .toList();
        }
    }
}
