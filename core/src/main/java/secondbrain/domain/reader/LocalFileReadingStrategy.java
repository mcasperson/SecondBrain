package secondbrain.domain.reader;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.io.FileUtils;
import secondbrain.domain.exceptions.InvalidFile;

import java.io.File;
import java.nio.charset.StandardCharsets;

@ApplicationScoped
public class LocalFileReadingStrategy implements FileReadingStrategy {
    @Override
    public String read(final String pathOrUrl) {
        return Try.of(() -> FileUtils.readFileToString(new File(pathOrUrl), StandardCharsets.UTF_8))
                .getOrElseThrow(() -> new InvalidFile("Failed to read document from " + pathOrUrl));
    }

    @Override
    public boolean isSupported(final String pathOrUrl) {
        return FileUtils.isRegularFile(new File(pathOrUrl));
    }

    @Override
    public int getPriority() {
        return 200;
    }
}
