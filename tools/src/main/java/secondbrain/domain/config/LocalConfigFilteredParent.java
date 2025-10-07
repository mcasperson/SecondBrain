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
     * If this is true, items will be filtered out if their rating is less than the minimum rating.
     * If false, items will be filtered out if their rating is greater than the minimum rating
     *
     * @return True if items should be filtered out if their rating is less than the minimum rating. False if items should be filtered out if their rating is greater than the minimum rating.
     */
    boolean isContextFilterGreaterThan();
}
