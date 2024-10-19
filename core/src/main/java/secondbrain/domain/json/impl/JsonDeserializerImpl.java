package secondbrain.domain.json.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;
import jakarta.enterprise.context.ApplicationScoped;
import secondbrain.domain.json.JsonDeserializer;

import java.util.Map;

/**
 * A service for serializing and deserializing JSON.
 */
@ApplicationScoped
public class JsonDeserializerImpl implements JsonDeserializer {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String serialize(final Object object) throws JsonProcessingException {
        return objectMapper.writeValueAsString(object);
    }

    @Override
    public <T> T deserialize(final String json, final Class<T> clazz) throws JsonProcessingException {
        return objectMapper.readValue(json, clazz);
    }

    @Override
    public <U, V> Map<U, V> deserializeMap(final String json, final Class<U> key, final Class<V> value) throws JsonProcessingException {
        final MapType type = objectMapper.getTypeFactory().constructMapType(Map.class, key, value);
        return objectMapper.readValue(json, type);
    }
}
