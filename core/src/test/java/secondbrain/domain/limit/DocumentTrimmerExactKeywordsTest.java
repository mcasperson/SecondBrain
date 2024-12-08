package secondbrain.domain.limit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentTrimmerExactKeywordsTest {

    @Test
    void testTrimDocument() {
        DocumentTrimmerExactKeywords sectioner = new DocumentTrimmerExactKeywords();
        String document = "This is a test document. It contains several keywords. This is another sentence with keywords.";
        List<String> keywords = List.of("test", "keywords");

        String expected = "This is a test docum s several keywords. ence with keywords.";
        String result = sectioner.trimDocument(document, keywords, 20);

        assertEquals(expected, result);
    }

    @Test
    void testTrimOverlapDocument() {
        DocumentTrimmerExactKeywords sectioner = new DocumentTrimmerExactKeywords();
        String document = "This is a test keywords document. It contains several test keywords. This is another sentence with keywords.";
        List<String> keywords = List.of("test", "keywords");

        String expected = "This is a test keywords d s several test keywords. ence with keywords.";
        String result = sectioner.trimDocument(document, keywords, 20);

        assertEquals(expected, result);
    }

    @Test
    void testTrimDocumentWithNoKeywords() {
        DocumentTrimmerExactKeywords sectioner = new DocumentTrimmerExactKeywords();
        String document = "This is a test document. It contains several keywords. This is another sentence with keywords.";
        List<String> keywords = List.of();

        String result = sectioner.trimDocument(document, keywords, 20);

        assertEquals(document, result);
    }

    @Test
    void testTrimDocumentWithNullDocument() {
        DocumentTrimmerExactKeywords sectioner = new DocumentTrimmerExactKeywords();
        String document = null;
        List<String> keywords = List.of("test", "keywords");

        String expected = "";
        String result = sectioner.trimDocument(document, keywords, 20);

        assertEquals(expected, result);
    }

    @Test
    void testTrimDocumentWithEmptyDocument() {
        DocumentTrimmerExactKeywords sectioner = new DocumentTrimmerExactKeywords();
        String document = "";
        List<String> keywords = List.of("test", "keywords");

        String expected = "";
        String result = sectioner.trimDocument(document, keywords, 20);

        assertEquals(expected, result);
    }

    @Test
    void testMergeSections() {
        DocumentTrimmerExactKeywords sectioner = new DocumentTrimmerExactKeywords();

        List<Section> sections = List.of(
                new Section(0, 10, Set.of("keyword1")),
                new Section(5, 15, Set.of("keyword2")),
                new Section(20, 30, Set.of("keyword1")),
                new Section(25, 35, Set.of("keyword2"))
        );

        List<Section> expected = List.of(
                new Section(0, 15, Set.of("keyword1", "keyword2")),
                new Section(20, 35, Set.of("keyword1", "keyword2"))
        );

        List<Section> result = sectioner.mergeSections(sections);

        assertEquals(expected, result);
    }

    @Test
    void testMergeSectionsSorting() {
        DocumentTrimmerExactKeywords sectioner = new DocumentTrimmerExactKeywords();

        List<Section> sections = List.of(
                new Section(25, 35, Set.of("keyword1")),
                new Section(0, 10, Set.of("keyword2")),
                new Section(5, 15, Set.of("keyword1")),
                new Section(20, 30, Set.of("keyword2"))
        );

        List<Section> expected = List.of(
                new Section(0, 15, Set.of("keyword1", "keyword2")),
                new Section(20, 35, Set.of("keyword1", "keyword2"))
        );

        List<Section> result = sectioner.mergeSections(sections);

        assertEquals(expected, result);
    }

    @Test
    void testMergeSectionsWithNoOverlap() {
        DocumentTrimmerExactKeywords sectioner = new DocumentTrimmerExactKeywords();

        List<Section> sections = List.of(
                new Section(0, 10, Set.of("keyword1")),
                new Section(15, 25, Set.of("keyword1")),
                new Section(30, 40, Set.of("keyword1"))
        );

        List<Section> expected = List.of(
                new Section(0, 10, Set.of("keyword1")),
                new Section(15, 25, Set.of("keyword1")),
                new Section(30, 40, Set.of("keyword1"))
        );

        List<Section> result = sectioner.mergeSections(sections);

        assertEquals(expected, result);
    }

    @Test
    void testMergeSectionsWithContainedSections() {
        DocumentTrimmerExactKeywords sectioner = new DocumentTrimmerExactKeywords();

        List<Section> sections = List.of(
                new Section(0, 20, Set.of("keyword1")),
                new Section(5, 10, Set.of("keyword1")),
                new Section(15, 25, Set.of("keyword1"))
        );

        List<Section> expected = List.of(
                new Section(0, 25, Set.of("keyword1"))
        );

        List<Section> result = sectioner.mergeSections(sections);

        assertEquals(expected, result);
    }
}