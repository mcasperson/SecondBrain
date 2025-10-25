package secondbrain.domain.config;

import java.util.List;

/**
 * Represents the configuration required to extract keywords from content.
 */
public interface LocalConfigKeywords {
    List<String> getKeywords();

    int getKeywordWindow();
}
