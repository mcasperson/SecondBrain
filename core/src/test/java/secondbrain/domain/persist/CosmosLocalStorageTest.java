package secondbrain.domain.persist;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.inject.ConfigExtension;
import io.vavr.control.Try;
import jakarta.inject.Inject;
import org.apache.tika.utils.StringUtils;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import secondbrain.domain.encryption.AesEncryptor;
import secondbrain.domain.encryption.JasyptBinaryEncryptor;
import secondbrain.domain.encryption.JasyptEncryptor;
import secondbrain.domain.exceptionhandling.LoggingExceptionHandler;
import secondbrain.domain.json.JsonDeserializerJackson;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.sanitize.FinancialLocationContactRedaction;
import secondbrain.domain.zip.ApacheCommonsZStdZipper;
import secondbrain.domain.zip.ApacheCompressZipper;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

@SuppressWarnings("NullAway")
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(CosmosLocalStorage.class)
@AddBeanClasses(FileLocalStorageReadWrite.class)
@AddBeanClasses(Loggers.class)
@AddBeanClasses(LoggingExceptionHandler.class)
@AddBeanClasses(JsonDeserializerJackson.class)
@AddBeanClasses(JasyptEncryptor.class)
@AddBeanClasses(JasyptBinaryEncryptor.class)
@AddBeanClasses(AesEncryptor.class)
@AddBeanClasses(ApacheCompressZipper.class)
@AddBeanClasses(ApacheCommonsZStdZipper.class)
@AddBeanClasses(FinancialLocationContactRedaction.class)
public class CosmosLocalStorageTest {

    @Inject
    CosmosLocalStorage cosmosLocalStorage;

