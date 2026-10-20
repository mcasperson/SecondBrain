package secondbrain.infrastructure.googledocs;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.docs.v1.Docs;
import com.google.api.services.docs.v1.model.Document;
import io.vavr.control.Try;

/**
 * Adapts the Google Docs SDK to the GoogleDocsClient interface. A new transport is created for each request.
 * Instances are created by {@link GoogleDocsClientProducer}.
 */
public class GoogleDocsClientLive implements GoogleDocsClient {
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final String APPLICATION_NAME = "SecondBrain";

    @Override
    public Document getDocument(final HttpRequestInitializer credentials, final String documentId) {
        return Try.of(GoogleNetHttpTransport::newTrustedTransport)
                .map(transport -> new Docs.Builder(transport, JSON_FACTORY, credentials)
                        .setApplicationName(APPLICATION_NAME)
                        .build())
                .mapTry(service -> service.documents().get(documentId).execute())
                .get();
    }
}
