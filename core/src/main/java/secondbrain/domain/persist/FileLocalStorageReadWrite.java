package secondbrain.domain.persist;

import io.vavr.control.Try;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.inject.Inject;
import org.apache.commons.io.output.LockableFileWriter;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.exceptionhandling.ExceptionHandler;
import secondbrain.domain.persist.config.LocalStorageCacheDirectory;
import secondbrain.domain.persist.config.LocalStorageMemoryCacheEnabled;
import secondbrain.domain.persist.config.LocalStorageMemoryCacheFileLimit;
import secondbrain.domain.persist.config.LocalStorageMemoryCacheSizeLimit;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A lot of time can be spent reading and writing local cache files.
 * This implementation uses an optional in-memory cache to speed up reads.
 */
@ApplicationScoped
public class FileLocalStorageReadWrite implements LocalStorageReadWrite {
    private static final Pattern LOCAL_CACHE_TIMESTAMP = Pattern.compile("(.*?)\\.cache\\.(\\d+)");
    private static final Set<String> IGNORED_FILES = Set.of("localstoragev2.mv.db", "localstoragev2.trace.db", "lastclean.marker");
    private static final int LARGE_OBJECT_WARNING_BYTES = 2 * 1024 * 1024;
    private static final String MARKER_FILE_NAME = "lastclean.marker";
    private static final Duration CLEANUP_INTERVAL = Duration.ofHours(1);

    private static final Map<Path, String> MEMORY_CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger TOTAL_READS = new AtomicInteger();
    private static final AtomicInteger FILE_READS = new AtomicInteger();
    private static final AtomicInteger MEMORY_READS = new AtomicInteger();

    /**
     * An in-memory index of cache files keyed by their cache key (tool_source_promptHash).
     * Each value is a list of entries with the file path and expiration timestamp.
     * This avoids repeated filesystem scans via Files.list() on every getString call.
     */
    private static final Map<String, List<CacheFileEntry>> FILE_INDEX = new ConcurrentHashMap<>();

    private record CacheFileEntry(Path path, long timestamp) {
    }

    /**
     * In-memory cache of the last cleanup time (epoch seconds) to avoid hitting the filesystem
     * on every getString call just to check the marker file.
     */
    private static volatile long lastCleanEpochSecond = 0;

    private static String cacheKey(final String tool, final String source, final String promptHash) {
        return tool + "_" + source + "_" + promptHash;
    }

    @Inject
    private Logger logger;

    @Inject
    private ExceptionHandler exceptionHandler;

    @Inject
    private LocalStorageCacheDirectory localStorageCacheDirectory;

    @Inject
    private LocalStorageMemoryCacheEnabled localStorageMemoryCacheEnabled;

    @Inject
    private LocalStorageMemoryCacheSizeLimit localStorageMemoryCacheSizeLimit;

    @Inject
    private LocalStorageMemoryCacheFileLimit localStorageMemoryCacheFileLimit;

    // This observer forces the container to instantiate the bean at startup
    public void onStartup(@Observes Startup event) {
        // Initialization logic here
    }

    @PreDestroy
    private void shutdown() {
        if (TOTAL_READS.get() > 0) {
            logger.info("Local storage cache read stats: total reads=" + TOTAL_READS.get()
                    + ", file reads=" + FILE_READS.get()
                    + ", memory reads=" + MEMORY_READS.get()
                    + ", file read hit rate=" + String.format("%.2f", (FILE_READS.get() * 100.0) / TOTAL_READS.get()) + "%"
                    + ", memory read hit rate=" + String.format("%.2f", (MEMORY_READS.get() * 100.0) / TOTAL_READS.get()) + "%");
        }
    }

