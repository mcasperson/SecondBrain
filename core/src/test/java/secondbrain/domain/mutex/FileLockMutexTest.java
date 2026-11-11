package secondbrain.domain.mutex;

import io.smallrye.config.inject.ConfigExtension;
import io.vavr.control.Try;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import secondbrain.domain.exceptions.LockFail;
import secondbrain.domain.files.FileUtilsImpl;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.mutex.config.MutexTimeout;
import secondbrain.domain.test.TestConfigUtil;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(FileLockMutex.class)
@AddBeanClasses(MutexTimeout.class)
@AddBeanClasses(Loggers.class)
@AddBeanClasses(FileUtilsImpl.class)
@Timeout(60)
class FileLockMutexTest {
    private static final String TIMEOUT_MESSAGE = "Failed to obtain file lock within the specified timeout";

    /**
     * A program run in a separate JVM that holds an exclusive lock on the file passed as the first argument
     * until its standard input is closed. File locks are held per process, so this is the only way to
     * make FileChannel.tryLock() return null rather than throw an OverlappingFileLockException.
     */
    private static final String LOCK_HOLDER_SOURCE = """
            import java.io.RandomAccessFile;
            import java.nio.channels.FileChannel;
            import java.nio.channels.FileLock;
            
            public class HoldLock {
            	public static void main(final String[] args) throws Exception {
            		try (FileChannel channel = new RandomAccessFile(args[0], "rw").getChannel();
            			 FileLock lock = channel.lock()) {
            			System.out.println("locked");
            			System.out.flush();
            			while (System.in.read() != -1) {
            			}
            		}
            	}
            }
            """;

    @Inject
    private FileLockMutex fileLockMutex;

    @TempDir
    @SuppressWarnings("NullAway.Init")
    private Path tempDir;

    @BeforeEach
    void registerConfig() {
        TestConfigUtil.registerConfig(Map.of("sb.mutex.defaultTimeout", "10000"));
    }

    private String lockPath(final String name) {
        return tempDir.resolve(name).toString();
    }

    @Test
    void acquireReturnsTheCallbackResultAndCreatesTheLockFile() {
        final String lock = lockPath("acquire.lock");

        final String result = fileLockMutex.acquire(1000, lock, () -> "hello");

        assertEquals("hello", result);
        assertTrue(Files.exists(Path.of(lock)));
    }

    @Test
    void acquireCanBeRepeatedOnceTheLockIsReleased() {
        final String lock = lockPath("repeat.lock");

        assertEquals("first", fileLockMutex.acquire(1000, lock, () -> "first"));
        assertEquals("second", fileLockMutex.acquire(1000, lock, () -> "second"));
    }

    @Test
    void acquireWithTheDefaultTimeoutReturnsTheCallbackResult() {
        assertEquals("default", fileLockMutex.acquire(lockPath("default.lock"), () -> "default"));
    }

    @Test
    void acquireWithTheDefaultTimeoutUsesTheConfiguredTimeout() {
        final AtomicInteger calls = new AtomicInteger();

        // The configured 10000ms timeout is used up by a single attempt, so there is no 10 second sleep
        final LockFail exception = assertThrows(LockFail.class,
                () -> fileLockMutex.acquire(lockPath("defaultTimeout.lock"), () -> {
                    calls.incrementAndGet();
                    throw new LockFail("busy");
                }));

        assertEquals(TIMEOUT_MESSAGE, exception.getMessage());
        assertEquals(1, calls.get());
    }

    @Test
    void acquireRethrowsAnExceptionFromTheCallback() {
        final IllegalStateException failure = new IllegalStateException("callback failed");

        final IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> fileLockMutex.acquire(1000, lockPath("callbackFailure.lock"), () -> {
                    throw failure;
                }));

