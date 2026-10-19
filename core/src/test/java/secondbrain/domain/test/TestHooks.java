package secondbrain.domain.test;

import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.hooks.PostInferenceHook;
import secondbrain.domain.hooks.PreProcessingHook;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Named hooks with easily observed effects. Expose them with @Produces methods so a HooksContainer can find them.
 */
public final class TestHooks {
    private TestHooks() {
    }

    /**
     * A pre-processing hook that appends a document with the supplied label and text. The document ID is the hook name,
     * as tools require an ID to rate documents.
     */
    public static PreProcessingHook appendDocument(final String name, final String label, final String text) {
        return new PreProcessingHook() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public <T> List<RagDocumentContext<T>> process(final String toolName, final List<RagDocumentContext<T>> contexts) {
                return Stream.concat(contexts.stream(), Stream.of(new RagDocumentContext<T>(toolName, label, text, List.of(), name))).toList();
            }
        };
    }

    /**
     * A pre-processing hook that converts every document to upper case.
     */
    public static PreProcessingHook upperCase(final String name) {
        return new PreProcessingHook() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public <T> List<RagDocumentContext<T>> process(final String toolName, final List<RagDocumentContext<T>> contexts) {
                return contexts.stream().map(c -> c.updateDocument(c.document().toUpperCase(Locale.ROOT))).toList();
            }
        };
    }

    /**
     * A post-inference hook that appends the suffix to the response.
     */
    public static PostInferenceHook appendResponse(final String name, final String suffix) {
        return new PostInferenceHook() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public <T> RagMultiDocumentContext<T> process(final String toolName, final RagMultiDocumentContext<T> ragMultiDocumentContext) {
                return ragMultiDocumentContext.updateResponse(ragMultiDocumentContext.getResponse() + suffix);
            }
        };
    }
}
