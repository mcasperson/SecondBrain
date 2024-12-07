package secondbrain.domain.context;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RagDocumentContextTest {

    @Test
    void testGetDocumentRight() {
        RagDocumentContext<String> context = new RagDocumentContext<>(
                "This is a test document after processing.",
                List.of(new RagStringContext("This is a test document", new Vector(1d))),
                "doc1"
        );

        assertEquals("rocessing.", context.getDocumentRight(10));
        assertEquals("after processing.", context.getDocumentRight(17));
        assertEquals("This is a test document after processing.", context.getDocumentRight(100));
        assertEquals("", context.getDocumentRight(0));
    }

    @Test
    void testGetDocumentLeft() {
        RagDocumentContext<String> context = new RagDocumentContext<>(
                "This is a test document after processing.",
                List.of(new RagStringContext("This is a test document", new Vector(1d))),
                "doc1"
        );

        assertEquals("This is a ", context.getDocumentLeft(10));
        assertEquals("This is a test document after ", context.getDocumentLeft(30));
        assertEquals("This is a test document after processing.", context.getDocumentLeft(100));
        assertEquals("", context.getDocumentLeft(0));
    }
}