    /**
     * <a href="https://github.com/weld/weld-testing/issues/81#issuecomment-1564002983">...</a>
     * Also need to run local cosmos emulator for this test to pass:
     * docker run --platform linux/amd64 --publish 9081:8081 --publish 10250-10255:10250-10255 --name linux-emulator --detach --restart unless-stopped mcr.microsoft.com/cosmosdb/linux/azure-cosmos-emulator:latest
     * <p>
     * On macos:
     * docker run --publish 9081:8081 --publish 10250-10255:10250-10255 --name linux-emulator --detach --restart unless-stopped mcr.microsoft.com/cosmosdb/linux/azure-cosmos-emulator:vnext-preview --protocol https
     * <p>
     * Get the self signed certificate
     * curl --insecure https://localhost:9081/_explorer/emulator.pem > ~/emulatorcert.crt
     * <p>
     * Import it into the java keystore. You might need to change the path to your JAVA_HOME to be something like /home/matthew/.jdks/azul-25
     * keytool -import -trustcacerts -alias cosmosdb_cert -file ~/emulatorcert.crt -keystore $JAVA_HOME/lib/security/cacerts
     * <p>
     * Do this on a mac:
     * <p>
     * EMULATOR_HOST=localhost
     * EMULATOR_PORT=9081
     * EMULATOR_CERT_PATH=/tmp/cosmos_emulator.cert
     * openssl s_client -connect ${EMULATOR_HOST}:${EMULATOR_PORT} </dev/null | sed -ne '/-BEGIN CERTIFICATE-/,/-END CERTIFICATE-/p' > $EMULATOR_CERT_PATH
     * keytool -cacerts -delete -alias cosmos_emulator
     * keytool -import -trustcacerts -alias cosmos_emulator -file /tmp/cosmos_emulator.cert -keystore ~/Library/Java/JavaVirtualMachines/azul-25.0.3/Contents/Home/lib/security/cacerts
     * <p>
     * Or for the system Java JDK:
     * sudo keytool -import -trustcacerts -alias cosmosdb_domain -file domain.crt -keystore /Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home/lib/security/cacerts
     */
    @BeforeEach
    void updateConfig() {
        final String autodiscovery = System.getenv("SB_COSMOS_AUTODISCOVERY");
        final String gatewayMode = System.getenv("SB_COSMOS_GATEWAYMODE");

        final var configSource = new PropertiesConfigSource(
                Map.of(
                        "sb.cache.disable", "false",
                        "sb.cosmos.endpoint", "https://localhost:9081",
                        "sb.cosmos.key", "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==",
                        "sb.cosmos.database", "testdb",
                        "sb.cosmos.container", "testcontainer",
                        "sb.encryption.password", "1234567890",
                        "sb.encryption.salt", "1234567890",
                        "sb.cosmos.autodiscovery", StringUtils.isBlank(autodiscovery) ? "true" : autodiscovery,
                        "sb.cosmos.gatewayMode", StringUtils.isBlank(gatewayMode) ? "false" : gatewayMode
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
        final String randomValue = UUID.randomUUID().toString();
        for (int i = 0; i < 10; i++) {

            System.out.println("generate" + i + ": " + randomValue);
            Assertions.assertEquals(randomValue, cosmosLocalStorage.getOrPutString(
                            CosmosLocalStorageTest.class.getSimpleName(),
                            "test" + i,
                            randomValue,
                            () -> randomValue)
                    .result());
        }

        cosmosLocalStorage.flush();

        for (int i = 0; i < 10; i++) {
            final CacheResult<String> value = cosmosLocalStorage.getString(
                    CosmosLocalStorageTest.class.getSimpleName(),
                    "test" + i,
                    randomValue);
            System.out.println("read" + i + ": " + value.result());
            Assertions.assertTrue(randomValue.equals(value.result()) || value.result().contains("{{{REDACTED-PHONE}}}"));
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
    public void testSaveArray() {
        final String[] array = new String[]{"A", "B", "C"};

        Assertions.assertArrayEquals(array, cosmosLocalStorage.getOrPutObjectArray(
                        CosmosLocalStorageTest.class.getSimpleName(),
                        "test",
                        Arrays.hashCode(array) + "",
                        60,
                        String.class,
                        String[].class,
                        () -> array)
                .result());

        // Give the cosmos some time to finalize the write
        Try.run(() -> Thread.sleep(3000));

        for (int i = 0; i < 5; i++) {
            CacheResult<String[]> result = cosmosLocalStorage.getOrPutObjectArray(
                    CosmosLocalStorageTest.class.getSimpleName(),
                    "test",
                    Arrays.hashCode(array) + "",
                    60,
                    String.class,
                    String[].class,
                    () -> array);

            Assertions.assertArrayEquals(array, result.result());
            Assertions.assertTrue(result.fromCache());
        }
    }

    @Test
    public void testSaveObjectArray() {
        TestObject[] array = new TestObject[]{
                new TestObject("A"),
                new TestObject("B"),
                new TestObject("C"),
        };

        Assertions.assertArrayEquals(array, cosmosLocalStorage.getOrPutObjectArray(
                        CosmosLocalStorageTest.class.getSimpleName(),
                        "testSaveObjectArray",
                        Arrays.hashCode(array) + "",
                        Integer.MAX_VALUE,
                        TestObject.class,
                        TestObject[].class,
                        () -> array)
                .result());

        // Give the cosmos some time to finalize the write
        Try.run(() -> Thread.sleep(3000));

        for (int i = 0; i < 5; i++) {
            CacheResult<TestObject[]> result = cosmosLocalStorage.getOrPutObjectArray(
                    CosmosLocalStorageTest.class.getSimpleName(),
                    "testSaveObjectArray",
                    Arrays.hashCode(array) + "",
                    60,
                    TestObject.class,
                    TestObject[].class,
                    () -> array);

            Assertions.assertArrayEquals(array, result.result());
            Assertions.assertTrue(result.fromCache());
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

        cosmosLocalStorage.flush();

        final String result = cosmosLocalStorage.getString(
                        CosmosLocalStorageTest.class.getSimpleName(),
                        "test",
                        randomValue)
                .result();

        Assertions.assertTrue(randomValue.equals(result) || result.contains("{{{REDACTED-PHONE}}}"));
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

