package secondbrain.domain.answer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AnswerFormatterDeepseekTest {

    @Test
    public void testFormatAnswer_RemovesThinkingTags() {
        AnswerFormatterDeepseek formatter = new AnswerFormatterDeepseek();
        String input = "This is a test <think>remove this</think> string.";
        String expected = "This is a test  string.";
        String actual = formatter.formatAnswer(input);
        assertEquals(expected, actual);
    }

    @Test
    public void testFormatAnswer_NoThinkingTags() {
        AnswerFormatterDeepseek formatter = new AnswerFormatterDeepseek();
        String input = "This is a test string.";
        String expected = "This is a test string.";
        String actual = formatter.formatAnswer(input);
        assertEquals(expected, actual);
    }

    @Test
    public void testFormatAnswer_BlankInput() {
        AnswerFormatterDeepseek formatter = new AnswerFormatterDeepseek();
        String input = "";
        String expected = "";
        String actual = formatter.formatAnswer(input);
        assertEquals(expected, actual);
    }

    @Test
    public void testFormatAnswer_NullInput() {
        AnswerFormatterDeepseek formatter = new AnswerFormatterDeepseek();
        String input = null;
        String expected = "";
        String actual = formatter.formatAnswer(input);
        assertEquals(expected, actual);
    }

    @Test
    public void testModelRegex_MatchesDeepseekR1() {
        AnswerFormatterDeepseek formatter = new AnswerFormatterDeepseek();
        String regex = formatter.modelRegex();
        assertTrue("deepseek-r1".matches(regex));
        assertTrue("deepseek-r1:32b".matches(regex));
        assertFalse("phi4".matches(regex));
    }
}