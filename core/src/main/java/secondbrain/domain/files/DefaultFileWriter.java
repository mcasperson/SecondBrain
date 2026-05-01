package secondbrain.domain.files;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

@ApplicationScoped
public class DefaultFileWriter implements FileWriter {
    @Inject
    private Logger logger;

    @Override
    public void write(final String path, final String content) {
        checkArgument(StringUtils.isNotBlank(path));
        checkNotNull(content);

        write(Path.of(path), content);
    }

    @Override
    public void write(final Path path, final String content) {
        checkNotNull(path);
        checkNotNull(content);

        Try.run(() -> Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))
                .onFailure(e -> logger.severe("Failed to write output to file " + path + ": " + e.getMessage()));
    }

    @Override
    public void append(final String path, final String content) {
        checkArgument(StringUtils.isNotBlank(path));
        checkNotNull(content);

        append(Path.of(path), content);
    }

    @Override
    public void append(final Path path, final String content) {
        checkNotNull(path);
        checkNotNull(content);

        Try.run(() -> Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.APPEND))
                .onFailure(e -> logger.severe("Failed to append output to file " + path + ": " + e.getMessage()));
    }
}
