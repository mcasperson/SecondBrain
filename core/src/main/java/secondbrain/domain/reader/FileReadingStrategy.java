package secondbrain.domain.reader;

public interface FileReadingStrategy {
    String read(String pathOrUrl);

    boolean isSupported(String pathOrUrl);

    int getPriority();
}
