package secondbrain.domain.persist;

import io.vavr.control.Try;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
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

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
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
    private static final List<String> IGNORED_FILES = List.of("localstoragev2.mv.db", "localstoragev2.trace.db");

    private static final Map<Path, String> MEMORY_CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger TOTAL_READS = new AtomicInteger();
    private static final AtomicInteger FILE_READS = new AtomicInteger();
    private static final AtomicInteger MEMORY_READS = new AtomicInteger();

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
        if (!localStorageMemoryCacheEnabled.isMemoryCacheEnabled()) {
            return;
        }

        final String cacheDir = localStorageCacheDirectory.getCacheDirectory();

        logger.fine("Initializing memory cache from local cache directory " + cacheDir);

        final AtomicLong currentCacheSize = new AtomicLong(0L);
        final Thread thread = new Thread(() ->
                Try.of(() -> Path.of(cacheDir))
                        .mapTry(Files::list)
                        .map(files -> files
                                .filter(f -> !IGNORED_FILES.contains(f.getFileName().toString()))
                                // Get the file attributes
                                .map(f -> Pair.of(f, Try.of(() -> Files.readAttributes(f, BasicFileAttributes.class)).getOrNull()))
                                // filter out any file we couldn't get attributes for
                                .filter(pair -> pair.getRight() != null)
                                // Sort by last access time, descending
                                .sorted((f1, f2) -> f2.getRight().lastAccessTime().compareTo(f1.getRight().lastAccessTime()))
                                // Limit by number of files
                                .limit(localStorageMemoryCacheFileLimit.getMemoryCacheFileLimit())
                                // Take while we have not exceeded the max cache size
                                .takeWhile(f -> currentCacheSize.addAndGet(f.getRight().size()) <= localStorageMemoryCacheSizeLimit.getMemoryCacheSizeLimit())
                                .toList())
                        // Load the files into memory
                        .peek(files -> files.forEach(f -> {
                            // If we didn't finish loading before shutdown, stop loading
                            if (Thread.interrupted()) {
                                throw new RuntimeException("interrupted");
                            }

                            MEMORY_CACHE.computeIfAbsent(f.getLeft(), this::readFileSilentFail);
                        }))
        );
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public Optional<String> getString(final String tool, final String source, final String promptHash) {
        TOTAL_READS.incrementAndGet();

        final String cacheDir = localStorageCacheDirectory.getCacheDirectory();

        clearExpiredEntries(cacheDir);
        return Optional.ofNullable(getString(cacheDir, tool, source, promptHash));
    }

    private String getString(final String cacheDir, final String tool, final String source, final String promptHash) {
        return Try.of(() -> Path.of(cacheDir))
                .mapTry(Files::list)
                // Find the matching cache file that is not expired
                .map(files -> files
                        // Files must match the regex
                        .map(file -> LOCAL_CACHE_TIMESTAMP.matcher(file.getFileName().toString()))
                        .filter(Matcher::matches)
                        // the first group of the regex match must match the tool, source, and promptHash
                        .filter(matcher -> matcher.group(1).equals(tool + "_" + source + "_" + promptHash))
                        // The second group (timestamp) must be 0 or in the future
                        .filter(matcher -> NumberUtils.toLong(matcher.group(2), -1L) == 0 || NumberUtils.toLong(matcher.group(2), 0L) >= Instant.now().getEpochSecond())
                        // get the most recent item
                        .sorted((Matcher m1, Matcher m2) -> Long.compare(
                                Long.parseLong(m2.group(2)),
                                Long.parseLong(m1.group(2))
                        ))
                        // We want the path of the first matching file
                        .map(matcher -> Path.of(cacheDir, matcher.group(0)))
                        // Get teh first matching file
                        .findFirst())
                // Get the cached result, failing if the Optional is empty
                .map(Optional::get)
                .mapTry(this::readFile)
                .getOrNull();
    }

    private String readFile(final Path path) {
        if (localStorageMemoryCacheEnabled.isMemoryCacheEnabled()) {
            MEMORY_READS.incrementAndGet();
            return MEMORY_CACHE.computeIfAbsent(path, this::readFileSilentFail);
        }
        final String result = readFileSilentFail(path);

        if (result != null) {
            FILE_READS.incrementAndGet();
        }
        return result;
    }

    private String readFileSilentFail(final Path path) {
        return Try.of(() -> Files.readString(path))
                .getOrElse(() -> null);
    }

    @Override
    public String putString(final String tool, final String source, final String promptHash, @Nullable final Long timestamp, final String value) {
        final String cacheDir = localStorageCacheDirectory.getCacheDirectory();

        Try.of(() -> Path.of(cacheDir))
                .mapTry(Files::createDirectories)
                .onFailure(ex -> logger.warning("Failed to create cache directory: " + exceptionHandler.getExceptionMessage(ex)))
                .map(path -> path.resolve(tool + "_" + source + "_" + promptHash + ".cache." + Objects.requireNonNullElse(timestamp, 0L)))
                // LockableFileWriter deals with multiple threads trying to write to the same file
                .flatMap(path -> Try.withResources(() -> new LockableFileWriter.Builder().setFile(path.toFile()).setAppend(false).get())
                        .of(w -> {
                            w.write(value);
                            return value;
                        }))
                .onFailure(ex -> {
                    if (!(ex instanceof NoSuchElementException)) {
                        logger.warning("Failed to write cache file timestamp: " + exceptionHandler.getExceptionMessage(ex));
                    }
                });
        return value;
    }


    private void clearExpiredEntries(final String cacheDir) {
        // Clear expired cache files
        Try.of(() -> Path.of(cacheDir))
                .mapTry(Files::list)
                .map(files -> files
                        .map(file -> LOCAL_CACHE_TIMESTAMP.matcher(file.getFileName().toString()))
                        .filter(Matcher::matches)
                        // Keep files for deletion that have a timestamp that is not 0 (no expiration) and in the past
                        .filter(matcher -> NumberUtils.toLong(matcher.group(2), 0L) != 0 && NumberUtils.toLong(matcher.group(2), 0L) < Instant.now().getEpochSecond())
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
    }
}
