package secondbrain.domain.limit;

import java.util.List;

public record TrimResult(String document, List<String> keywordMatches) {
    public TrimResult() {
        this("", List.of());
    }

    public TrimResult replaceDocument(final String document) {
        return new TrimResult(document, keywordMatches);
    }
}
