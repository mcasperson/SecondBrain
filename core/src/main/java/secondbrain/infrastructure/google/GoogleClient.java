package secondbrain.infrastructure.google;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.concurrency.SemaphoreLender;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.infrastructure.google.api.*;
import secondbrain.infrastructure.llm.LlmClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@ApplicationScoped
public class GoogleClient implements LlmClient {
    private static final SemaphoreLender SEMAPHORE_LENDER = new SemaphoreLender(1);
    private static final String MODEL = "gemini-2.0-flash";
    private static final String URL = "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent";

    @Inject
    @ConfigProperty(name = "sb.googlellm.apikey")
    private Optional<String> apiKey;

    @Inject
    private Logger logger;

    @Override
    public String call(final String prompt) {
        return call(prompt, MODEL);
    }

    @Override
    public String call(final String prompt, final String model) {
        final GoogleRequest request = new GoogleRequest(
                List.of(new GoogleRequestContents(
                        List.of(new GoogleRequestContentsParts(prompt))
                ))
        );

        return call(request);
    }

    @Override
    public <T> RagMultiDocumentContext<T> callWithCache(
            final RagMultiDocumentContext<T> ragDocs,
            final Map<String, String> environmentSettings,
            final String tool) {

        final List<GoogleRequestContentsParts> parts = ragDocs.individualContexts().stream()
                .map(ragDoc -> new GoogleRequestContentsParts(ragDoc.contextLabel() + ": " + ragDoc.document()))
                .collect(Collectors.toCollection(ArrayList::new));

        parts.add(new GoogleRequestContentsParts(ragDocs.prompt()));

        return ragDocs.updateResponse(call(
                        new GoogleRequest(
                                List.of(new GoogleRequestContents(parts)
                                ),
                                new GoogleRequestSystemInstruction(
                                        List.of(
                                                new GoogleRequestContentsParts(ragDocs.instructions())
                                        )
                                )
                        )
                )
        );
    }

    private String call(final GoogleRequest request) {
        if (apiKey.isEmpty()) {
            throw new IllegalStateException("Google API key is not configured.");
        }

        final String result = Try.withResources(ClientBuilder::newClient)
                .of(client -> Try.withResources(() -> SEMAPHORE_LENDER.lend(client.target(URL)
                                .request()
                                .header("Content-Type", "application/json")
                                .header("Accept", "application/json")
                                .header("X-goog-api-key", apiKey.get())
                                .post(Entity.entity(request, MediaType.APPLICATION_JSON))))
                        .of(wrapped -> Try.of(wrapped::getWrapped)
                                .map(response -> response.readEntity(GoogleResponse.class))
                                .map(response -> response.candidates().stream()
                                        .map(GoogleResponseCandidates::content)
                                        .flatMap(content -> content.parts().stream())
                                        .map(GoogleResponseCandidatesContentParts::text)
                                        .reduce("", String::concat))
                                .onFailure(e -> logger.severe(e.getMessage()))
                                .get()
                        )
                        .get()
                )
                .get();

        logger.info(result);

        return result;
    }
}
