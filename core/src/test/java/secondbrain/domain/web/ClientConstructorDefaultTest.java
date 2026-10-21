package secondbrain.domain.web;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.Configurable;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.plugins.providers.FormUrlEncodedProvider;
import org.jboss.resteasy.plugins.providers.StringTextStar;
import org.jboss.resteasy.plugins.providers.jackson.ResteasyJackson2Provider;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Builds real JAX-RS clients but never sends a request.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(ClientConstructorDefault.class)
class ClientConstructorDefaultTest {

    @Inject
    private ClientConstructorDefault constructor;

    @SuppressWarnings("removal")
	private static RequestConfig requestConfig(final Client client) {
        // The engine type is the only public way to read the timeouts that were applied to the client
        final org.jboss.resteasy.client.jaxrs.engines.ManualClosingApacheHttpClient43Engine engine =
        		(org.jboss.resteasy.client.jaxrs.engines.ManualClosingApacheHttpClient43Engine) ((ResteasyClient) client).httpEngine();
        return ((Configurable) engine.getHttpClient()).getConfig();
    }

    private static void assertProvidersRegistered(final Client client) {
        assertTrue(client.getConfiguration().isRegistered(ResteasyJackson2Provider.class));
        assertTrue(client.getConfiguration().isRegistered(StringTextStar.class));
        assertTrue(client.getConfiguration().isRegistered(FormUrlEncodedProvider.class));
    }

    @Test
    void defaultClientUsesDefaultTimeouts() {
        final Client client = constructor.getClient();
        try {
            final RequestConfig config = requestConfig(client);
            assertEquals(30_000, config.getConnectTimeout());
            assertEquals(120_000, config.getSocketTimeout());
            assertProvidersRegistered(client);
        } finally {
            client.close();
        }
    }

    @Test
    void clientUsesSuppliedConnectionAndReadTimeouts() {
        final Client client = constructor.getClient(5, 7);
        try {
            final RequestConfig config = requestConfig(client);
            assertEquals(5_000, config.getConnectTimeout());
            assertEquals(7_000, config.getSocketTimeout());
            assertProvidersRegistered(client);
        } finally {
            client.close();
        }
    }

    @Test
    void readTimeoutClientUsesDefaultConnectionTimeout() {
        final Client client = constructor.getClient(45);
        try {
            final RequestConfig config = requestConfig(client);
            assertEquals(30_000, config.getConnectTimeout());
            assertEquals(45_000, config.getSocketTimeout());
            assertProvidersRegistered(client);
        } finally {
            client.close();
        }
    }

    @Test
    void eachCallBuildsANewClient() {
        final Client first = constructor.getClient();
        final Client second = constructor.getClient();
        try {
            assertNotSame(first, second);
        } finally {
            first.close();
            second.close();
        }
    }
}
