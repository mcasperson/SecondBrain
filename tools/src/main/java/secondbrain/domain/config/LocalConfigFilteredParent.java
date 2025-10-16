package secondbrain.domain.config;

/**
 * Defines a set of configuration properties used for filtering based on ratings.
 */
public interface LocalConfigFilteredParent {
    /**
     * This is the value used to rate items against.
     *
     * @return The minimum rating to use when filtering items.
     */
    Integer getContextFilterMinimumRating();

    /**
     * In the event of an error, this is the default rating to use.
     *
     * @return The default rating to use in the event of an error.
     */
    Integer getDefaultRating();

    /**
     * Determines if the context filter rating must be "greater than" or "less than" the calculated rating.
     * <p>
     * If this is true, items will be filtered out if their rating is greater than the minimum rating (i.e. the context filter limit represents an upper limit).
     * If false, items will be filtered out if their rating is less than the minimum rating (i.e. the context filter limit represents a lower limit).
     *
     * @return True if items should be filtered out if their rating is greater than the minimum rating. False if items should be filtered out if their rating is less than the minimum rating.
     */
    boolean isContextFilterUpperLimit();
}
