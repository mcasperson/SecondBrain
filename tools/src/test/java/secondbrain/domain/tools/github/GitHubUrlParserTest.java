package secondbrain.domain.tools.github;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SuppressWarnings("NullAway")
public class GitHubUrlParserTest {

    @Test
    public void testUrlToCommitHash() {
        String url = "https://github.com/user/repo/commit/abc123";
        String expectedHash = "abc123";
        String actualHash = GitHubUrlParser.urlToCommitHash(url);
        assertEquals(expectedHash, actualHash);
    }

    @Test
    public void testUrlToCommitHashWithTrailingSlash() {
        String url = "https://github.com/user/repo/commit/abc123/";
        String expectedHash = "abc123";
        String actualHash = GitHubUrlParser.urlToCommitHash(url);
        assertEquals(expectedHash, actualHash);
    }

    @Test
    public void testUrlToCommitHashWithDifferentStructure() {
        String url = "https://github.com/user/repo/commits/abc123";
        String expectedHash = "abc123";
        String actualHash = GitHubUrlParser.urlToCommitHash(url);
        assertEquals(expectedHash, actualHash);
    }

    @Test
    public void testUrlToCommitHashWithNullString() {
        String url = null;
        String expectedHash = "";
        String actualHash = GitHubUrlParser.urlToCommitHash(url);
        assertEquals(expectedHash, actualHash);
    }
}