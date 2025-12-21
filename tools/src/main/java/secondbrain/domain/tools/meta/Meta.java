package secondbrain.domain.tools.meta;

import com.google.common.collect.ImmutableList;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptionhandling.ExceptionMapping;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.persist.CacheResult;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.infrastructure.llm.LlmClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * This tool combines many sub-tools to allow a single prompt to be answered by multiple different
 * data sources.
 */
@ApplicationScoped
public class Meta implements Tool<Void> {
    public static final String META_TOOL_NAMES_ARG = "toolNames";
    public static final String META_TTL_SECONDS_ARG = "ttlSeconds";

    @Inject
    private Instance<Tool<?>> tools;

    @Inject
    private MetaConfig config;

    @Inject
    private Logger logger;

    @Inject
    @Preferred
    private LocalStorage localStorage;

    @Inject
    @Preferred
    private LlmClient llmClient;

    @Inject
    private ExceptionMapping exceptionMapping;

    @Override
    public String getName() {
        return Meta.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "A tool that combines multiple tools to provide meta-level information and functionality.";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return ImmutableList.of(
                new ToolArguments(META_TOOL_NAMES_ARG, "Comma-separated list of tool names to include", ""),
                new ToolArguments(META_TTL_SECONDS_ARG, "The number of seconds to cache the result", "604800")
        );
    }

    @Override
    public List<RagDocumentContext<Void>> getContext(final Map<String, String> environmentSettings, final String prompt, List<ToolArgs> arguments) {
        final MetaConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        // Use the parsed tool names
        final List<String> toolNames = parsedArgs.getToolNames();

        // Filter the tools to those included in the toolNames
        final List<Tool<?>> filteredTools = tools.stream()
                .filter(tool -> toolNames.contains(tool.getName()))
                .filter(tool -> !tool.getName().equals(Meta.class.getSimpleName()))
                .toList();

        // Combine the results of getContext calls on all filtered tools
        return filteredTools.stream()
                .flatMap(tool -> tool.getContext(environmentSettings, prompt, arguments).stream())
                .map(RagDocumentContext::convertToRagDocumentContextVoid)
                .toList();
    }

    @Override
    public RagMultiDocumentContext<Void> call(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        logger.fine("Calling " + getName());

        final MetaConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        logger.info(parsedArgs.toString());

        final String cacheKey = generateCacheKey(parsedArgs, prompt);

        final CacheResult<RagMultiDocumentContext> result = localStorage.getOrPutObject(
                getName(),
                getName(),
                cacheKey,
                parsedArgs.getCacheTtl(),
                RagMultiDocumentContext.class,
                () -> callPrivate(environmentSettings, prompt, arguments));

        if (result.fromCache()) {
            logger.info("Cache hit for " + getName() + " " + cacheKey);
        } else {
            logger.info("Cache miss for " + getName() + " " + cacheKey);
        }

        return result.result()
                .convertToRagMultiDocumentContextVoid();
    }

    private String generateCacheKey(final MetaConfig.LocalArguments parsedArgs, final String prompt) {
        return parsedArgs.toString().hashCode() + "_" + prompt.hashCode();
    }

    private RagMultiDocumentContext<Void> callPrivate(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {
        final MetaConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> getContext(environmentSettings, prompt, arguments))
                .map(context -> new RagMultiDocumentContext<Void>(
                        prompt,
                        "You are a helpful agent. You have information from multiple tools. Answer the prompt based on the provided information.",
                        context,
                        null,
                        null,
                        "",
                        null))
                .map(ragDoc -> llmClient.callWithCache(ragDoc, environmentSettings, getName()));

        return exceptionMapping.map(result).get();
    }

    @Override
    public String getContextLabel() {
        return "Unused";
    }
}

@ApplicationScoped
class MetaConfig {
    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private ToStringGenerator toStringGenerator;

    @Inject
    @ConfigProperty(name = "sb.meta.toolNames")
    private Optional<String> configToolNames;

    @Inject
    @ConfigProperty(name = "sb.meta.ttlSeconds")
    private Optional<String> configTtlSeconds;

    public Optional<String> getConfigToolNames() {
        return configToolNames;
    }

    public Optional<String> getConfigTtlSeconds() {
        return configTtlSeconds;
    }

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    public ToStringGenerator getToStringGenerator() {
        return toStringGenerator;
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

        public String toString() {
            return getToStringGenerator().generateGetterConfig(this);
        }

        public List<String> getToolNames() {
            return getArgsAccessor().getArgumentList(
                            getConfigToolNames()::get,
                            arguments,
                            context,
                            Meta.META_TOOL_NAMES_ARG,
                            Meta.META_TOOL_NAMES_ARG,
                            "")
                    .stream()
                    .map(Argument::value)
                    .toList();
        }

        public int getCacheTtl() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigTtlSeconds()::get,
                    arguments,
                    context,
                    Meta.META_TTL_SECONDS_ARG,
                    Meta.META_TTL_SECONDS_ARG,
                    "604800");

            return Math.max(0, Integer.parseInt(argument.value()));
        }
    }
}
