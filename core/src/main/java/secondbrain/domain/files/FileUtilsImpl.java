package secondbrain.domain.files;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

@ApplicationScoped
public class FileUtilsImpl implements FileUtils {
    @Inject
    private Logger logger;

    @Override
    public Path createDirectoryIfNotExists(final String directory) {
        return createDirectoryIfNotExists(Paths.get(directory));
    }

    @Override
    public Path createDirectoryIfNotExists(final Path directory) {
        final Path path = directory.toAbsolutePath();

        if (!Files.exists(path)) {
            Try.run(() -> Files.createDirectories(path))
                    .onFailure(ex -> logger.warning("Failed to create directory " + path + ": " + ex.getMessage()));
        }

        return path;
    }
}
