package secondbrain.domain.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import secondbrain.domain.exceptions.DeserializationFailed;
import secondbrain.domain.exceptions.SerializationFailed;

import java.util.Map;

/**
 * A service for serializing and deserializing JSON.
 */
@ApplicationScoped
public class JsonDeserializerJackson implements JsonDeserializer {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String serialize(final Object object) {
        return Try.of(() -> objectMapper.writeValueAsString(object))
                .getOrElseThrow(ex -> new SerializationFailed(ex));
    }

    @Override
    public <T> T deserialize(final String json, final Class<T> clazz) {
        return Try.of(() -> objectMapper.readValue(json, clazz))
                .getOrElseThrow(ex -> new SerializationFailed(ex));
    }

    @Override
    public <U, V> Map<U, V> deserializeMap(final String json, final Class<U> key, final Class<V> value) {
        final MapType type = objectMapper.getTypeFactory().constructMapType(Map.class, key, value);
        return Try.of(() -> objectMapper.<Map<U, V>>readValue(json, type))
                .getOrElseThrow(ex -> new DeserializationFailed(ex));
    }
}
