package secondbrain.domain.converter;

/**
 * Extracts text from supported files.
 */
public interface TextExtractorStrategy {
    String convert(String path);

    String convertContents(byte[] contents);

    boolean isSupported(String path);

    /**
     * When more than one extractor supports a file, the extractor with the lowest priority number is used.
     */
    int priority();
}
