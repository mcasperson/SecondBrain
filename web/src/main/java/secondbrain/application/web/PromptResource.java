package secondbrain.application.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Preconditions;
import io.vavr.control.Try;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.collections4.map.PassiveExpiringMap;
import org.apache.commons.lang3.StringUtils;
import secondbrain.domain.handler.PromptHandler;
import secondbrain.domain.json.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Path("/")
public class PromptResource {
    /**
     * This is an in memory cache of the results of the prompt handler.
     */
    private static final PassiveExpiringMap<String, String> RESULTS = new PassiveExpiringMap<>(
            new PassiveExpiringMap.ConstantTimeToLiveExpirationPolicy<>(5, TimeUnit.MINUTES)
    );

    @Inject
    private PromptHandler promptHandler;

    @Inject
    private JsonDeserializer jsonDeserializer;

    @POST
    @Path("promptweb")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public String getWeb(@QueryParam("prompt") final String prompt,
                         @CookieParam("session") final Cookie session,
                         final Map<String, String> context) throws JsonProcessingException {

        Preconditions.checkNotNull(promptHandler, "promptHandler must not be null");
        Preconditions.checkNotNull(jsonDeserializer, "jsonDeserializer must not be null");

        final Map<String, String> cookieContext = Try.of(() -> session)
                .mapTry(Objects::requireNonNull)
                .mapTry(cookie -> new String(new Base64().decode(cookie.getValue().getBytes())))
                .mapTry(s -> jsonDeserializer.deserializeMap(s, String.class, String.class))
                .recover(throwable -> Map.of())
                .get();

        final Map<String, String> filteredContext = Objects.requireNonNullElse(context, Map.<String, String>of())
                .entrySet()
                .stream()
                .filter(entry -> StringUtils.isNotBlank(entry.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        final Map<String, String> combinedContext = new HashMap<>(cookieContext);
        combinedContext.putAll(filteredContext);

        final String resultKey = UUID.randomUUID().toString();

        Thread.startVirtualThread(() -> {
            final String result = promptHandler.handlePrompt(combinedContext, Objects.requireNonNullElse(prompt, ""));
            synchronized (RESULTS) {
                RESULTS.put(resultKey, result);
            }
        });

        return resultKey;
    }

    @GET
    @Path("/results/{resultKey}")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getResult(@PathParam("resultKey") final String resultKey) {
        synchronized (RESULTS) {
            final String result = RESULTS.get(resultKey);
            if (StringUtils.isEmpty(result)) {
                return Response.status(404).build();
            }
            return Response.ok(result).build();
        }
    }
}