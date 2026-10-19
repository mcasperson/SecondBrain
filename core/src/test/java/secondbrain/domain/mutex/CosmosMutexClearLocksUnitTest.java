package secondbrain.domain.mutex;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import secondbrain.domain.exceptionhandling.LoggingExceptionHandler;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.mutex.config.MutexTimeout;
import secondbrain.domain.test.FakeCosmosLockContainer;
import secondbrain.domain.test.TestConfigUtil;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that {@link CosmosMutex} clears the lock container on startup when sb.cosmos.clearlocksonstartup is set.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(CosmosMutex.class)
@AddBeanClasses(MutexTimeout.class)
@AddBeanClasses(Loggers.class)
@AddBeanClasses(LoggingExceptionHandler.class)
class CosmosMutexClearLocksUnitTest {
    private final FakeCosmosLockContainer container = new FakeCosmosLockContainer();

    @Inject
    private CosmosMutex mutex;

    @Produces
    CosmosLockContainer produceCosmosLockContainer() {
        return container;
    }

    /**
     * The bean observes the Startup event, so it is created when the container starts. Config must be registered
     * before then.
     */
    @BeforeAll
    static void registerConfig() {
        TestConfigUtil.registerConfig(Map.of("sb.cosmos.clearlocksonstartup", "true"));
    }

    @Test
    void locksAreClearedOnceOnStartupWhenEnabled() {
        assertEquals("ok", mutex.tryAcquire("new", () -> "ok").get());

        assertEquals(1, container.getDeleteAllCount());
    }
}
