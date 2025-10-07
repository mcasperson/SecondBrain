package secondbrain.domain.config;

public interface LocalConfigFilteredParent {
    Integer getContextFilterMinimumRating();

    Integer getDefaultRating();

    boolean isContextFilterGreaterThan();
}
