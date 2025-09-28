package secondbrain.domain.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import secondbrain.domain.exceptions.DeserializationFailed;
import secondbrain.domain.exceptions.SerializationFailed;

import java.util.Map;

/**
 * A service for serializing and deserializing JSON.
 */
@ApplicationScoped
public class JsonDeserializerJackson implements JsonDeserializer {

    @Inject
    private Instance<SimpleModule> modules;

    @Override
    public String serialize(final Object object) {
        return Try.of(this::createObjectMapper)
                .mapTry(objectMapper -> objectMapper.writeValueAsString(object))
                .getOrElseThrow(ex -> new SerializationFailed(ex));
    }

    @Override
    public <T> T deserialize(final String json, final Class<T> clazz) {
        return Try.of(this::createObjectMapper)
                .mapTry(objectMapper -> objectMapper.readValue(json, clazz))
                .getOrElseThrow(ex -> new SerializationFailed(ex));
    }

    @Override
    public <U, V> Map<U, V> deserializeMap(final String json, final Class<U> key, final Class<V> value) {
        return Try.of(this::createObjectMapper)
                .mapTry(objectMapper -> objectMapper.<Map<U, V>>readValue(
                        json,
                        objectMapper.getTypeFactory().constructMapType(Map.class, key, value)))
                .getOrElseThrow(ex -> new DeserializationFailed(ex));
    }

    private ObjectMapper createObjectMapper() {
        final ObjectMapper objectMapper = new ObjectMapper();
        if (modules != null) {
            objectMapper.registerModules(modules);
        }
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return objectMapper;
    }
}
