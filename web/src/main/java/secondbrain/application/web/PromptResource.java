package secondbrain.application.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Preconditions;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import secondbrain.domain.handler.PromptHandler;
import secondbrain.domain.json.JsonDeserializer;

import java.util.Map;
import java.util.Objects;

@Path("/prompt")
public class PromptResource {
    @Inject
    private PromptHandler promptHandler;

    @Inject
    private JsonDeserializer jsonDeserializer;

    @GET
    public String get(@QueryParam("prompt") final String prompt,
                      @HeaderParam("context") final String context) throws JsonProcessingException {

        Preconditions.checkNotNull(promptHandler, "promptHandler must not be null");
        Preconditions.checkNotNull(jsonDeserializer, "jsonDeserializer must not be null");

        final Map<String, String> contextMap = jsonDeserializer.deserializeMap(
                Objects.requireNonNullElse(context, "{}"), String.class, String.class);
        return promptHandler.handlePrompt(contextMap, Objects.requireNonNullElse(prompt, ""));
    }
}