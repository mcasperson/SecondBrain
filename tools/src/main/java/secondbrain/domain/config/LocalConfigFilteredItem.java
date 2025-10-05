package secondbrain.domain.config;

/**
 * Represents the configuration required to rate items based on a filtering prompt.
 */
public interface LocalConfigFilteredItem {

    String getContextFilterQuestion();

    Integer getDefaultRating();

    Integer getContextFilterMinimumRating();
}
