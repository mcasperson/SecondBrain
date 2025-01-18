package secondbrain.domain.converter;

/**
 * Extracts text from supported files.
 */
public interface TextExtractorStrategy {
    String convert(String path);

    boolean isSupported(String path);

    int priority();
}
