package secondbrain.domain.reader;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

@ApplicationScoped
public class FileReaderSelector implements FileReader {

    @Inject
    private Instance<FileReadingStrategy> fileReadingStrategies;

    @Override
    public String read(final String path) {
        return fileReadingStrategies
                .stream()
                .sorted((a, b) -> Integer.compare(b.getPriority(), a.getPriority()))
                .filter(extractor -> extractor.isSupported(path))
                .findFirst()
                .map(extractor -> extractor.read(path))
                .get();
    }
}
