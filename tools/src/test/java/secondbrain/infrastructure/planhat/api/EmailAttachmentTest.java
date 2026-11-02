package secondbrain.infrastructure.planhat.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmailAttachmentTest {
	@Test
	void gettersReturnTheSuppliedValues() {
		final EmailAttachment attachment = new EmailAttachment("a1", "log.txt", "text/plain", 12);

		assertEquals("a1", attachment.getId());
		assertEquals("log.txt", attachment.getFileName());
		assertEquals("text/plain", attachment.getMimeType());
		assertEquals(12, attachment.getSize());
	}

	@Test
	void gettersReturnDefaultsWhenValuesAreNull() {
		final EmailAttachment attachment = new EmailAttachment(null, null, null, null);

		assertEquals("", attachment.getId());
		assertEquals("", attachment.getFileName());
		assertEquals("", attachment.getMimeType());
		assertEquals(0, attachment.getSize());
	}

	@Test
	void gettersKeepEmptyAndZeroValues() {
		final EmailAttachment attachment = new EmailAttachment("", "", "", 0);

		assertEquals("", attachment.getId());
		assertEquals("", attachment.getFileName());
		assertEquals("", attachment.getMimeType());
		assertEquals(0, attachment.getSize());
	}

	@Test
	void getSizeKeepsANegativeValue() {
		assertEquals(-1, new EmailAttachment("a1", "log.txt", "text/plain", -1).getSize());
	}
}
