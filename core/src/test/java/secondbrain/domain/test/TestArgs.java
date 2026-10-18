package secondbrain.domain.test;

import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.objects.ToStringGenerator;

import java.util.List;

/**
 * Small deterministic collaborators for tests that wire beans with CDI producer methods.
 */
public final class TestArgs {
    private TestArgs() {
    }

    /**
     * Encrypts by wrapping the text in "enc(...)" and only decrypts text in that format.
     */
    public static Encryptor encryptor() {
        return new Encryptor() {
            @Override
            public String encrypt(final String text) {
                return "enc(" + text + ")";
            }

            @Override
            public String decrypt(final String text) {
                if (text == null || !text.startsWith("enc(") || !text.endsWith(")")) {
                    throw new IllegalArgumentException("Not encrypted");
                }
                return text.substring(4, text.length() - 1);
            }
        };
    }

    public static ToStringGenerator toStringGenerator(final String value, final int hash) {
        return new ToStringGenerator() {
            @Override
            public String generateGetterConfig(final Object obj) {
                return value;
            }

            @Override
            public String generateGetterConfig(final Object obj, final List<String> excludeFields) {
                return value + excludeFields;
            }

            @Override
            public int generateHashGetterConfig(final Object obj) {
                return hash;
            }

            @Override
            public int generateHashGetterConfig(final Object obj, final List<String> excludeFields) {
                return hash;
            }
        };
    }
}
