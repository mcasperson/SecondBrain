package secondbrain.domain.files;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AntPathMatcherTest {

    private final AntPathMatcher matcher = new AntPathMatcher();

    @Test
    void matchesExactPath() {
        assertTrue(matcher.matches("src/main/App.java", "src/main/App.java"));
    }

    @Test
    void doesNotMatchDifferentPath() {
        assertFalse(matcher.matches("src/main/App.java", "src/test/App.java"));
    }

    @Test
    void questionMarkMatchesExactlyOneCharacter() {
        assertTrue(matcher.matches("file?.txt", "file1.txt"));
        assertFalse(matcher.matches("file?.txt", "file.txt"));
        assertFalse(matcher.matches("file?.txt", "file12.txt"));
    }

    @Test
    void starMatchesAnySequenceOfCharacters() {
        assertTrue(matcher.matches("*.java", "App.java"));
        assertTrue(matcher.matches("src/*.java", "src/main/App.java"));
        assertFalse(matcher.matches("*.java", "App.txt"));
    }

    @Test
    void doubleStarMatchesNestedDirectories() {
        assertTrue(matcher.matches("src/**/App.java", "src/main/java/App.java"));
    }

    @Test
    void normalizesBackslashesInFilePath() {
        assertTrue(matcher.matches("src/main/*.java", "src\\main\\App.java"));
    }

    @Test
    void matchingIsCaseSensitive() {
        assertFalse(matcher.matches("*.JAVA", "App.java"));
    }
}
