package secondbrain.domain.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.constraints.NotNull;

@ApplicationScoped
public class JsonDeserializer {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @NotNull
    public <T> T deserialize(@NotNull final String json, @NotNull final Class<T> clazz) throws JsonProcessingException {
        return objectMapper.readValue(json, clazz);
    }
}
