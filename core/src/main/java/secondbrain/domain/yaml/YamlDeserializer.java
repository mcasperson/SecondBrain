package secondbrain.domain.yaml;

/**
 * Defines a service for deserializing YAML.
 */
public interface YamlDeserializer {
    <T> T deserialize(String source, Class<T> clazz);
}
