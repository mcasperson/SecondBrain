package secondbrain.domain.limit;

import java.util.List;

public interface DocumentTrimmer {
    String trimDocument(String document, List<String> keywords);
}
