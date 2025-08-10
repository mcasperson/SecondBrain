package secondbrain.domain.tools.alias;

import io.smallrye.common.annotation.Identifier;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.FailedOllama;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.sanitize.SanitizeDocument;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.infrastructure.llm.LlmClient;

import java.util.List;
import java.util.Map;

import static com.google.common.base.Predicates.instanceOf;

/**
 * AliasTool generates aliases for a named entity based on the provided context.
 */
@ApplicationScoped
public class AliasTool implements Tool<Void> {
    private static final String INSTRUCTIONS = """
            You are a helpful assistant.
            You are given the name of an entity.
            You must generate a list of aliases for the entity based on the context provided.
            You will be penalized for returning generic or irrelevant aliases.
            You will be penalized for returning markdown or any other formatting.
            The response must be a JSON array of strings, with each string being an alias.
            You will be penalized for returning any text in the response that is not a valid JSON array.
            For example, if the entity is "Microsoft", you might return ["Microsoft Corporation", "MSFT"].
            If the entity has no aliases, return an empty array: [].""".stripLeading();

    @Inject
    private AliasConfig config;

    @Inject
    @Preferred
    private LlmClient llmClient;

    @Inject
    @Identifier("findFirstMarkdownBlock")
    private SanitizeDocument findFirstMarkdownBlock;

    @Override
    public String getName() {
        return AliasTool.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Generates a list of aliases for a named entity";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return List.of();
    }

    @Override
    public List<RagDocumentContext<Void>> getContext(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        return List.of();
    }

    @Override
    public RagMultiDocumentContext<Void> call(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> getContext(environmentSettings, prompt, arguments))
                .map(ragDoc -> new RagMultiDocumentContext<>(prompt, INSTRUCTIONS, ragDoc))
                .map(ragDoc -> llmClient.callWithCache(
                        ragDoc,
                        environmentSettings,
                        getName()))
                /*
                 We expect a JSON array, but some LLMs return markdown blocks, which we
                 */
                .map(ragDoc -> ragDoc.updateResponse(findFirstMarkdownBlock.sanitize(ragDoc.getResponse()).trim()));

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(
                        API.Case(API.$(instanceOf(EmptyString.class)), throwable -> new InternalFailure("The entity name was empty")),
                        API.Case(API.$(instanceOf(InternalFailure.class)), throwable -> throwable),
                        API.Case(API.$(instanceOf(FailedOllama.class)), throwable -> new InternalFailure(throwable.getMessage(), throwable)),
                        API.Case(API.$(), ex -> new ExternalFailure(getName() + " failed to call Ollama", ex)))
                .get();
    }

    @Override
    public String getContextLabel() {
        return "Entity Name";
    }
}

@ApplicationScoped
class AliasConfig {
    @Inject
    private ArgsAccessor argsAccessor;

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    public class LocalArguments {
        private final List<ToolArgs> arguments;

        private final String prompt;

        private final Map<String, String> environmentSettings;

        public LocalArguments(final List<ToolArgs> arguments, final String prompt, final Map<String, String> environmentSettings) {
            this.arguments = arguments;
            this.prompt = prompt;
            this.environmentSettings = environmentSettings;
        }
    }
}
