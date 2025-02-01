package secondbrain.domain.persist;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.inject.ConfigExtension;
import io.vavr.control.Try;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import secondbrain.domain.json.JsonDeserializerJackson;
import secondbrain.domain.logger.Loggers;

import java.util.Map;
import java.util.UUID;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(H2LocalStorage.class)
@AddBeanClasses(Loggers.class)
@AddBeanClasses(JsonDeserializerJackson.class)
public class H2LocalStorageTest {

    @Inject
    H2LocalStorage h2LocalStorage;

    /**
     * <a href="https://github.com/weld/weld-testing/issues/81#issuecomment-1564002983">...</a>
     */
    @BeforeEach
    void updateConfig() {
        final var configSource = new PropertiesConfigSource(
                Map.of("sb.cache.disable", "false"),
                "TestConfig",
                Integer.MAX_VALUE
        );
        final Config newConfig = new SmallRyeConfigBuilder()
                .withSources(configSource)
                .build();

        final var configProviderResolver = ConfigProviderResolver.instance();
        final var oldConfig = configProviderResolver.getConfig();

        configProviderResolver.releaseConfig(oldConfig);
        configProviderResolver.registerConfig(
                newConfig,
                Thread.currentThread().getContextClassLoader()
        );
    }

    @Test
    public void testConnection() {
        Assertions.assertNull(h2LocalStorage.getString(
                H2LocalStorageTest.class.getSimpleName(),
                "test",
                UUID.randomUUID().toString()));
    }

    @Test
    public void testSave() {
        final String randomValue = UUID.randomUUID().toString();
        Assertions.assertEquals(randomValue, h2LocalStorage.getOrPutString(
                H2LocalStorageTest.class.getSimpleName(),
                "test",
                randomValue,
                () -> randomValue));
        Assertions.assertEquals(randomValue, h2LocalStorage.getString(
                H2LocalStorageTest.class.getSimpleName(),
                "test",
                randomValue));
    }

    @Test
    public void testSaveObject() {
        final String randomValue = UUID.randomUUID().toString();
        Assertions.assertEquals(randomValue, h2LocalStorage.getOrPutObject(
                H2LocalStorageTest.class.getSimpleName(),
                "test",
                randomValue,
                TestObject.class,
                () -> new TestObject(randomValue)).value());
    }

    @Test
    public void testTTL() {
        final String randomValue = UUID.randomUUID().toString();
        Assertions.assertEquals(randomValue, h2LocalStorage.getOrPutString(
                H2LocalStorageTest.class.getSimpleName(),
                "test",
                randomValue,
                10,
                () -> randomValue));
        Assertions.assertEquals(randomValue, h2LocalStorage.getString(
                H2LocalStorageTest.class.getSimpleName(),
                "test",
                randomValue));
        Try.run(() -> Thread.sleep(15000));
        Assertions.assertNull(h2LocalStorage.getString(
                H2LocalStorageTest.class.getSimpleName(),
                "test",
                randomValue));
    }

    record TestObject(String value) {
    }
}
