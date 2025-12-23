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
import secondbrain.domain.exceptionhandling.ExceptionHandler;
import secondbrain.domain.exceptionhandling.LoggingExceptionHandler;
import secondbrain.domain.json.JsonDeserializerJackson;
import secondbrain.domain.logger.Loggers;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(FileLocalStorageReadWrite.class)
@AddBeanClasses(Loggers.class)
@AddBeanClasses(LoggingExceptionHandler.class)
@AddBeanClasses(JsonDeserializerJackson.class)
@AddBeanClasses(ExceptionHandler.class)
public class FileLocalStorageReadWriteTest {

    @Inject
    FileLocalStorageReadWrite fileLocalStorage;

    @BeforeEach
    void updateConfig() {
        final var configSource = new PropertiesConfigSource(
                Map.of("sb.cache.localdir", "testlocalcache"),
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
        Assertions.assertTrue(fileLocalStorage.getString(
                FileLocalStorageReadWriteTest.class.getSimpleName(),
                "testconnection",
                UUID.randomUUID().toString()
        ).isEmpty());
    }

    @Test
    public void testSave() {
        final String randomValue = UUID.randomUUID().toString();
        fileLocalStorage.putString(
                FileLocalStorageReadWriteTest.class.getSimpleName(),
                "testsave",
                randomValue,
                0L,
                randomValue
        );
        Assertions.assertEquals(Optional.of(randomValue), fileLocalStorage.getString(
                FileLocalStorageReadWriteTest.class.getSimpleName(),
                "testsave",
                randomValue
        ));
    }

    @Test
    public void testTTL() {
        final String randomValue = UUID.randomUUID().toString();
        fileLocalStorage.putString(
                FileLocalStorageReadWriteTest.class.getSimpleName(),
                "testttl",
                randomValue,
                Instant.now().getEpochSecond() + 1,
                randomValue
        );
        Assertions.assertEquals(Optional.of(randomValue), fileLocalStorage.getString(
                FileLocalStorageReadWriteTest.class.getSimpleName(),
                "testttl",
                randomValue
        ));

        Try.run(() -> Thread.sleep(2500));

        Assertions.assertTrue(fileLocalStorage.getString(
                FileLocalStorageReadWriteTest.class.getSimpleName(),
                "testttl",
                randomValue
        ).isEmpty());
    }
}

