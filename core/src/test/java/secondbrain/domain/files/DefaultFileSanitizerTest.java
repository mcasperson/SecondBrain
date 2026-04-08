package secondbrain.domain.files;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultFileSanitizerTest {
    private final DefaultFileSanitizer sanitizer = new DefaultFileSanitizer();

    @Test
    void testSanitizeFilePath_withForbiddenChars() {
        String input = "invalidfi*le?na<me>|.txt";
        String expected = "invalidfi_le_na_me__.txt";
        assertEquals(expected, sanitizer.sanitizeFilePath(input));
    }

    @Test
    void testSanitizeFilePath_noForbiddenChars() {
        String input = "valid_filename.txt";
        assertEquals(input, sanitizer.sanitizeFilePath(input));
    }

    @Test
    void testSanitizeFilePath_emptyString() {
        assertEquals("", sanitizer.sanitizeFilePath(""));
    }

    @Test
    void testSanitizeFilePath_onlyForbiddenChars() {
        String input = "*?\"<>|";
        String expected = "______";
        assertEquals(expected, sanitizer.sanitizeFilePath(input));
    }

    @Test
    void testSanitizeFilePath_mixed() {
        String input = "abc*d?e\"f<g>h|i";
        String expected = "abc_d_e_f_g_h_i";
        assertEquals(expected, sanitizer.sanitizeFilePath(input));
    }

    @Test
    void testSanitizeFileName_withForbiddenChars() {
        String input = "i\\n/va:lidfi*le?na<me>|.txt";
        String expected = "i_n_va_lidfi_le_na_me__.txt";
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
        String input = ":*?\"<>|\\/";
        String expected = "_________";
        assertEquals(expected, sanitizer.sanitizeFileName(input));
    }

    @Test
    void testSanitizeFileName_mixed() {
        String input = "a:b\\c*d?e\"f<g>h|i/j";
        String expected = "a_b_c_d_e_f_g_h_i_j";
        assertEquals(expected, sanitizer.sanitizeFileName(input));
    }
}

