package secondbrain.domain.context;

/**
 * Represents the relationship between an external data source and its context. The purpose of this class is
 * to ensure that we can list the external sources that were used to generate the prompt context, allowing
 * users to verify the sources of the information.
 *
 * @param id      The external source ID
 * @param context The external source context.
 */
public record IndividualContext<T>(String id, T context) {
}
