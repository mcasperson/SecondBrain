package secondbrain.domain.mutex;

import io.smallrye.config.inject.ConfigExtension;
import io.vavr.control.Try;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import secondbrain.domain.exceptionhandling.LoggingExceptionHandler;
import secondbrain.domain.exceptions.LockFail;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.mutex.config.MutexTimeout;
import secondbrain.domain.test.FakeCosmosLockContainer;
import secondbrain.domain.test.TestConfigUtil;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the parts of {@link CosmosMutex} that changed when the Cosmos container moved behind
 * {@link CosmosLockContainer}. The container is an in-memory test instance, so no emulator is needed.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(CosmosMutex.class)
@AddBeanClasses(MutexTimeout.class)
@AddBeanClasses(Loggers.class)
@AddBeanClasses(LoggingExceptionHandler.class)
class CosmosMutexUnitTest {
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
        TestConfigUtil.registerConfig(Map.of());
    }

    @Test
    void locksAreKeptOnStartupByDefault() {
        container.addLock("old", Instant.now());

        assertEquals("ok", mutex.tryAcquire("new", () -> "ok").get());

        assertEquals(List.of("new"), container.getDeletes());
        assertEquals(0, container.getDeleteAllCount());
        assertTrue(container.hasLock("old"));
    }

    @Test
    void tryAcquireFailsWithLockFailWhenLockIsHeld() {
        container.addLock("held", Instant.now());

        final Try<String> result = mutex.tryAcquire("held", () -> "never");

        assertTrue(result.isFailure());
        assertInstanceOf(LockFail.class, result.getCause());
        assertEquals("Failed to obtain lock - lock held is currently held", result.getCause().getMessage());
        assertEquals(List.of(), container.getUpserts());
    }
}
