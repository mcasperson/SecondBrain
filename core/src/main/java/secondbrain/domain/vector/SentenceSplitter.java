package secondbrain.domain.vector;

import java.util.List;

public interface SentenceSplitter {
    List<String> splitDocument(String document);
}
