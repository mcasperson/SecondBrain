package secondbrain.domain.json;

import java.util.List;
import java.util.Map;

public interface JsonDeserializer {
    String serialize(Object object);

    <T> T deserialize(String json, Class<T> clazz);

    <U, V> Map<U, V> deserializeMap(String json, Class<U> key, Class<V> value);

    <U> List<U> deserializeCollection(String json, Class<U> value);

    <T, U> T deserializeGeneric(String json, Class<T> container, Class<U> contained);

    <T, U, V> T deserializeGeneric(String json, Class<T> container, Class<U> contained, Class<V> contained2);
}
