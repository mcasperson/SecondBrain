package secondbrain.domain.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

@ApplicationScoped
public class JsonDeserializer {
    private static final ObjectMapper objectMapper = new ObjectMapper();


    public String serialize(final Object object) throws JsonProcessingException {
        return objectMapper.writeValueAsString(object);
    }


    public <T> T deserialize(final String json, final Class<T> clazz) throws JsonProcessingException {
        return objectMapper.readValue(json, clazz);
    }


    public <U, V> Map<U, V> deserializeMap(final String json, final Class<U> key, final Class<V> value) throws JsonProcessingException {

        final MapType type = objectMapper.getTypeFactory().constructMapType(Map.class, key, value);
        return objectMapper.readValue(json, type);
    }
}
