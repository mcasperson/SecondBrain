package secondbrain.domain.limit;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.List;

class DocumentSectionerTest {

    @Test
    void testTrimDocument() {
        DocumentSectioner sectioner = new DocumentSectioner(20);
        String document = "This is a test document. It contains several keywords. This is another sentence with keywords.";
        List<String> keywords = List.of("test", "keywords");

        String expected = "This is a test docum s several keywords. ence with keywords.";
        String result = sectioner.trimDocument(document, keywords);

        assertEquals(expected, result);
    }

    @Test
    void testTrimOverlapDocument() {
        DocumentSectioner sectioner = new DocumentSectioner(20);
        String document = "This is a test keywords document. It contains several test keywords. This is another sentence with keywords.";
        List<String> keywords = List.of("test", "keywords");

        String expected = "This is a test keywords d s several test keywords. ence with keywords.";
        String result = sectioner.trimDocument(document, keywords);

        assertEquals(expected, result);
    }

    @Test
    void testTrimDocumentWithNoKeywords() {
        DocumentSectioner sectioner = new DocumentSectioner();
        String document = "This is a test document. It contains several keywords. This is another sentence with keywords.";
        List<String> keywords = List.of();

        String expected = "";
        String result = sectioner.trimDocument(document, keywords);

        assertEquals(expected, result);
    }

    @Test
    void testTrimDocumentWithNullDocument() {
        DocumentSectioner sectioner = new DocumentSectioner();
        String document = null;
        List<String> keywords = List.of("test", "keywords");

        String expected = "";
        String result = sectioner.trimDocument(document, keywords);

        assertEquals(expected, result);
    }

    @Test
    void testTrimDocumentWithEmptyDocument() {
        DocumentSectioner sectioner = new DocumentSectioner();
        String document = "";
        List<String> keywords = List.of("test", "keywords");

        String expected = "";
        String result = sectioner.trimDocument(document, keywords);

        assertEquals(expected, result);
    }
}