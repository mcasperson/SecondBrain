package secondbrain.domain.tools.keyword;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeywordsTest {

    @Test
    void parseKeywordsNormalizesAndDeduplicatesValues() {
        final List<String> result = Keywords.parseKeywords("[\" Azure \",\"Kubernetes\",\"Azure\",\"\",\"  \"]");

        assertEquals(List.of("Azure", "Kubernetes"), result);
    }

    @Test
    void parseKeywordsReturnsEmptyListForInvalidJson() {
        final List<String> result = Keywords.parseKeywords("not-json");

        assertEquals(List.of(), result);
    }
}

