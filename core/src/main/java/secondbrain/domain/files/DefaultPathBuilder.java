package secondbrain.domain.files;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Path;
import java.nio.file.Paths;

@ApplicationScoped
public class DefaultPathBuilder implements PathBuilder {
    @Inject
    private FileSanitizer fileSanitizer;

    @Override
    public Path getFilePath(String directory, String path) {
        final Path directoryPath = Paths.get(fileSanitizer.sanitizeFilePath(path));
        if (directoryPath.isAbsolute()) {
            // Return the absolute path as is
            return directoryPath;
        } else if (StringUtils.isNotBlank(directory)) {
            // In this scenario we expect the directory to provide the final location of the file,
            // and the path to be a file name only.
            // This means when you define the directory, any file names will be sanitized and placed in that directory.
            return Paths.get(directory, fileSanitizer.sanitizeFileName(path));
        } else {
            // Return the relative path as is.
            return Paths.get(fileSanitizer.sanitizeFilePath(path));
        }
    }
}
