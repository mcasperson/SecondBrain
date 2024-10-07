package secondbrain.domain.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

@ApplicationScoped
public class JsonDeserializer {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @NotNull
    public <T> T deserialize(@NotNull final String json, @NotNull final Class<T> clazz) throws JsonProcessingException {
        return objectMapper.readValue(json, clazz);
    }

    @NotNull
    public <T, U, V> T deserializeMap(@NotNull final String json, @NotNull final Class<U> key, @NotNull final Class<V> value) throws JsonProcessingException {

        final MapType type = objectMapper.getTypeFactory().constructMapType(Map.class, key, value);
        return objectMapper.readValue(json, type);
    }
}