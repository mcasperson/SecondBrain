package secondbrain.application.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Application;
import secondbrain.domain.handler.PromptHandler;
import secondbrain.domain.json.JsonDeserializer;

import java.util.Map;

@ApplicationPath("/api")
public class RestApplication extends Application {

    @Inject
    private PromptHandler promptHandler;

    @Inject
    private JsonDeserializer jsonDeserializer;

    @GET
    @Path("prompt")
    @QueryParam("prompt")
    @HeaderParam("context")
    public String get(@QueryParam("prompt") final String prompt,
                      @QueryParam("context") final String context) throws JsonProcessingException {

        final Map<String, String> contextMap = jsonDeserializer.deserializeMap(
                context, String.class, String.class);
        return promptHandler.handlePrompt(contextMap, prompt);
    }
}