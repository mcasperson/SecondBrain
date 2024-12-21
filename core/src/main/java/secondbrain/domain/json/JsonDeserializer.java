package secondbrain.domain.json;

import java.util.Map;

public interface JsonDeserializer {
    String serialize(Object object);

    <T> T deserialize(String json, Class<T> clazz);

    <U, V> Map<U, V> deserializeMap(String json, Class<U> key, Class<V> value);
}
