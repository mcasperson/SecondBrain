package secondbrain.domain.context;

/**
 * Represents the relationship between an external data source and its context. The purpose of this class is
 * to ensure that we can list the external sources used to generate the prompt context, allowing
 * users to verify the sources of the information.
 * <p>
 * IndividualContext is used to capture the native results from various external sources are captured and processed.
 * For example, you would use IndividualContext to capture the results from a REST API call, a database query,
 * or a web scrape, and save the POJO that captures the result as the context.
 * <p>
 * Eventually, and IndividualContext is used to populate a RagDocumentContext. Unlike IndividualContext,
 * RagDocumentContext only captures a string as the context. LLMs can only process strings, so
 * RagDocumentContext is the next step between the raw context captured by IndividualContext and the
 * final prompt sent to the LLM.
 *
 * @param id      The external source ID
 * @param context The external source context.
 */
public record IndividualContext<T, U>(String id, T context, U meta) {
    /**
     * Updates the context of the IndividualContext. The type of the context is frequently changed
     * as the context is processed and transformed.
     *
     * @param context The new context
     * @return A new copy of this object with the new context
     */
    public <W> IndividualContext<W, U> updateContext(W context) {
        return new IndividualContext<>(id, context, meta);
    }
}
