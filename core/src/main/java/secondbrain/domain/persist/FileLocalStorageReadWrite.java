package secondbrain.domain.persist;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.exceptionhandling.ExceptionHandler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class FileLocalStorageReadWrite implements LocalStorageReadWrite {
    private static final Pattern LOCAL_CACHE_TIMESTAMP = Pattern.compile("(.*?)\\.cache\\.(\\d+)");
    private static final String LOCAL_CACHE_DIR = "localcache";

    @Inject
    private Logger logger;

    @Inject
    private ExceptionHandler exceptionHandler;

    @Inject
    @ConfigProperty(name = "sb.cache.localdir", defaultValue = LOCAL_CACHE_DIR)
    private Optional<String> localCache;

    @Override
    public Optional<String> getString(final String tool, final String source, final String promptHash) {
        final String cacheDir = localCache.orElse(LOCAL_CACHE_DIR);

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
                .mapTry(Files::readString)
                .getOrNull();
    }

    @Override
    public String putString(final String tool, final String source, final String promptHash, final Long timestamp, final String value) {
        final String cacheDir = localCache.orElse(LOCAL_CACHE_DIR);

        Try.of(() -> Path.of(cacheDir))
                .mapTry(Files::createDirectories)
                .onFailure(ex -> logger.warning("Failed to create cache directory: " + exceptionHandler.getExceptionMessage(ex)))
                .map(path -> path.resolve(tool + "_" + source + "_" + promptHash + ".cache." + Objects.requireNonNullElse(timestamp, 0L)))
                .mapTry(path -> Files.writeString(path, value, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))
                .onFailure(ex -> logger.warning("Failed to write cache file timestamp: " + exceptionHandler.getExceptionMessage(ex)));
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
                        logger.info("Deleting " + files.size() + " expired cache files: " + files);
                    }
                })
                .peek(files -> files.forEach(file -> Try.run(() -> Files.delete(file))
                        .onFailure(ex -> logger.warning("Failed to delete expired cache file " + file + ": " + exceptionHandler.getExceptionMessage(ex)))));
    }
}
