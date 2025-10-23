package secondbrain.domain.mutex;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.inject.ConfigExtension;
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
import secondbrain.domain.logger.Loggers;

import java.util.Map;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(CosmosMutex.class)
@AddBeanClasses(Loggers.class)
@AddBeanClasses(LoggingExceptionHandler.class)
public class CosmosMutexTest {
    @Inject
    private CosmosMutex cosmosMutex;

    /**
     * <a href="https://github.com/weld/weld-testing/issues/81#issuecomment-1564002983">...</a>
     * Also need to run local cosmos emulator for this test to pass:
     * docker run --publish 9081:8081 --publish 10250-10255:10250-10255 --name linux-emulator --detach --restart unless-stopped mcr.microsoft.com/cosmosdb/linux/azure-cosmos-emulator:latest
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
                        "sb.cosmos.lockdatabase", "secondbrainlock",
                        "sb.cosmos.lockscontainer", "locks"
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
    public void testLocking() {
        final String result = cosmosMutex.acquire(5000, "mytestlock8", () -> "hi");
        Assertions.assertEquals("hi", result);
    }
}
