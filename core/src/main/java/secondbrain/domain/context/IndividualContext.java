package secondbrain.domain.context;

/**
 * Represents the relationship between an external data source and its context. The purpose of this class is
 * to ensure that we can list the external sources that were used to generate the prompt context, allowing
 * users to verify the sources of the information.
 * <p>
 * IndividualContext is used as the results from various external sources are captured and processed. For example,
 * you would use IndividualContext to capture the results from a REST API call, a database query, or a web scrape.
 * <p>
 * Eventually, and IndividualContext is used to populate a RagDocumentContext. Unlike IndividualContext,
 * RagDocumentContext only captures a string as the context.
 *
 * @param id      The external source ID
 * @param context The external source context.
 */
public record IndividualContext<T>(String id, T context) {
}
