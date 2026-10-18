package secondbrain.domain.persist;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import secondbrain.domain.exceptionhandling.LoggingExceptionHandler;
import secondbrain.domain.exceptions.LocalStorageFailure;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.test.TestConfigUtil;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(CosmosCacheContainerProducer.class)
@AddBeanClasses(LoggingExceptionHandler.class)
@AddBeanClasses(Loggers.class)
class CosmosCacheContainerProducerTest {

    @Inject
    private Instance<CosmosCacheContainer> containers;

    @ParameterizedTest
    @CsvSource({"sb.cosmos.endpoint, https://localhost:9081", "sb.cosmos.key, key"})
    void producingAContainerWithoutEndpointAndKeyFails(final String setting, final String value) {
        final Map<String, String> settings = new HashMap<>();
        settings.put(setting, value);
        TestConfigUtil.registerConfig(settings);

        final LocalStorageFailure ex = assertThrows(LocalStorageFailure.class, containers::get);

        assertEquals("Cosmos DB endpoint and key must be configured", ex.getMessage());
    }
}
