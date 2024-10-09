package secondbrain.application.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Preconditions;
import jakarta.inject.Inject;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Cookie;
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
                      @CookieParam("session") final Cookie session) throws JsonProcessingException {

        Preconditions.checkNotNull(promptHandler, "promptHandler must not be null");
        Preconditions.checkNotNull(jsonDeserializer, "jsonDeserializer must not be null");

        final Map<String, String> context = session == null
                ? Map.of()
                : jsonDeserializer.deserializeMap(session.getValue(), String.class, String.class);

        return promptHandler.handlePrompt(context, Objects.requireNonNullElse(prompt, ""));
    }
}