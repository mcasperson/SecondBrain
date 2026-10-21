package secondbrain.domain.reader;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Three test strategies with distinct priorities are registered. "doc.txt" is supported by all of them,
 * "doc.md" only by the lowest priority strategy, and "other.bin" by none.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(FileReaderSelector.class)
@AddBeanClasses(FileReaderSelectorTest.LowPriorityStrategy.class)
@AddBeanClasses(FileReaderSelectorTest.HighPriorityStrategy.class)
@AddBeanClasses(FileReaderSelectorTest.MidPriorityStrategy.class)
class FileReaderSelectorTest {

    @Inject
    private FileReaderSelector selector;

    @Test
    void readUsesTheHighestPrioritySupportingStrategy() {
        assertEquals("high:doc.txt", selector.read("doc.txt"));
    }

    @Test
    void readSkipsHigherPriorityStrategiesThatDoNotSupportThePath() {
        assertEquals("low:doc.md", selector.read("doc.md"));
    }

    @Test
    void readUsesTheMiddleStrategyWhenTheHighestDoesNotSupportThePath() {
        assertEquals("mid:notes.txt", selector.read("notes.txt"));
    }

    @Test
    void readThrowsWhenNoStrategySupportsThePath() {
        assertThrows(NoSuchElementException.class, () -> selector.read("other.bin"));
    }

    @ApplicationScoped
    static class LowPriorityStrategy implements FileReadingStrategy {
        @Override
        public String read(final String pathOrUrl) {
            return "low:" + pathOrUrl;
        }

        @Override
        public boolean isSupported(final String pathOrUrl) {
            return pathOrUrl.startsWith("doc");
        }

        @Override
        public int getPriority() {
            return 1;
        }
    }

    @ApplicationScoped
    static class MidPriorityStrategy implements FileReadingStrategy {
        @Override
        public String read(final String pathOrUrl) {
            return "mid:" + pathOrUrl;
        }

        @Override
        public boolean isSupported(final String pathOrUrl) {
            return pathOrUrl.endsWith(".txt");
        }

        @Override
        public int getPriority() {
            return 5;
        }
    }

    @ApplicationScoped
    static class HighPriorityStrategy implements FileReadingStrategy {
        @Override
        public String read(final String pathOrUrl) {
            return "high:" + pathOrUrl;
        }

        @Override
        public boolean isSupported(final String pathOrUrl) {
            return pathOrUrl.equals("doc.txt");
        }

        @Override
        public int getPriority() {
            return 10;
        }
    }
}
