package secondbrain.domain.context;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleSentenceSplitterTest {

    @Test
    void testSplitDocument() {
        SimpleSentenceSplitter splitter = new SimpleSentenceSplitter();

        String document = "This is a test document. It contains multiple sentences! Does it work? Yes, it does.";
        List<String> sentences = splitter.splitDocument(document, 1);

        assertEquals(4, sentences.size());
        assertTrue(sentences.contains("This is a test document"));
        assertTrue(sentences.contains("It contains multiple sentences"));
        assertTrue(sentences.contains("Does it work"));
        assertTrue(sentences.contains("Yes, it does"));
    }

    @Test
    void testSplitDocumentWithMinWords() {
        SimpleSentenceSplitter splitter = new SimpleSentenceSplitter();

        String document = "Short. This is a longer sentence. Another short one.";
        List<String> sentences = splitter.splitDocument(document, 3);

        assertEquals(2, sentences.size());
        assertTrue(sentences.contains("This is a longer sentence"));
    }

    @Test
    void testFilename() {
        SimpleSentenceSplitter splitter = new SimpleSentenceSplitter();

        String document = "This is a longer sentence with an example filename of example.png.";
        List<String> sentences = splitter.splitDocument(document, 3);

        assertEquals(1, sentences.size());
        assertTrue(sentences.contains("This is a longer sentence with an example filename of example.png"));
    }

    @Test
    void testSplitDocumentWithListFormatting() {
        SimpleSentenceSplitter splitter = new SimpleSentenceSplitter();

        String document = "* First item. • Second item. ◦ Third item. - Fourth item. 1. Fifth item.";
        List<String> sentences = splitter.splitDocument(document, 1);

        assertEquals(6, sentences.size());
        assertTrue(sentences.contains("First item"));
        assertTrue(sentences.contains("Second item"));
        assertTrue(sentences.contains("Third item"));
        assertTrue(sentences.contains("Fourth item"));
        assertTrue(sentences.contains("1"));
        assertTrue(sentences.contains("Fifth item"));
    }

    @Test
    void testSplitDocumentWithNull() {
        SimpleSentenceSplitter splitter = new SimpleSentenceSplitter();

        List<String> sentences = splitter.splitDocument(null, 1);

        assertTrue(sentences.isEmpty());
    }
}