        assertSame(failure, exception);
    }

    @Test
    void acquireReleasesTheLocksWhenTheCallbackFails() throws Exception {
        final String lock = lockPath("releaseAfterFailure.lock");

        assertThrows(IllegalStateException.class, () -> fileLockMutex.acquire(1000, lock, () -> {
            throw new IllegalStateException("callback failed");
        }));

        // Another thread can only get the lock within 100ms if both the JVM and file locks were released
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertEquals("after", executor.submit(() -> fileLockMutex.acquire(100, lock, () -> "after"))
                    .get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void acquireTreatsALockFailFromTheCallbackAsContentionAndTimesOutWithoutRetrying() {
        final AtomicInteger calls = new AtomicInteger();

        final LockFail exception = assertThrows(LockFail.class,
                () -> fileLockMutex.acquire(500, lockPath("callbackLockFail.lock"), () -> {
                    calls.incrementAndGet();
                    throw new LockFail("busy");
                }));

        assertEquals(TIMEOUT_MESSAGE, exception.getMessage());
        assertNull(exception.getCause());
        assertEquals(1, calls.get());
    }

    @Test
    void establishFileLockTimesOutAfterOneAttemptWhenTheTimeoutEqualsTheRetryInterval() {
        final AtomicInteger calls = new AtomicInteger();

        final LockFail exception = assertThrows(LockFail.class,
                () -> fileLockMutex.establishFileLock(10000, lockPath("boundary.lock"), () -> {
                    calls.incrementAndGet();
                    throw new LockFail("busy");
                }));

        assertEquals(TIMEOUT_MESSAGE, exception.getMessage());
        assertEquals(1, calls.get());
    }

    @Test
    void acquireFailsWhenTheLocalLockIsHeldByAnotherThread() throws Exception {
        final String lock = lockPath("localLockHeld.lock");
        final CountDownLatch locked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        final ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            final Future<String> holder = executor.submit(() -> fileLockMutex.acquire(10000, lock, () -> {
                locked.countDown();
                Try.run(release::await);
                return "holder";
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));

            final LockFail exception = assertThrows(LockFail.class,
                    () -> fileLockMutex.acquire(100, lock, () -> {
                        calls.incrementAndGet();
                        return "never";
                    }));

            assertEquals(TIMEOUT_MESSAGE, exception.getMessage());
            assertInstanceOf(NoSuchElementException.class, exception.getCause());
            assertEquals(0, calls.get());

            release.countDown();
            assertEquals("holder", holder.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        // The failed attempt must not have left the local lock held by this thread
        assertEquals("after", fileLockMutex.acquire(100, lock, () -> "after"));
    }

    @Test
    void acquireFailsWhenTheFileIsLockedByAnotherProcess() throws Exception {
        final String lock = lockPath("otherProcess.lock");
        final Path source = tempDir.resolve("HoldLock.java");
        Files.writeString(source, LOCK_HOLDER_SOURCE);
        final String java = ProcessHandle.current().info().command().orElse("java");
        final Process holder = new ProcessBuilder(java, source.toString(), lock)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        final AtomicInteger calls = new AtomicInteger();

        try {
            final BufferedReader output = new BufferedReader(
                    new InputStreamReader(holder.getInputStream(), StandardCharsets.UTF_8));
            assertEquals("locked", output.readLine());

            final LockFail exception = assertThrows(LockFail.class,
                    () -> fileLockMutex.acquire(1000, lock, () -> {
                        calls.incrementAndGet();
                        return "never";
                    }));

            assertEquals(TIMEOUT_MESSAGE, exception.getMessage());
            assertEquals(0, calls.get());
        } finally {
            holder.getOutputStream().close();
            assertTrue(holder.waitFor(30, TimeUnit.SECONDS));
        }

        // Once the other process has released the file, the lock can be obtained
        assertEquals("released", fileLockMutex.acquire(1000, lock, () -> "released"));
        assertEquals(0, calls.get());
    }

    @Test
    void acquireIsNotReentrantForTheSameFile() {
        final String lock = lockPath("reentrant.lock");
        final AtomicInteger innerCalls = new AtomicInteger();

        assertThrows(OverlappingFileLockException.class,
                () -> fileLockMutex.acquire(1000, lock, () -> fileLockMutex.acquire(1000, lock, () -> {
                    innerCalls.incrementAndGet();
                    return "inner";
                })));

        assertEquals(0, innerCalls.get());
        // The failure released the outer lock as well
        assertEquals("after", fileLockMutex.acquire(1000, lock, () -> "after"));
    }

    @Test
    void acquireRethrowsAnErrorOpeningTheLockFile() {
        final AtomicInteger calls = new AtomicInteger();

        // A directory can not be opened as a lock file
        assertThrows(FileNotFoundException.class, () -> fileLockMutex.acquire(1000, tempDir.toString(), () -> {
            calls.incrementAndGet();
            return "never";
        }));

        assertEquals(0, calls.get());
    }

    @Test
    void tryAcquireReturnsTheCallbackResult() {
        final Try<String> result = fileLockMutex.tryAcquire(lockPath("tryAcquire.lock"), () -> "tried");

        assertEquals("tried", result.get());
    }

    @Test
    void tryAcquireReturnsAFailureWhenTheFileIsAlreadyLockedByThisJvm() {
        final String lock = lockPath("tryAcquireOverlap.lock");
        final AtomicInteger innerCalls = new AtomicInteger();

        final Try<String> result = fileLockMutex.acquire(1000, lock, () -> fileLockMutex.tryAcquire(lock, () -> {
            innerCalls.incrementAndGet();
            return "inner";
        }));

        assertTrue(result.isFailure());
        assertInstanceOf(OverlappingFileLockException.class, result.getCause());
        assertEquals(0, innerCalls.get());
    }

    @Test
    void tryAcquireReturnsALockFailWhenTheLocalLockIsHeldByAnotherThread() throws Exception {
        final String lock = lockPath("tryAcquireLocalLock.lock");
        final CountDownLatch locked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            final Future<String> holder = executor.submit(() -> fileLockMutex.acquire(10000, lock, () -> {
                locked.countDown();
                Try.run(release::await);
                return "holder";
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));

            final Try<String> result = fileLockMutex.tryAcquire(lock, () -> "never");

            assertTrue(result.isFailure());
            assertInstanceOf(LockFail.class, result.getCause());
            assertEquals("Failed to obtain local lock for: " + lock, result.getCause().getMessage());

            release.countDown();
            assertEquals("holder", holder.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
