package secondbrain.domain.mutex;

import io.smallrye.config.inject.ConfigExtension;
import io.vavr.control.Try;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import secondbrain.domain.exceptions.LockFail;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.mutex.config.MutexTimeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(FileLockMutex.class)
@AddBeanClasses(FileLockSemaphore.class)
@AddBeanClasses(MutexTimeout.class)
@AddBeanClasses(Loggers.class)
public class FileLockSemaphoreTest {

    @Inject
    private FileLockSemaphore fileLockSemaphore;

    private final List<Path> lockFiles = new ArrayList<>();

    @BeforeAll
    static void registerConfig() {
        TestConfigUtil.registerConfig(Map.of(
                "sb.cache.disable", "false"
        ));
    }

    @BeforeEach
    void updateConfig() {
        registerConfig();
    }

    @AfterEach
    void cleanupLockFiles() {
        for (final Path lockFile : lockFiles) {
            Try.run(() -> Files.deleteIfExists(lockFile));
        }
        lockFiles.clear();
    }

    private String lockPath(final String name) {
        final Path path = Path.of(System.getProperty("java.io.tmpdir"), name);
        lockFiles.add(path);
        return path.toString();
    }

    @Test
    public void testSemaphoreAcquireSingleSlot() {
        final String lock = lockPath("fileSemaphoreTest1");
        final String result = fileLockSemaphore.acquire(1, 5000, lock, () -> "hello");
        Assertions.assertEquals("hello", result);
    }

    @Test
    public void testSemaphoreAcquireMultipleSlots() {
        final String lock = lockPath("fileSemaphoreTest2");
        for (int i = 0; i < 5; i++) {
            final String result = fileLockSemaphore.acquire(3, 5000, lock, () -> "world");
            Assertions.assertEquals("world", result);
        }
    }

    @Test
    public void testSemaphoreConcurrentAccess() throws InterruptedException {
        final int allowed = 2;
        final String lock = lockPath("fileSemaphoreConcurrent3");

        // Start two threads that hold slots for a while
        final Thread t1 = new Thread(() -> fileLockSemaphore.acquire(allowed, 10000, lock,
                () -> Try.run(() -> Thread.sleep(5000))));
        final Thread t2 = new Thread(() -> fileLockSemaphore.acquire(allowed, 10000, lock,
                () -> Try.run(() -> Thread.sleep(5000))));

        t1.start();
        t2.start();

        // Give time for both threads to acquire their slots
        Thread.sleep(2000);

        // With both slots held, a short timeout should fail
        Assertions.assertThrows(LockFail.class,
                () -> fileLockSemaphore.acquire(allowed, 1000, lock, () -> "should fail"));

        t1.join();
        t2.join();
    }

    @Test
    public void testSemaphoreExceptionPropagation() {
        final String lock = lockPath("fileSemaphoreException4");
        Assertions.assertThrows(RuntimeException.class,
                () -> fileLockSemaphore.acquire(2, 5000, lock, () -> {
                    throw new RuntimeException("Test exception");
                }));
    }

    @Test
    public void testSemaphoreDefaultTimeout() {
        final String lock = lockPath("fileSemaphoreDefault5");
        final String result = fileLockSemaphore.acquire(2, lock, () -> "default");
        Assertions.assertEquals("default", result);
    }
}


