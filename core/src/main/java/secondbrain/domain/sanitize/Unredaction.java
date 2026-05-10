package secondbrain.domain.sanitize;

/**
 * This interface defines an "undo" for certain types of redactions.
 * Typically, this is used to ensure things like IDs are not manipulated as part of a redaction process.
 */
public interface Unredaction {
    String unredact(final String original, final String redacted);
}
