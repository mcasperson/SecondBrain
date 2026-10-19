package secondbrain.domain.hooks;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.test.TestHooks;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(NamedHooksContainer.class)
// Null names are passed deliberately to cover the blank name handling
@SuppressWarnings("NullAway")
class NamedHooksContainerTest {
    private final PreProcessingHook alpha = TestHooks.upperCase("Alpha");
    private final PreProcessingHook beta = TestHooks.appendDocument("Beta", "label", "text");
    private final PostInferenceHook gamma = TestHooks.appendResponse("Gamma", "!");
    private final PostInferenceHook delta = TestHooks.appendResponse("Delta", "?");

    @Inject
    private NamedHooksContainer container;

    @Produces
    PreProcessingHook produceAlpha() {
        return alpha;
    }

    @Produces
    PreProcessingHook produceBeta() {
        return beta;
    }

    @Produces
    PostInferenceHook produceGamma() {
        return gamma;
    }

    @Produces
    PostInferenceHook produceDelta() {
        return delta;
    }

    @BeforeEach
    void setUp() {
        TestConfigUtil.registerConfig(Map.of());
    }

    @Test
    void hookNamesAreSplitTrimmedAndDeduplicatedInOrder() {
        assertEquals(List.of("Beta", "Alpha", "Gamma"), container.getHookNames(" Beta , Alpha,,  ,Beta,Gamma "));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void hookNamesAreEmptyForBlankNames(final String name) {
        assertEquals(List.of(), container.getHookNames(name));
    }

    @Test
    void matchingPreProcessorHooksFollowTheRequestedOrder() {
        assertEquals(List.of(beta, alpha), container.getMatchingPreProcessorHooks("Beta,Alpha"));
        assertEquals(List.of(alpha, beta), container.getMatchingPreProcessorHooks("Alpha, Beta"));
    }

    @Test
    void matchingPreProcessorHooksSkipUnknownAndPostInferenceNames() {
        assertEquals(List.of(alpha), container.getMatchingPreProcessorHooks("Unknown,Gamma,Alpha"));
    }

    @Test
    void matchingPreProcessorHooksAreDeduplicated() {
        assertEquals(List.of(alpha), container.getMatchingPreProcessorHooks("Alpha,Alpha"));
    }

    @Test
    void matchingPreProcessorHookNamesAreCaseSensitive() {
        assertEquals(List.of(), container.getMatchingPreProcessorHooks("alpha"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " , "})
    void matchingPreProcessorHooksAreEmptyForBlankNames(final String name) {
        assertEquals(List.of(), container.getMatchingPreProcessorHooks(name));
    }

    @Test
    void matchingPostInferenceHooksFollowTheRequestedOrder() {
        assertEquals(List.of(delta, gamma), container.getMatchingPostInferenceHooks("Delta,Gamma"));
        assertEquals(List.of(gamma, delta), container.getMatchingPostInferenceHooks("Gamma,Delta,Gamma"));
    }

    @Test
    void matchingPostInferenceHooksSkipUnknownAndPreProcessorNames() {
        assertEquals(List.of(gamma), container.getMatchingPostInferenceHooks("Alpha,Gamma,Missing"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void matchingPostInferenceHooksAreEmptyForBlankNames(final String name) {
        assertEquals(List.of(), container.getMatchingPostInferenceHooks(name));
    }

    @Test
    void preProcessorHookByNameMatchesTheExactName() {
        assertEquals(Optional.of(beta), container.getPreProcessorHookByName("Beta"));
        assertEquals(Optional.empty(), container.getPreProcessorHookByName("Gamma"));
        // Names are not trimmed when looking up a single hook
        assertEquals(Optional.empty(), container.getPreProcessorHookByName(" Beta"));
    }

    @Test
    void postInferenceHookByNameMatchesTheExactName() {
        assertEquals(Optional.of(delta), container.getPostInferenceHookByName("Delta"));
        assertEquals(Optional.empty(), container.getPostInferenceHookByName("Alpha"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void hookByNameIsEmptyForBlankNames(final String name) {
        assertEquals(Optional.empty(), container.getPreProcessorHookByName(name));
        assertEquals(Optional.empty(), container.getPostInferenceHookByName(name));
    }
}
