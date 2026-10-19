package secondbrain.domain.tools.googledocs.model;

import org.junit.jupiter.api.Test;
import secondbrain.domain.data.IdData;
import secondbrain.domain.data.TextData;
import secondbrain.domain.data.UrlData;

import static org.junit.jupiter.api.Assertions.*;

class GoogleDocTest {
	private final GoogleDoc doc = new GoogleDoc(
			"doc-123",
			"Quarterly Plan",
			"https://docs.google.com/document/d/doc-123",
			"The plan text");

	@Test
	void generateIdReturnsTheId() {
		assertEquals("doc-123", doc.generateId());
	}

	@Test
	void generateTextReturnsTheText() {
		assertEquals("The plan text", doc.generateText());
	}

	@Test
	void generateLinkTextReturnsTheLinkText() {
		assertEquals("Quarterly Plan", doc.generateLinkText());
	}

	@Test
	void generateUrlReturnsTheUrl() {
		assertEquals("https://docs.google.com/document/d/doc-123", doc.generateUrl());
	}

	@Test
	void eachFieldIsReturnedByItsOwnGenerator() {
		final GoogleDoc distinct = new GoogleDoc("a", "b", "c", "d");

		assertEquals("a", distinct.generateId());
		assertEquals("b", distinct.generateLinkText());
		assertEquals("c", distinct.generateUrl());
		assertEquals("d", distinct.generateText());
	}

	@Test
	void isUsableThroughTheDataInterfaces() {
		final TextData text = doc;
		final IdData id = doc;
		final UrlData url = doc;

		assertEquals("The plan text", text.generateText());
		assertEquals("doc-123", id.generateId());
		assertEquals("Quarterly Plan", url.generateLinkText());
		assertEquals("https://docs.google.com/document/d/doc-123", url.generateUrl());
	}

	@Test
	void recordsWithTheSameValuesAreEqual() {
		final GoogleDoc same = new GoogleDoc(
				"doc-123",
				"Quarterly Plan",
				"https://docs.google.com/document/d/doc-123",
				"The plan text");

		assertEquals(doc, same);
		assertEquals(doc.hashCode(), same.hashCode());
		assertNotEquals(doc, new GoogleDoc("doc-124", "Quarterly Plan", "https://docs.google.com/document/d/doc-123", "The plan text"));
	}
}
