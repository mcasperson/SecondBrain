package secondbrain.domain.yaml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import secondbrain.domain.exceptions.DeserializationFailed;

/**
 * A YAML deserializer that uses Jackson.
 */
@ApplicationScoped
public class YamlDeserializerJackson implements YamlDeserializer {
    final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    public YamlDeserializerJackson() {
        mapper.findAndRegisterModules();
    }

    @Override
    public <T> T deserialize(String source, Class<T> clazz) {
        return Try.of(() -> mapper.readValue(source, clazz))
                .getOrElseThrow(ex -> new DeserializationFailed(ex));
    }
}
