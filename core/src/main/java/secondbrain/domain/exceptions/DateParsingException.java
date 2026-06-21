package secondbrain.domain.exceptions;

public class DateParsingException extends RuntimeException implements InternalException {
    public DateParsingException(String message) {
        super(message);
    }
}
