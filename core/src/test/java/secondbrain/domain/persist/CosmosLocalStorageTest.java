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
import secondbrain.domain.exceptionhandling.LoggingExceptionHandler;
import secondbrain.domain.json.JsonDeserializerJackson;
import secondbrain.domain.logger.Loggers;

import java.util.Map;
import java.util.UUID;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(CosmosLocalStorage.class)
@AddBeanClasses(Loggers.class)
@AddBeanClasses(LoggingExceptionHandler.class)
@AddBeanClasses(JsonDeserializerJackson.class)
public class CosmosLocalStorageTest {

    @Inject
    CosmosLocalStorage cosmosLocalStorage;

    /**
     * <a href="https://github.com/weld/weld-testing/issues/81#issuecomment-1564002983">...</a>
     * Also need to run local cosmos emulator for this test to pass:
     * docker run --publish 9081:8081 --publish 10250-10255:10250-10255 --name linux-emulator --detach mcr.microsoft.com/cosmosdb/linux/azure-cosmos-emulator:latest
     * <p>
     * Get the self signed certificate
     * curl --insecure https://localhost:9081/_explorer/emulator.pem > ~/emulatorcert.crt
     * <p>
     * Import it into the java keystore. You might need to chaneg the path to your JAVA_HOME to be something like /home/matthew/.jdks/azul-25
     * keytool -import -trustcacerts -alias cosmosdb_cert -file ~/emulatorcert.crt -keystore $JAVA_HOME/lib/security/cacerts
     */
    @BeforeEach
    void updateConfig() {
        final var configSource = new PropertiesConfigSource(
                Map.of(
                        "sb.cache.disable", "false",
                        "sb.cosmos.endpoint", "https://localhost:9081",
                        "sb.cosmos.key", "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==",
                        "sb.cosmos.database", "testdb",
                        "sb.cosmos.container", "testcontainer"
                ),
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
        Assertions.assertNull(cosmosLocalStorage.getString(
                        CosmosLocalStorageTest.class.getSimpleName(),
                        "test",
                        UUID.randomUUID().toString())
                .result());
    }

    @Test
    public void testSave() {
        for (int i = 0; i < 10; i++) {
            final String randomValue = UUID.randomUUID().toString();
            Assertions.assertEquals(randomValue, cosmosLocalStorage.getOrPutString(
                            CosmosLocalStorageTest.class.getSimpleName(),
                            "test" + i,
                            randomValue,
                            () -> randomValue)
                    .result());
            Assertions.assertEquals(randomValue, cosmosLocalStorage.getString(
                            CosmosLocalStorageTest.class.getSimpleName(),
                            "test" + i,
                            randomValue)
                    .result());
        }
    }

    @Test
    public void testSaveObject() {
        for (int i = 0; i < 10; i++) {
            final String randomValue = UUID.randomUUID().toString();
            Assertions.assertEquals(randomValue, cosmosLocalStorage.getOrPutObject(
                            CosmosLocalStorageTest.class.getSimpleName(),
                            "test" + i,
                            randomValue,
                            TestObject.class,
                            () -> new TestObject(randomValue))
                    .result().value());
        }
    }

    @Test
    public void testTTL() {
        final String randomValue = UUID.randomUUID().toString();
        Assertions.assertEquals(randomValue, cosmosLocalStorage.getOrPutString(
                        CosmosLocalStorageTest.class.getSimpleName(),
                        "test",
                        randomValue,
                        10,
                        () -> randomValue)
                .result());
        Assertions.assertEquals(randomValue, cosmosLocalStorage.getString(
                        CosmosLocalStorageTest.class.getSimpleName(),
                        "test",
                        randomValue)
                .result());
        Try.run(() -> Thread.sleep(15000));
        Assertions.assertNull(cosmosLocalStorage.getString(
                        CosmosLocalStorageTest.class.getSimpleName(),
                        "test",
                        randomValue)
                .result());
    }

    record TestObject(String value) {
    }
}

