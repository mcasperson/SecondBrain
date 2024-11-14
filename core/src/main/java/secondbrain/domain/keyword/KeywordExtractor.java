package secondbrain.domain.keyword;

import java.util.List;

public interface KeywordExtractor {
    List<String> getKeywords(String text);
}
