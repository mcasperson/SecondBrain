package secondbrain.infrastructure.googledocs;

import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.services.docs.v1.model.Document;

/**
 * Exposes the Google Docs operations used by {@link secondbrain.domain.tools.googledocs.GoogleDocs} behind an interface.
 * The Google Docs SDK client has no interface, so this adapter allows it to be swapped for a test instance.
 */
public interface GoogleDocsClient {
    /**
     * Downloads a Google Docs document.
     *
     * @param credentials The credentials used to authenticate the request
     * @param documentId  The ID of the document
     * @return The document
     * @throws RuntimeException if the document could not be downloaded
     */
    Document getDocument(HttpRequestInitializer credentials, String documentId);
}
