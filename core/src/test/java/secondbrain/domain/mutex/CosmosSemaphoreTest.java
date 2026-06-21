package secondbrain.domain.mutex;

import io.smallrye.config.inject.ConfigExtension;
import io.vavr.control.Try;
import jakarta.inject.Inject;
import org.apache.tika.utils.StringUtils;
import secondbrain.domain.testconstants.TestConstants;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import secondbrain.domain.exceptionhandling.LoggingExceptionHandler;
import secondbrain.domain.exceptions.LockFail;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.mutex.config.MutexTimeout;

import java.util.Map;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(CosmosMutex.class)
@AddBeanClasses(CosmosSemaphore.class)
@AddBeanClasses(MutexTimeout.class)
@AddBeanClasses(Loggers.class)
@AddBeanClasses(LoggingExceptionHandler.class)
public class CosmosSemaphoreTest {

    @Inject
    private CosmosSemaphore cosmosSemaphore;

    /**
     * Requires a local Cosmos DB emulator:
     * docker run --publish 9081:8081 --publish 10250-10255:10250-10255 --name linux-emulator --detach --restart unless-stopped mcr.microsoft.com/cosmosdb/linux/azure-cosmos-emulator:latest
     */
    @BeforeAll
    static void registerConfig() {
        final String autodiscovery = System.getenv("SB_COSMOS_AUTODISCOVERY");
        final String gatewayMode = System.getenv("SB_COSMOS_GATEWAYMODE");

        TestConfigUtil.registerConfig(Map.of(
                "sb.cache.disable", "false",
                "sb.cosmos.endpoint", TestConstants.COSMOS_EMULATOR_ENDPOINT,
                "sb.cosmos.key", TestConstants.COSMOS_EMULATOR_KEY,
                "sb.cosmos.lockdatabase", "secondbrainlock",
                "sb.cosmos.lockscontainer", "locks",
                "sb.cosmos.autodiscovery", StringUtils.isBlank(autodiscovery) ? "true" : autodiscovery,
                "sb.cosmos.gatewayMode", StringUtils.isBlank(gatewayMode) ? "false" : gatewayMode
        ));
    }

    @BeforeEach
    void updateConfig() {
        registerConfig();
    }

    @Test
    public void testSemaphoreAcquireSingleSlot() {
        final String result = cosmosSemaphore.acquire(1, 5000, "semaphoreTest1", () -> "hello");
        Assertions.assertEquals("hello", result);
    }

    @Test
    public void testSemaphoreAcquireMultipleSlots() {
        for (int i = 0; i < 5; i++) {
            final String result = cosmosSemaphore.acquire(3, 5000, "semaphoreTest2", () -> "world");
            Assertions.assertEquals("world", result);
        }
    }

    @Test
    public void testSemaphoreConcurrentAccess() throws InterruptedException {
        final int allowed = 2;
        final String lockName = "semaphoreConcurrent3";

        // Start two threads that hold slots for a while
        final Thread t1 = new Thread(() -> cosmosSemaphore.acquire(allowed, 10000, lockName,
                () -> Try.run(() -> Thread.sleep(5000))));
        final Thread t2 = new Thread(() -> cosmosSemaphore.acquire(allowed, 10000, lockName,
                () -> Try.run(() -> Thread.sleep(5000))));

        t1.start();
        t2.start();

        // Give time for both threads to acquire their slots
        Thread.sleep(2000);

        // With both slots held, a short timeout should fail
        Assertions.assertThrows(LockFail.class,
                () -> cosmosSemaphore.acquire(allowed, 1000, lockName, () -> "should fail"));

        t1.join();
        t2.join();
    }

    @Test
    public void testSemaphoreExceptionPropagation() {
        Assertions.assertThrows(RuntimeException.class,
                () -> cosmosSemaphore.acquire(2, 5000, "semaphoreException4", () -> {
                    throw new RuntimeException("Test exception");
                }));
    }

    @Test
    public void testSemaphoreDefaultTimeout() {
        final String result = cosmosSemaphore.acquire(2, "semaphoreDefault5", () -> "default");
        Assertions.assertEquals("default", result);
    }
}


