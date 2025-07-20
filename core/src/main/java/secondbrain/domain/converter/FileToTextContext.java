package secondbrain.domain.converter;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.Paths;

@ApplicationScoped
public class FileToTextContext implements FileToText {

    @Inject
    private Instance<TextExtractorStrategy> textExtractors;

    @Override
    public String convert(final String path) {
        return textExtractors
                .stream()
                .sorted((a, b) -> Integer.compare(b.priority(), a.priority()))
                .filter(extractor -> extractor.isSupported(path))
                .findFirst()
                .map(extractor -> extractor.convert(path))
                .orElse(Try.of(() -> Files.readString(Paths.get(path))).getOrNull());
    }

    @Override
    public String convertString(final String contents) {
        return textExtractors
                .stream().min((a, b) -> Integer.compare(b.priority(), a.priority()))
                .map(extractor -> extractor.convertContents(contents))
                .orElse(contents);

    }
}
