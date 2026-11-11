package secondbrain.domain.files;

import java.nio.file.Path;

/**
 * Utility operations for working with files and directories.
 */
public interface FileUtils {
    /**
     * Creates the directory, including any missing parent directories, if it does not already exist.
     * Failures are logged rather than thrown.
     *
     * @param directory The directory path
     * @return The absolute path to the directory
     */
    Path createDirectoryIfNotExists(String directory);

    /**
     * Creates the directory, including any missing parent directories, if it does not already exist.
     * Failures are logged rather than thrown.
     *
     * @param directory The directory path
     * @return The absolute path to the directory
     */
    Path createDirectoryIfNotExists(Path directory);
}
