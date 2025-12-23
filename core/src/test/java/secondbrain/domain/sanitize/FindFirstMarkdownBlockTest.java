package secondbrain.domain.sanitize;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SuppressWarnings("NullAway")
class FindFirstMarkdownBlockTest {

    private final GetFirstMarkdownBlock removeMarkdownBlock = new GetFirstMarkdownBlock();

    @Test
    void testSanitizeWithEmptyString() {
        String document = "";
        String result = removeMarkdownBlock.sanitize(document);
        assertEquals("", result);
    }

    @Test
    void testSanitizeWithNull() {
        String document = null;
        String result = removeMarkdownBlock.sanitize(document);
        assertNull(result);
    }

    @Test
    void testSanitizeWithWhitespaceOnly() {
        String document = "   \n\t  ";
        String result = removeMarkdownBlock.sanitize(document);
        assertEquals("   \n\t  ", result);
    }

    @Test
    void testSanitizeWithNoMarkdownBlock() {
        String document = "This is regular text without code blocks.";
        String result = removeMarkdownBlock.sanitize(document);
        assertEquals("This is regular text without code blocks.", result);
    }

    @Test
    void testSanitizeWithJavaCodeBlock() {
        String document = "```java\npublic class Test {\n    // code here\n}\n```";
        String result = removeMarkdownBlock.sanitize(document);
        assertEquals("public class Test {\n    // code here\n}", result);
    }

    @Test
    void testSanitizeWithPythonCodeBlock() {
        String document = "```python\ndef hello():\n    print('Hello World')\n```";
        String result = removeMarkdownBlock.sanitize(document);
        assertEquals("def hello():\n    print('Hello World')", result);
    }

    @Test
    void testSanitizeWithEmptyLanguage() {
        String document = "```\nsome code here\n```";
        String result = removeMarkdownBlock.sanitize(document);
        assertEquals("some code here", result);
    }

    @Test
    void testSanitizeWithPreamble() {
        String document = "hi there\n```\nsome code here\n```";
        String result = removeMarkdownBlock.sanitize(document);
        assertEquals("some code here", result);
    }

    @Test
    void testSanitizeWithExtraWhitespace() {
        String document = "   ```java\n\n  code with spaces  \n\n```   ";
        String result = removeMarkdownBlock.sanitize(document);
        assertEquals("\n  code with spaces  \n", result);
    }

    @Test
    void testSanitizeWithPartialMarkdownBlock() {
        String document = "```java\nincomplete code block";
        String result = removeMarkdownBlock.sanitize(document);
        assertEquals("```java\nincomplete code block", result);
    }

    @Test
    void testSanitizeWithSingleLineCodeBlock() {
        String document = "```java\nSystem.out.println(\"Hello\");\n```";
        String result = removeMarkdownBlock.sanitize(document);
        assertEquals("System.out.println(\"Hello\");", result);
    }
}