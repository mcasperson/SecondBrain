package secondbrain.infrastructure.googledocs;

import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GoogleDocsClientLive} that never reach the network. The credentials are an
 * {@link HttpRequestInitializer}, which is invoked while the request is built. The initializer registers an interceptor
 * that records the fully built request and then throws, which stops the request before the transport opens a
 * connection.
 */
class GoogleDocsClientLiveTest {
    private final List<HttpRequest> requests = new CopyOnWriteArrayList<>();
    private final IOException stop = new IOException("Stopped before sending");

    private final HttpRequestInitializer recordAndStop = request -> request.setInterceptor(built -> {
        requests.add(built);
        throw stop;
    });

    @Test
    void getDocumentRequestsTheDocumentById() {
        final IOException ex = assertThrows(IOException.class, () -> new GoogleDocsClientLive().getDocument(recordAndStop, "doc-123"));

        assertSame(stop, ex);
        assertEquals(1, requests.size());
        final HttpRequest request = requests.getFirst();
        assertEquals("GET", request.getRequestMethod());
        assertEquals("https://docs.googleapis.com/v1/documents/doc-123", request.getUrl().build());
    }

    @Test
    void getDocumentIdentifiesTheApplication() {
        assertThrows(IOException.class, () -> new GoogleDocsClientLive().getDocument(recordAndStop, "doc-123"));

        final String userAgent = requests.getFirst().getHeaders().getUserAgent();
        assertTrue(userAgent.startsWith("SecondBrain "), userAgent);
    }

    @Test
    void getDocumentEncodesTheDocumentId() {
        assertThrows(IOException.class, () -> new GoogleDocsClientLive().getDocument(recordAndStop, "a/b c"));

        assertEquals("https://docs.googleapis.com/v1/documents/a%2Fb%20c", requests.getFirst().getUrl().build());
    }

    @Test
    void getDocumentCreatesANewTransportForEachRequest() {
        final GoogleDocsClientLive client = new GoogleDocsClientLive();

        assertThrows(IOException.class, () -> client.getDocument(recordAndStop, "doc-1"));
        assertThrows(IOException.class, () -> client.getDocument(recordAndStop, "doc-2"));

        final HttpTransport first = requests.get(0).getTransport();
        final HttpTransport second = requests.get(1).getTransport();
        assertNotSame(first, second);
        assertEquals("https://docs.googleapis.com/v1/documents/doc-2", requests.get(1).getUrl().build());
    }

    @Test
    void getDocumentPropagatesCredentialFailures() {
        final IllegalStateException failure = new IllegalStateException("Credentials expired");
        final HttpRequestInitializer failingCredentials = request -> {
            throw failure;
        };

        assertSame(failure, assertThrows(IllegalStateException.class, () -> new GoogleDocsClientLive().getDocument(failingCredentials, "doc-123")));
    }
}
