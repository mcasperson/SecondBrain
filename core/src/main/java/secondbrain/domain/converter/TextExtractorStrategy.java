package secondbrain.domain.converter;

/**
 * Extracts text from supported files.
 */
public interface TextExtractorStrategy {
    String convert(String path);

    String convertContents(byte[] contents);

    boolean isSupported(String path);

    int priority();
}
