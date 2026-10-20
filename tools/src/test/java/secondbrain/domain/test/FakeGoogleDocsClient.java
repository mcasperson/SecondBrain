package secondbrain.domain.test;

import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.services.docs.v1.model.Document;
import org.jspecify.annotations.Nullable;
import secondbrain.infrastructure.googledocs.GoogleDocsClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An in-memory test instance of GoogleDocsClient. Expose it to the class being tested with a @Produces method.
 * Every call is recorded with the credentials it used. Requesting a document that has not been added fails.
 */
public class FakeGoogleDocsClient implements GoogleDocsClient {
    /**
     * A recorded call to getDocument.
     */
    public record Call(HttpRequestInitializer credentials, String documentId) {
    }

    private final Map<String, Document> documents = new HashMap<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    @Nullable
    private RuntimeException failure;

    /**
     * Adds a document that can be downloaded with the supplied ID.
     */
    public FakeGoogleDocsClient addDocument(final String documentId, final Document document) {
        documents.put(documentId, document);
        return this;
    }

    /**
     * Makes every subsequent download throw the supplied exception.
     */
    public FakeGoogleDocsClient failWith(final RuntimeException failure) {
        this.failure = failure;
        return this;
    }

    /**
     * @return Every call made to the fake, in order.
     */
    public List<Call> getCalls() {
        return List.copyOf(calls);
    }

    @Override
    public Document getDocument(final HttpRequestInitializer credentials, final String documentId) {
        calls.add(new Call(credentials, documentId));
        if (failure != null) {
            throw failure;
        }
        final Document document = documents.get(documentId);
        if (document == null) {
            throw new IllegalStateException("Document " + documentId + " not found");
        }
        return document;
    }
}
