package secondbrain.domain.persist;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import secondbrain.domain.exceptionhandling.LoggingExceptionHandler;
import secondbrain.domain.files.FileUtilsImpl;
import secondbrain.domain.json.JsonDeserializerJackson;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.sanitize.FinancialLocationContactRedaction;
import secondbrain.domain.test.TestConfigUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Verifies that a corrupt database file is moved aside and replaced with a fresh one.
 */
@SuppressWarnings("NullAway")
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(H2LocalStorage.class)
@AddBeanClasses(FileUtilsImpl.class)
@AddBeanClasses(Loggers.class)
@AddBeanClasses(LoggingExceptionHandler.class)
@AddBeanClasses(JsonDeserializerJackson.class)
@AddBeanClasses(FinancialLocationContactRedaction.class)
public class H2LocalStorageCorruptionTest {

    private static final String DATABASE_FILE = "localstoragev2.mv.db";

    @Inject
    H2LocalStorage h2LocalStorage;

    private Path databaseDirectory;

    /**
     * <a href="https://github.com/weld/weld-testing/issues/81#issuecomment-1564002983">...</a>
     */
    @BeforeEach
    void updateConfig(@TempDir final Path tempDir) {
        databaseDirectory = tempDir;
        TestConfigUtil.registerConfig(Map.of(
                "sb.infrastructure.mock", "true",
                "sb.cache.disable", "false",
                "sb.cache.autoserver", "false",
                "sb.h2cache.path", tempDir.toString()));
    }

    @Test
    public void testCorruptDatabaseIsReplaced() throws IOException {
        final byte[] garbage = new byte[16384];
        Arrays.fill(garbage, (byte) 'x');
        Files.write(databaseDirectory.resolve(DATABASE_FILE), garbage);

        final String randomValue = UUID.randomUUID().toString();
        Assertions.assertEquals(randomValue, h2LocalStorage.getOrPutString(
                        H2LocalStorageCorruptionTest.class.getSimpleName(),
                        "test",
                        randomValue,
                        () -> randomValue)
                .result());

        try (Stream<Path> files = Files.list(databaseDirectory)) {
            final var names = files.map(p -> p.getFileName().toString()).toList();
            Assertions.assertTrue(
                    names.stream().anyMatch(n -> n.startsWith(DATABASE_FILE + ".corrupt-")),
                    "Corrupt file should be kept: " + names);
            Assertions.assertTrue(names.contains(DATABASE_FILE), "Fresh database should exist: " + names);
        }

        Assertions.assertNotNull(h2LocalStorage.getString(
                        H2LocalStorageCorruptionTest.class.getSimpleName(),
                        "test",
                        randomValue)
                .result());
    }
}
