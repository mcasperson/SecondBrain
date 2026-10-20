package secondbrain.infrastructure.googledocs;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(GoogleDocsClientProducer.class)
class GoogleDocsClientProducerTest {
    @Inject
    private Instance<GoogleDocsClient> clients;

    @Inject
    private GoogleDocsClientProducer producer;

    @Test
    void producesTheLiveClient() {
        assertInstanceOf(GoogleDocsClientLive.class, clients.get());
    }

    @Test
    void producesANewClientForEachInjection() {
        // The producer method has the default dependent scope, so every lookup creates a new client
        assertNotSame(clients.get(), clients.get());
    }

    @Test
    void produceGoogleDocsClientReturnsANewLiveClientEachTime() {
        final GoogleDocsClient first = producer.produceGoogleDocsClient();
        final GoogleDocsClient second = producer.produceGoogleDocsClient();

        assertInstanceOf(GoogleDocsClientLive.class, first);
        assertInstanceOf(GoogleDocsClientLive.class, second);
        assertNotSame(first, second);
    }
}
