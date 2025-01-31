package secondbrain.domain.args;

/**
 * Represents an argument passed to a tool.
 *
 * @param value   The value of the argument
 * @param trusted Whether the argument value is trusted. System properties, context values, and default values
 *                are trusted. LLM generated arguments are not trusted. Untrusted values are subject to sanitization
 *                and validation.
 */
public record Argument(String value, boolean trusted) {
    public Argument replaceValue(String value) {
        return new Argument(value, trusted);
    }
}