    /**
     * When we start this service, preload the most recent cache entries into memory for faster access.
     * We do this in a thread to avoid blocking startup.
     * The idea is that by the time the cache is needed, it will be loaded.
     */
    @PostConstruct
    private void init() {
        final String cacheDir = localStorageCacheDirectory.getCacheDirectory();

        // Ensure the cache directory exists
        Try.run(() -> Files.createDirectories(Path.of(cacheDir)))
                .onFailure(ex -> logger.warning("Failed to create cache directory: " + exceptionHandler.getExceptionMessage(ex)));

        // Build the file index from existing cache files (single directory scan)
        final List<Path> allFiles = Try.of(() -> Path.of(cacheDir))
                .mapTry(Files::list)
                .map(java.util.stream.Stream::toList)
                .getOrElse(List.of());

        allFiles.forEach(file -> {
            final Matcher matcher = LOCAL_CACHE_TIMESTAMP.matcher(file.getFileName().toString());
            if (matcher.matches()) {
                final String key = matcher.group(1);
                final long ts = NumberUtils.toLong(matcher.group(2), 0L);
                FILE_INDEX.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()))
                        .add(new CacheFileEntry(file, ts));
            }
        });

        // Initialize the in-memory cleanup timestamp from the marker file
        final Path markerPath = Path.of(cacheDir, MARKER_FILE_NAME);
        if (Files.exists(markerPath)) {
            lastCleanEpochSecond = Try.of(() -> Files.getLastModifiedTime(markerPath).toInstant().getEpochSecond())
                    .getOrElse(0L);
        }

        if (!localStorageMemoryCacheEnabled.isMemoryCacheEnabled()) {
            return;
        }

        logger.fine("Initializing memory cache from local cache directory " + cacheDir);

        // Preload the most recent cache entries into memory using the already-scanned file list
        final AtomicLong currentCacheSize = new AtomicLong(0L);
        final Thread thread = new Thread(() -> {
            allFiles.stream()
                    .filter(f -> !IGNORED_FILES.contains(f.getFileName().toString()))
                    .map(f -> Pair.of(f, Try.of(() -> Files.readAttributes(f, BasicFileAttributes.class)).getOrNull()))
                    .filter(pair -> pair.getRight() != null)
                    .sorted((f1, f2) -> f2.getRight().lastAccessTime().compareTo(f1.getRight().lastAccessTime()))
                    .limit(localStorageMemoryCacheFileLimit.getMemoryCacheFileLimit())
                    .takeWhile(f -> currentCacheSize.addAndGet(f.getRight().size()) <= localStorageMemoryCacheSizeLimit.getMemoryCacheSizeLimit())
                    .forEach(f -> {
                        if (Thread.interrupted()) {
                            throw new RuntimeException("interrupted");
                        }
                        MEMORY_CACHE.computeIfAbsent(f.getLeft(), this::readFileSilentFail);
                    });
        });
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public Optional<String> getString(final String tool, final String source, final String promptHash) {
        TOTAL_READS.incrementAndGet();

        final String cacheDir = localStorageCacheDirectory.getCacheDirectory();

        clearExpiredEntries(cacheDir);

        final String key = cacheKey(tool, source, promptHash);
        final List<CacheFileEntry> entries = FILE_INDEX.get(key);

        if (entries == null || entries.isEmpty()) {
            return Optional.empty();
        }

        final long now = Instant.now().getEpochSecond();

        // Find the most recent non-expired entry (timestamp 0 means no expiration)
        return entries.stream()
                .filter(e -> e.timestamp() == 0 || e.timestamp() >= now)
                .max(Comparator.comparingLong(CacheFileEntry::timestamp))
                .map(CacheFileEntry::path)
                .map(path -> Try.of(() -> readFile(path)).getOrNull());
    }

    private String readFile(final Path path) {
        if (localStorageMemoryCacheEnabled.isMemoryCacheEnabled()) {
            MEMORY_READS.incrementAndGet();
            final String result = MEMORY_CACHE.computeIfAbsent(path, this::readFileSilentFail);
            warnIfLarge(path, result);
            return result;
        }
        final String result = readFileSilentFail(path);

        if (result != null) {
            FILE_READS.incrementAndGet();
            warnIfLarge(path, result);
        }
        return result;
    }

    private void warnIfLarge(@Nullable final Path path, @Nullable final String value) {
        if (value != null) {
            final int size = value.length() * 2; // approximate byte size for UTF-16 chars
            if (size > LARGE_OBJECT_WARNING_BYTES) {
                logger.warning("Large cached object loaded (" + (size / 1024 / 1024) + " MB) from " + path);
            }
        }
    }

    private String readFileSilentFail(final Path path) {
        return Try.of(() -> Files.readString(path))
                .getOrElse(() -> null);
    }

    @Override
    public String putString(final String tool, final String source, final String promptHash, @Nullable final Long timestamp, final String value) {
        final String cacheDir = localStorageCacheDirectory.getCacheDirectory();
        final long ts = Objects.requireNonNullElse(timestamp, 0L);
        final String key = cacheKey(tool, source, promptHash);

        Try.of(() -> Path.of(cacheDir))
                .map(path -> path.resolve(key + ".cache." + ts))
                // LockableFileWriter deals with multiple threads trying to write to the same file
                .flatMap(path -> Try.withResources(() -> new LockableFileWriter.Builder().setFile(path.toFile()).setAppend(false).get())
                        .of(w -> {
                            w.write(value);
                            // Update the file index
                            FILE_INDEX.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()))
                                    .add(new CacheFileEntry(path, ts));
                            return value;
                        }))
                .onFailure(ex -> {
                    if (ex instanceof OverlappingFileLockException) {
                        logger.warning("Another thread is currently writing to this cache file: " + key);
                    } else if (!(ex instanceof NoSuchElementException)) {
                        logger.warning("Failed to write cache file timestamp: " + exceptionHandler.getExceptionMessage(ex));
                    }
                });
        return value;
    }

    @Override
    public void purge() {
        MEMORY_CACHE.clear();
        FILE_INDEX.clear();
    }


    private void clearExpiredEntries(final String cacheDir) {
        final long now = Instant.now().getEpochSecond();
        final long cleanupIntervalSeconds = CLEANUP_INTERVAL.toSeconds();

        // Fast path: check the in-memory timestamp (no filesystem I/O)
        if (now - lastCleanEpochSecond < cleanupIntervalSeconds) {
            return;
        }

        final Path markerPath = Path.of(cacheDir, MARKER_FILE_NAME);

        // Acquire a lock on the marker file to prevent concurrent cleanup
        try (final FileChannel channel = FileChannel.open(markerPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             final FileLock lock = channel.tryLock()) {

            if (lock == null) {
                // Another thread/process is already cleaning up
                return;
            }

            // Double-check after acquiring the lock (another thread may have just finished)
            if (now - lastCleanEpochSecond < cleanupIntervalSeconds) {
                return;
            }

            // Clear expired cache files
            Try.of(() -> Path.of(cacheDir))
                    .mapTry(Files::list)
                    .map(files -> files
                            .map(file -> LOCAL_CACHE_TIMESTAMP.matcher(file.getFileName().toString()))
                            .filter(Matcher::matches)
                            // Keep files for deletion that have a timestamp that is not 0 (no expiration) and in the past
                            .filter(matcher -> NumberUtils.toLong(matcher.group(2), 0L) != 0 && NumberUtils.toLong(matcher.group(2), 0L) < now)
                            .map(matcher -> Path.of(cacheDir, matcher.group(0)))
                            .toList())
                    .peek(files -> {
                        if (!files.isEmpty()) {
                            logger.fine("Deleting " + files.size() + " expired cache files: " + files);
                        }
                    })
                    .peek(files -> files.forEach(file -> Try.run(() -> Files.delete(file))
                            .onFailure(ex -> {
                                // Ignore race conditions when deleting files
                                if (!(ex instanceof NoSuchFileException)) {
                                    logger.warning("Failed to delete expired cache file " + file + ": " + exceptionHandler.getExceptionMessage(ex));
                                }
                            })));

            // Remove expired entries from the file index
            FILE_INDEX.values().forEach(entries ->
                    entries.removeIf(e -> e.timestamp() != 0 && e.timestamp() < now));
            // Remove any keys that have no remaining entries
            FILE_INDEX.entrySet().removeIf(e -> e.getValue().isEmpty());

            // Update the marker file and in-memory timestamp
            lastCleanEpochSecond = Instant.now().getEpochSecond();
            Files.writeString(markerPath, Instant.ofEpochSecond(lastCleanEpochSecond).toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        } catch (final IOException ex) {
            logger.warning("Failed to acquire lock for cache cleanup: " + exceptionHandler.getExceptionMessage(ex));
        }
    }
}
