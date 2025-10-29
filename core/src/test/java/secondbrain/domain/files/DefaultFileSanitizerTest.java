package secondbrain.domain.files;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultFileSanitizerTest {
    private final DefaultFileSanitizer sanitizer = new DefaultFileSanitizer();

    @Test
    void testSanitizeFileName_withForbiddenChars() {
        String input = "inva:lidfi*le?na<me>|.txt";
        String expected = "inva_lidfi_le_na_me__.txt";
        assertEquals(expected, sanitizer.sanitizeFileName(input));
    }

    @Test
    void testSanitizeFileName_noForbiddenChars() {
        String input = "valid_filename.txt";
        assertEquals(input, sanitizer.sanitizeFileName(input));
    }

    @Test
    void testSanitizeFileName_emptyString() {
        assertEquals("", sanitizer.sanitizeFileName(""));
    }

    @Test
    void testSanitizeFileName_onlyForbiddenChars() {
        String input = ":*?\"<>|";
        String expected = "_______";
        assertEquals(expected, sanitizer.sanitizeFileName(input));
    }

    @Test
    void testSanitizeFileName_mixed() {
        String input = "a:bc*d?e\"f<g>h|i";
        String expected = "a_bc_d_e_f_g_h_i";
        assertEquals(expected, sanitizer.sanitizeFileName(input));
    }
}

