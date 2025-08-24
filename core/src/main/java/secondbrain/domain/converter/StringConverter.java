package secondbrain.domain.converter;

/**
 * Interface for converting a string response.
 */
public interface StringConverter {
    String getFormat();

    String convert(String response);
}
