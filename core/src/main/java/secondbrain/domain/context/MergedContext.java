package secondbrain.domain.context;

import java.util.List;

/**
 * Represents the relationship between multiple external data sources and their combined context.
 * <p>
 * Like IndividualContext, the purpose of this class is to ensure that we can list the external sources that were
 * used to generate the prompt context, allowing users to verify the sources of the information.
 *
 * @param ids     A list of external source IDs
 * @param context The combined context of the external sources.
 */
public record MergedContext(List<String> ids, String context) {
}
