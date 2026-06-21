package secondbrain.domain.answer;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import secondbrain.domain.constants.ModelRegex;
import secondbrain.domain.test.TestConfigUtil;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("NullAway")
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(AnswerFormatterQwen.class)
public class AnswerFormatterQwenTest {

    @Inject
    private AnswerFormatterQwen formatter;

    @BeforeEach
    void updateConfig() {
        TestConfigUtil.registerConfig(Map.of(
                "sb.infrastructure.mock", "true",
                "sb.answerformatter.qwenregex", ModelRegex.QWEN_REGEX));
    }

    @Test
    public void testFormatAnswer_RemovesThinkingTags() {
        String input = "This is a test <think>remove this</think> string.";
        String expected = "This is a test  string.";
        String actual = formatter.formatAnswer(input);
        assertEquals(expected, actual);
    }

    @Test
    public void testFormatAnswer_RemovesEndTag() {
        String input = "This is a test remove this\n</think> string.";
        String expected = "string.";
        String actual = formatter.formatAnswer(input);
        assertEquals(expected, actual);
    }

    @Test
    public void testFormatAnswer_NoThinkingTags() {
        String input = "This is a test string.";
        String expected = "This is a test string.";
        String actual = formatter.formatAnswer(input);
        assertEquals(expected, actual);
    }

    @Test
    public void testFormatAnswer_BlankInput() {
        String input = "";
        String expected = "";
        String actual = formatter.formatAnswer(input);
        assertEquals(expected, actual);
    }

    @Test
    public void testFormatAnswer_NullInput() {
        String input = null;
        String expected = "";
        String actual = formatter.formatAnswer(input);
        assertEquals(expected, actual);
    }

    @Test
    public void testModelRegex_MatchesDeepseekR1() {
        String regex = formatter.modelRegex();
        assertFalse("deepseek-r1".matches(regex));
        assertFalse("deepseek-r1:32b".matches(regex));
        assertFalse("phi4".matches(regex));
        assertTrue("qwen3".matches(regex));
        assertTrue("qwen2".matches(regex));
    }
}