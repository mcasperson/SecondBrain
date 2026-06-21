package secondbrain.domain.hooks;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.test.TestConfigUtil;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(RegexFilterHook.class)
@AddBeanClasses(Loggers.class)
class RegexFilterHookTest {

    @Inject
    RegexFilterHook regexFilterHook;

    /**
     * <a href="https://github.com/weld/weld-testing/issues/81#issuecomment-1564002983">...</a>
     */
    @BeforeEach
    void updateConfig() {
        TestConfigUtil.registerConfig(Map.of(
                "sb.regexfilterhook.regex", "\\[Something\\] \\[Category\\] \\[Action\\]"));
    }

    @Test
    void testProcessWithValidRegex() {
        List<RagDocumentContext<Void>> input = List.of(
                new RagDocumentContext<>("tool1", "label1", "This contains an error message", List.of()),
                new RagDocumentContext<>("tool2", "label2", "This is fine", List.of()),
                new RagDocumentContext<>("tool3", "label3", "[Something] [Category] [Action]", List.of())
        );

        List<RagDocumentContext<Void>> result = regexFilterHook.process("TestTool", input);

        assertEquals(2, result.size());
    }
}

