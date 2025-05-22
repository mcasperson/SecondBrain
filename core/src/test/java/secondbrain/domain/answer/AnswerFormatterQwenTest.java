package secondbrain.domain.answer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AnswerFormatterQwenTest {

    @Test
    public void testFormatAnswer_RemovesThinkingTags() {
        AnswerFormatterQwen formatter = new AnswerFormatterQwen();
        String input = "This is a test <think>remove this</think> string.";
        String expected = "This is a test  string.";
        String actual = formatter.formatAnswer(input);
        assertEquals(expected, actual);
    }

    @Test
    public void testFormatAnswer_RemovesEndTag() {
        AnswerFormatterQwen formatter = new AnswerFormatterQwen();
        String input = "This is a test remove this\n</think> string.";
        String expected = "string.";
        String actual = formatter.formatAnswer(input);
        assertEquals(expected, actual);
    }

    @Test
    public void testFormatAnswer_NoThinkingTags() {
        AnswerFormatterQwen formatter = new AnswerFormatterQwen();
        String input = "This is a test string.";
        String expected = "This is a test string.";
        String actual = formatter.formatAnswer(input);
        assertEquals(expected, actual);
    }

    @Test
    public void testFormatAnswer_BlankInput() {
        AnswerFormatterQwen formatter = new AnswerFormatterQwen();
        String input = "";
        String expected = "";
        String actual = formatter.formatAnswer(input);
        assertEquals(expected, actual);
    }

    @Test
    public void testFormatAnswer_NullInput() {
        AnswerFormatterQwen formatter = new AnswerFormatterQwen();
        String input = null;
        String expected = "";
        String actual = formatter.formatAnswer(input);
        assertEquals(expected, actual);
    }

    @Test
    public void testModelRegex_MatchesDeepseekR1() {
        AnswerFormatterQwen formatter = new AnswerFormatterQwen();
        String regex = formatter.modelRegex();
        assertFalse("deepseek-r1".matches(regex));
        assertFalse("deepseek-r1:32b".matches(regex));
        assertFalse("phi4".matches(regex));
        assertTrue("qwen3".matches(regex));
        assertTrue("qwen2".matches(regex));
    }
}