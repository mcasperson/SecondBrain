package secondbrain.domain.files;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import secondbrain.domain.test.TestConfigUtil;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(DefaultPathBuilder.class)
@AddBeanClasses(DefaultFileSanitizer.class)
public class DefaultPathBuilderTest {
    @Inject
    private DefaultPathBuilder pathBuilder;

    @BeforeEach
    void updateConfig() {
        TestConfigUtil.registerConfig(Map.of("sb.infrastructure.mock", "true"));
    }

    @Test
    public void testAbsolutePath() {
        final String absolutePath = "/tmp/test/file.txt";
        final Path result = pathBuilder.getFilePath("/base/dir", absolutePath);

        Assertions.assertTrue(result.isAbsolute());
        Assertions.assertEquals(Paths.get(absolutePath), result);
    }

    @Test
    public void testRelativePathWithDirectory() {
        final String directory = "/base/dir";
        final String relativePath = "subdir/file.txt";
        final Path result = pathBuilder.getFilePath(directory, relativePath);

        Assertions.assertEquals(Paths.get("/base/dir/subdir_file.txt"), result);
    }

    @Test
    public void testRelativePathWithoutDirectory() {
        final String relativePath = "file.txt";
        final Path result = pathBuilder.getFilePath("", relativePath);

        Assertions.assertEquals(Paths.get(relativePath), result);
    }

    @Test
    public void testRelativePathWithNullDirectory() {
        final String relativePath = "subdir/file.txt";
        final Path result = pathBuilder.getFilePath(null, relativePath);

        Assertions.assertEquals(Paths.get(relativePath), result);
    }

    @Test
    public void testPathWithSpecialCharacters() {
        final String directory = "/base/dir";
        final String pathWithSpecial = "my file & data.txt";
        final Path result = pathBuilder.getFilePath(directory, pathWithSpecial);

        // The sanitizer should handle special characters
        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.toString().contains(directory));
    }
}

