package secondbrain.domain.converter;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Comparator;

@ApplicationScoped
public class FileToTextContext implements FileToText {

    @Inject
    private Instance<TextExtractorStrategy> textExtractors;

    @Override
    public String convert(final String path) {
        return textExtractors
                .stream()
                .filter(extractor -> extractor.isSupported(path))
                // The extractor with the lowest priority number wins
                .min(Comparator.comparingInt(TextExtractorStrategy::priority))
                .map(extractor -> extractor.convert(path))
                .orElseGet(() -> Try.of(() -> Files.readString(Paths.get(path))).getOrNull());
    }

    @Override
    public String convertByteArray(final byte[] contents) {
        return textExtractors
                .stream()
                // The extractor with the lowest priority number wins
                .min(Comparator.comparingInt(TextExtractorStrategy::priority))
                .map(extractor -> extractor.convertContents(contents))
                .orElse(new String(contents));

    }
}
