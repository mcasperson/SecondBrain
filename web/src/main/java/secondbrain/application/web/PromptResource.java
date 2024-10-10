package secondbrain.application.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Preconditions;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import secondbrain.domain.handler.PromptHandler;
import secondbrain.domain.json.JsonDeserializer;

import java.util.Map;
import java.util.Objects;

@Path("promptweb")
public class PromptResource {
    @Inject
    private PromptHandler promptHandler;

    @Inject
    private JsonDeserializer jsonDeserializer;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public String getWeb(@QueryParam("prompt") final String prompt,
                         @CookieParam("session") final Cookie session,
                         final Map<String, String> context) throws JsonProcessingException {

        Preconditions.checkNotNull(promptHandler, "promptHandler must not be null");
        Preconditions.checkNotNull(jsonDeserializer, "jsonDeserializer must not be null");

        final Map<String, String> cookieContext = session == null
                ? Map.of()
                : jsonDeserializer.deserializeMap(session.getValue(), String.class, String.class);

        cookieContext.putAll(Objects.requireNonNullElse(context, Map.of()));

        return promptHandler.handlePrompt(cookieContext, Objects.requireNonNullElse(prompt, ""));
    }
}