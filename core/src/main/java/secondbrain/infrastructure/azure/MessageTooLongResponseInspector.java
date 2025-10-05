package secondbrain.infrastructure.azure;

import io.smallrye.common.annotation.Identifier;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import secondbrain.domain.json.JsonDeserializer;
import secondbrain.domain.response.ResponseInspector;
import secondbrain.infrastructure.azure.api.AzureResponse;
import secondbrain.infrastructure.azure.api.AzureResponseError;

@ApplicationScoped
@Identifier("MessageTooLongResponseInspector")
public class MessageTooLongResponseInspector implements ResponseInspector {

    @Inject
    private JsonDeserializer jsonDeserializer;

    @Override
    public boolean isMatch(String response) {
        return Try.of(() -> jsonDeserializer.deserialize(response, AzureResponse.class))
                .map(AzureResponse::error)
                .map(AzureResponseError::message)
                .map(message -> StringUtils.contains(message, "Please reduce the length of the messages"))
                .getOrElse(false);
    }
}
