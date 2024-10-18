package secondbrain.domain.limit;


import java.util.List;

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
}
