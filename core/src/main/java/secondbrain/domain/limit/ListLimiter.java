package secondbrain.domain.limit;


import secondbrain.domain.context.IndividualContext;

import java.util.List;
import java.util.function.Function;

/**
 * A service that limits the content of a list.
 */
public interface ListLimiter {
    /**
     * Limits the content of a list to a specified length.
     *
     * @param list  The list to limit
     * @param limit The maximum length of the list
     * @return The list with content limited to the specified length
     */

    List<String> limitListContent(List<String> list, int limit);

    /**
     * Limits the content of a list with complex objects to a specified length.
     *
     * @param list       The list to limit
     * @param getContext A callback to get the string value of the items in the list
     * @param limit      The maximum length of the list
     * @param <T>        The type of the items in the list
     * @return The list with content limited to the specified length
     */
    <T> List<T> limitListContent(List<T> list, Function<T, String> getContext, int limit);

    /**
     * Limits the content of a list of IndividualContext items to a specified length.
     *
     * @param list  The list to limit
     * @param limit The maximum length of the list
     * @return The list with content limited to the specified length
     */
    <U> List<IndividualContext<String, U>> limitIndividualContextListContent(List<IndividualContext<String, U>> list, int limit);
}
