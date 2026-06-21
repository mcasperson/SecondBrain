package secondbrain.infrastructure.slack.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import org.jspecify.annotations.Nullable;

import java.io.IOException;

/**
 * A lenient Integer deserializer that handles cases where an Integer field
 * is serialized as a JSON object (e.g., from database extended JSON formats)
 * instead of a plain number. When an object is encountered, it skips the
 * object content and returns null.
 */
public class LenientIntegerDeserializer extends StdDeserializer<Integer> {
    public LenientIntegerDeserializer() {
        super(Integer.class);
    }

    @Override
    public @Nullable Integer deserialize(final JsonParser p, final DeserializationContext ctxt) throws IOException {
        if (p.currentToken() == JsonToken.START_OBJECT) {
            p.skipChildren();
            return null;
        }
        if (p.currentToken() == JsonToken.START_ARRAY) {
            p.skipChildren();
            return null;
        }
        if (p.currentToken() == JsonToken.VALUE_NULL) {
            return null;
        }
        if (p.currentToken() == JsonToken.VALUE_STRING) {
            final String text = p.getText();
            if (text == null || text.isEmpty()) {
                return null;
            }
            try {
                return Integer.parseInt(text);
            } catch (final NumberFormatException e) {
                return null;
            }
        }
        return p.getIntValue();
    }
}
