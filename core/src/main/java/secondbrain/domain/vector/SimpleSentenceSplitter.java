package secondbrain.domain.vector;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class SimpleSentenceSplitter implements SentenceSplitter {
    @Override
    public List<String> splitDocument(final String document) {
        if (document == null) {
            return List.of();
        }

        return List.of(document.split("\\r\\n|\\r|\\n|\\.|;|!|\\?"));
    }
}
