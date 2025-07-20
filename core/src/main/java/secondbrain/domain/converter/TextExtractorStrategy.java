package secondbrain.domain.converter;

/**
 * Extracts text from supported files.
 */
public interface TextExtractorStrategy {
    String convert(String path);

    String convertContents(String contents);

    boolean isSupported(String path);

    int priority();
}
