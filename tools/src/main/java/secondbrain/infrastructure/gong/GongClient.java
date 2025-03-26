package secondbrain.infrastructure.gong;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.codec.digest.DigestUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.concurrency.SemaphoreLender;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.response.ResponseValidation;

import java.util.Base64;
import java.util.List;
import java.util.Objects;

@ApplicationScoped
public class GongClient {
    private static final SemaphoreLender SEMAPHORE_LENDER = new SemaphoreLender(Constants.DEFAULT_SEMAPHORE_COUNT);

    @Inject
    private ResponseValidation responseValidation;

    @Inject
    @ConfigProperty(name = "sb.gong.url", defaultValue = "https://api.gong.io")
    private String url;

    @Inject
    private LocalStorage localStorage;

    public List<GongCallExtensive> getCallsExtensive(
            final Client client,
            final String company,
            final String username,
            final String password,
            final String fromDateTime,
            final String toDateTime) {

        /*
         There is no way to filter by salesforce ID. So we instead get all the calls during the period,
         cache the result, and then filter the calls by the company ID.
         */
        final GongCallsExtensive calls = localStorage.getOrPutObject(
                GongClient.class.getSimpleName(),
                "GongAPICallsExtensive",
                DigestUtils.sha256Hex(fromDateTime + toDateTime),
                GongCallsExtensive.class,
                () -> getCallsExtensiveApi(client, fromDateTime, toDateTime, username, password));

        if (calls == null) {
            return List.of();
        }

        return calls.calls().stream()
                .filter(call -> Objects.requireNonNullElse(call.context(), List.<GongCallExtensiveContext>of())
                        .stream()
                        .anyMatch(c ->
                                // One of the call context object must be for Salesforce
                                "Salesforce".equals(c.system()) &&
                                        // And one of the objects must be an account that matches the company id
                                        c.objects().stream().anyMatch(o ->
                                                company.equals(o.objectId()) && "Account".equals(o.objectType()))))
                .toList();

    }

    public GongCallTranscript getCallTranscript(
            final Client client,
            final String username,
            final String password,
            final String id) {

        return localStorage.getOrPutObject(
                GongClient.class.getSimpleName(),
                "GongAPICallTranscript",
                id,
                GongCallTranscript.class,
                () -> getCallTranscriptApi(client, id, username, password));
    }

    /**
     * https://gong.app.gong.io/settings/api/documentation#post-/v2/calls/extensive
     */
    private GongCallsExtensive getCallsExtensiveApi(
            final Client client,
            final String fromDateTime,
            final String toDateTime,
            final String username,
            final String password) {
        final String target = url + "/v2/calls/extensive";

        final GongCallExtensiveQuery body = new GongCallExtensiveQuery(
                new GongCallExtensiveQueryFiler(fromDateTime, toDateTime, null, null),
                new GongCallExtensiveQueryContentSelector("Extended", List.of("Now", "TimeOfCall"))
        );

        return Try.withResources(() -> SEMAPHORE_LENDER.lend(client.target(target)
                        .request(MediaType.APPLICATION_JSON_TYPE)
                        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes()))
                        .post(Entity.entity(body, MediaType.APPLICATION_JSON))))
                .of(response -> Try.of(() -> responseValidation.validate(response.getWrapped(), target))
                        .map(r -> r.readEntity(GongCallsExtensive.class))
                        .get())
                .getOrElseThrow(e -> new RuntimeException("Failed to get calls from Gong API", e));
    }

    /**
     * https://gong.app.gong.io/settings/api/documentation#post-/v2/calls/transcript
     */
    private GongCallTranscript getCallTranscriptApi(
            final Client client,
            final String id,
            final String username,
            final String password) {
        final String target = url + "/v2/calls/transcript";

        final GongCallTranscriptQuery body = new GongCallTranscriptQuery(
                new GongCallTranscriptQueryFilter(List.of(id))
        );

        return Try.withResources(() -> SEMAPHORE_LENDER.lend(client.target(target)
                        .request()
                        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes()))
                        .header("Accept", MediaType.APPLICATION_JSON)
                        .post(Entity.entity(body, MediaType.APPLICATION_JSON))))
                .of(response -> Try.of(() -> responseValidation.validate(response.getWrapped(), target))
                        .map(r -> r.readEntity(GongCallTranscript.class))
                        .get())
                .getOrElseThrow(e -> new RuntimeException("Failed to get call transcript from Gong API", e));
    }
}
