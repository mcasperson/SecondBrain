package secondbrain.domain.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.blackbird.BlackbirdModule;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import secondbrain.domain.exceptions.DeserializationFailed;
import secondbrain.domain.exceptions.SerializationFailed;
import secondbrain.domain.persist.TimedOperation;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * A service for serializing and deserializing JSON.
 */
@ApplicationScoped
public class JsonDeserializerJackson implements JsonDeserializer {

    @Inject
    private Instance<SimpleModule> modules;

    @Inject
    private Logger logger;

    @Override
    public String serialize(final Object object) {
        return Try.of(this::createObjectMapper)
                .mapTry(objectMapper -> objectMapper.writeValueAsString(object))
                .onFailure(ex -> logger.warning("Failed to serialize object of type " + object.getClass().getSimpleName() + ": " + ex.getMessage()))
                .getOrElseThrow(ex -> new SerializationFailed(ex));
    }

    @Override
    public <T> T deserialize(final String json, final Class<T> clazz) {
        return Try.withResources(() -> new TimedOperation("Deserialize string " + clazz.getSimpleName()))
                .of(t -> deserializeTimed(json, clazz))
                .get();
    }

    private <T> T deserializeTimed(final String json, final Class<T> clazz) {
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
                .onFailure(ex -> logger.warning("Failed to deserialize map of type " + key.getSimpleName() + ": " + ex.getMessage()))
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
                .onFailure(ex -> logger.warning("Failed to deserialize object of type " + container.getSimpleName() + " containing type " + contained.getSimpleName() + ": " + ex.getMessage()))
                .getOrElseThrow(ex -> new DeserializationFailed(ex));
    }

    @Override
    public <T, U, V> T deserializeGeneric(final String json, final Class<T> container, final Class<U> contained, final Class<V> contained2) {
        return Try.of(this::createObjectMapper)
                .mapTry(objectMapper -> objectMapper.<T>readValue(
                        json,
                        constructParametricType(objectMapper, container, contained, contained2)))
                .onFailure(ex -> logger.warning("Failed to deserialize object of type " + container.getSimpleName() + " containing type " + contained.getSimpleName() + " containing type " + contained2.getSimpleName() + ": " + ex.getMessage()))
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
        objectMapper.registerModule(new BlackbirdModule());
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return objectMapper;
    }
}
