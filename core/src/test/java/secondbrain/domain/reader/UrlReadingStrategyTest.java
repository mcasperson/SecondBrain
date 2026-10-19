package secondbrain.domain.reader;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import secondbrain.domain.exceptions.InvalidResponse;
import secondbrain.domain.exceptions.MissingResponse;
import secondbrain.domain.response.OkResponseValidation;
import secondbrain.domain.test.FakeJaxRs;
import secondbrain.domain.test.FixedClientConstructor;
import secondbrain.domain.web.ClientConstructor;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * The HTTP client is a fake JAX-RS client, so no network access is needed.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(UrlReadingStrategy.class)
@AddBeanClasses(OkResponseValidation.class)
class UrlReadingStrategyTest {
    private static final String URL = "https://example.org/doc";

    private final FakeJaxRs jaxRs = new FakeJaxRs();

    @Inject
    private UrlReadingStrategy strategy;

    @Produces
    ClientConstructor produceClientConstructor() {
        return new FixedClientConstructor(jaxRs.client);
    }

    private static Response redirect(final int status, final String location) {
        final Response response = FakeJaxRs.response(status, "");
        when(response.getHeaderString("Location")).thenReturn(location);
        return response;
    }

    @Test
    void readReturnsBodyOfOkResponse() {
        jaxRs.enqueue(FakeJaxRs.response(200, "document body"));

        assertEquals("document body", strategy.read(URL));
        assertEquals(List.of(URL), jaxRs.targets());
    }

    @Test
    void readAcceptsCreatedResponse() {
        jaxRs.enqueue(FakeJaxRs.response(201, "created body"));

        assertEquals("created body", strategy.read(URL));
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302})
    void readFollowsRedirectToLocationHeader(final int status) {
        jaxRs.enqueue(redirect(status, "https://example.org/moved"));
        jaxRs.enqueue(FakeJaxRs.response(200, "moved body"));

        assertEquals("moved body", strategy.read(URL));
        assertEquals(List.of(URL, "https://example.org/moved"), jaxRs.targets());
    }

    @Test
    void readFollowsFiveRedirects() {
        IntStream.rangeClosed(1, 5).forEach(i -> jaxRs.enqueue(redirect(302, URL + i)));
        jaxRs.enqueue(FakeJaxRs.response(200, "final body"));

        assertEquals("final body", strategy.read(URL));
        assertEquals(List.of(URL, URL + 1, URL + 2, URL + 3, URL + 4, URL + 5), jaxRs.targets());
    }

    @Test
    void readFailsAfterMoreThanFiveRedirects() {
        IntStream.rangeClosed(1, 6).forEach(i -> jaxRs.enqueue(redirect(302, URL + i)));

        final InvalidResponse ex = assertThrows(InvalidResponse.class, () -> strategy.read(URL));

        assertEquals("Failed to get document from " + URL + 6, ex.getMessage());
        // The sixth redirect target is never requested
        assertEquals(List.of(URL, URL + 1, URL + 2, URL + 3, URL + 4, URL + 5), jaxRs.targets());
    }

    @Test
    void readThrowsMissingResponseFor404() {
        jaxRs.enqueue(FakeJaxRs.response(404, "not here"));

        assertThrows(MissingResponse.class, () -> strategy.read(URL));
    }

    @Test
    void readThrowsInvalidResponseForServerError() {
        jaxRs.enqueue(FakeJaxRs.response(500, "boom"));

        final InvalidResponse ex = assertThrows(InvalidResponse.class, () -> strategy.read(URL));

        assertTrue(String.valueOf(ex.getMessage()).startsWith("Expected status code 200, but got 500 from URI " + URL), ex.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.org/doc", "relative/path.txt", "/absolute/path.txt", ""})
    void isSupportedForValidUris(final String uri) {
        assertTrue(strategy.isSupported(uri));
    }

    @ParameterizedTest
    @ValueSource(strings = {"has a space", "https://example.org/<bad>", "%zz"})
    void isNotSupportedForInvalidUris(final String uri) {
        assertFalse(strategy.isSupported(uri));
    }

    @Test
    void priorityIs100() {
        assertEquals(100, strategy.getPriority());
    }
}
