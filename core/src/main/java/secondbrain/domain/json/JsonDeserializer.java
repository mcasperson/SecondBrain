package secondbrain.domain.json;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.Map;

public interface JsonDeserializer {
    String serialize(Object object) throws JsonProcessingException;

    <T> T deserialize(String json, Class<T> clazz) throws JsonProcessingException;

    <U, V> Map<U, V> deserializeMap(String json, Class<U> key, Class<V> value) throws JsonProcessingException;
}
