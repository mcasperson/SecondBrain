package secondbrain.domain.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import secondbrain.domain.exceptions.DeserializationFailed;
import secondbrain.domain.exceptions.SerializationFailed;

import java.util.List;
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

    @Override
    public <U> List<U> deserializeCollection(final String json, final Class<U> value) {
        return Try.of(this::createObjectMapper)
                .mapTry(objectMapper -> objectMapper.<List<U>>readValue(
                        json,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, value)))
                .getOrElseThrow(ex -> new DeserializationFailed(ex));
    }

    @Override
    public <T, U> T deserializeGeneric(final String json, final Class<T> container, final Class<U> contained) {
        return Try.of(this::createObjectMapper)
                .mapTry(objectMapper -> objectMapper.<T>readValue(
                        json,
                        objectMapper.getTypeFactory().constructParametricType(container, contained)))
                .getOrElseThrow(ex -> new DeserializationFailed(ex));
    }

    @Override
    public <T, U, V> T deserializeGeneric(final String json, final Class<T> container, final Class<U> contained, final Class<V> contained2) {
        return Try.of(this::createObjectMapper)
                .mapTry(objectMapper -> objectMapper.<T>readValue(
                        json,
                        constructParametricType(objectMapper, container, contained, contained2)))
                .getOrElseThrow(ex -> new DeserializationFailed(ex));
    }

    private <T, U, V> JavaType constructParametricType(final ObjectMapper objectMapper, final Class<T> container, final Class<U> contained, final Class<V> contained2) {
        final JavaType inner = objectMapper.getTypeFactory().constructParametricType(contained, contained2);
        return objectMapper.getTypeFactory().constructParametricType(container, inner);
    }

    private ObjectMapper createObjectMapper() {
        final ObjectMapper objectMapper = new ObjectMapper();
        if (modules != null) {
            objectMapper.registerModules(modules);
        }
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return objectMapper;
    }
}
