package secondbrain.domain.limit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DocumentTrimmerExactKeywordsTest {

    @Test
    void testTrimDocumentToKeywords() {
        DocumentTrimmerExactKeywords sectioner = new DocumentTrimmerExactKeywords();
        String document = "This is a test document. It contains several keywords. This is another sentence with keywords.";
        List<String> keywords = List.of("test", "keywords");

        String expected = "is a test document. eral keywords. This with keywords.";
        TrimResult result = sectioner.trimDocumentToKeywords(document, keywords, 20);

        assertEquals(expected, result.document());
    }

    @Test
    void testTrimOverlapDocument() {
        DocumentTrimmerExactKeywords sectioner = new DocumentTrimmerExactKeywords();
        String document = "This is a test keywords document. It contains several test keywords. This is another sentence with keywords.";
        List<String> keywords = List.of("test", "keywords");

        String expected = "is a test keywords docume eral test keywords. This with keywords.";
        TrimResult result = sectioner.trimDocumentToKeywords(document, keywords, 20);

        assertEquals(expected, result.document());
    }

    @Test
    void testTrimDocumentToKeywordsWithNoKeywords() {
        DocumentTrimmerExactKeywords sectioner = new DocumentTrimmerExactKeywords();
        String document = "This is a test document. It contains several keywords. This is another sentence with keywords.";
        List<String> keywords = List.of();

        TrimResult result = sectioner.trimDocumentToKeywords(document, keywords, 20);

        assertEquals(document, result.document());
    }

    @Test
    void testTrimDocumentWithNullDocumentToKeywords() {
        DocumentTrimmerExactKeywords sectioner = new DocumentTrimmerExactKeywords();
        String document = null;
        List<String> keywords = List.of("test", "keywords");

        String expected = "";
        TrimResult result = sectioner.trimDocumentToKeywords(document, keywords, 20);

        assertEquals(expected, result.document());
    }

    @Test
    void testTrimDocumentWithEmptyDocumentToKeywords() {
        DocumentTrimmerExactKeywords sectioner = new DocumentTrimmerExactKeywords();
        String document = "";
        List<String> keywords = List.of("test", "keywords");

        String expected = "";
        TrimResult result = sectioner.trimDocumentToKeywords(document, keywords, 20);

        assertEquals(expected, result.document());
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

    @Test
    void testIsWholeWord() {
        DocumentTrimmerExactKeywords sectioner = new DocumentTrimmerExactKeywords();

        String document = "This is a test: document with keywords.";

        // Test when the keyword is a whole word
        assertTrue(sectioner.isWholeWord(document, "test", document.indexOf("test")));

        // Test when the keyword is part of another word
        assertFalse(sectioner.isWholeWord(document, "tes", document.indexOf("tes")));

        // Test when the keyword is at the start of the document
        assertTrue(sectioner.isWholeWord(document, "This", document.indexOf("This")));

        // Test when the keyword is at the end of the document
        assertTrue(sectioner.isWholeWord(document, "keywords", document.indexOf("keywords")));

        // Test when the keyword is not a whole word at the end of the document
        assertFalse(sectioner.isWholeWord(document, "keyword", document.indexOf("keyword")));
    }
}