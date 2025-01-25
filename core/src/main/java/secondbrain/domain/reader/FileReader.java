package secondbrain.domain.reader;

/**
 * Represents a service that reads a file from a path or URL.
 */
public interface FileReader {
    String read(String pathOrUrl);
}
