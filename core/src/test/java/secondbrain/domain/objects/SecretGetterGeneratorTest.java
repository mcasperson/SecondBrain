package secondbrain.domain.objects;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("NullAway")
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(SecretGetterGenerator.class)
class SecretGetterGeneratorTest {

    @Inject
    private SecretGetterGenerator generator;

    @Test
    void generateGetterConfig_withNull() {
        String result = generator.generateGetterConfig(null);
        assertEquals("", result);
    }

    @Test
    void generateGetterConfig_withSimpleObject() {
        TestObject obj = new TestObject("John", 30, "secret123");
        String result = generator.generateGetterConfig(obj);
        String result2 = generator.generateGetterConfig(obj);

        assertTrue(result.contains("getName = John"));
        assertTrue(result.contains("getAge = 30"));
        assertFalse(result.contains("secret123"));
        assertFalse(result.contains("getSecret"));
        assertEquals(result, result2);
    }

    @Test
    void generateGetterConfig_excludesSecretGetters() {
        TestObject obj = new TestObject("Jane", 25, "password");
        String result = generator.generateGetterConfig(obj);

        assertFalse(result.contains("getSecretPassword"));
        assertFalse(result.contains("password"));
    }

    @Test
    void generateGetterConfig_excludesSensitiveGetters() {
        TestObject obj = new TestObject("Bob", 40, "sensitive");
        String result = generator.generateGetterConfig(obj);

        assertFalse(result.contains("getSensitiveData"));
        assertFalse(result.contains("sensitive-data"));
    }

    @Test
    void generateGetterConfig_excludesGetClass() {
        TestObject obj = new TestObject("Alice", 35, "key");
        String result = generator.generateGetterConfig(obj);

        assertFalse(result.contains("getClass"));
    }

    @Test
    void generateGetterConfig_onlyIncludesParameterlessGetters() {
        TestObject obj = new TestObject("Charlie", 50, "token");
        String result = generator.generateGetterConfig(obj);

        // getValueWithParam(String) should not be included
        assertFalse(result.contains("getValueWithParam"));
    }

    @Test
    void generateGetterConfig_withEmptyObject() {
        EmptyObject obj = new EmptyObject();
        String result = generator.generateGetterConfig(obj);

        // Should return empty or minimal output (no custom getters)
        assertNotNull(result);
    }

    @Test
    void generateGetterConfig_withGetterThrowingException() {
        ThrowingObject obj = new ThrowingObject();
        String result = generator.generateGetterConfig(obj);

        // Should handle exceptions gracefully and not include the throwing getter
        assertFalse(result.contains("getThrowingValue"));
    }

    @Test
    void generateGetterConfig_sortsGettersAlphabetically() {
        TestObject obj = new TestObject("Test", 1, "secret");
        String result = generator.generateGetterConfig(obj);

        int ageIndex = result.indexOf("getAge");
        int nameIndex = result.indexOf("getName");

        // "getAge" should come before "getName" alphabetically
        assertTrue(ageIndex < nameIndex);
    }

    @Test
    void generateHashGetterConfig_withNull() {
        int result = generator.generateHashGetterConfig(null);
        assertEquals(0, result);
    }

    @Test
    void generateHashGetterConfig_withSimpleObject() {
        TestObject obj1 = new TestObject("John", 30, "secret123");
        TestObject obj2 = new TestObject("John", 30, "secret456");
        TestObject obj3 = new TestObject("Jane", 30, "secret123");

        int hash1 = generator.generateHashGetterConfig(obj1);
        int hash2 = generator.generateHashGetterConfig(obj2);
        int hash3 = generator.generateHashGetterConfig(obj3);

        assertEquals(hash1, hash2, "Hash should be same as secrets are ignored");
        assertNotEquals(hash1, hash3, "Hash should be different for different names");
    }

    @Test
    void generateHashGetterConfig_withExclusions() {
        TestObject obj1 = new TestObject("John", 30, "secret123");
        TestObject obj2 = new TestObject("Jane", 30, "secret123");

        int hash1 = generator.generateHashGetterConfig(obj1, List.of("getName"));
        int hash2 = generator.generateHashGetterConfig(obj2, List.of("getName"));

        assertEquals(hash1, hash2, "Hash should be same if name is excluded");
    }

    // Test helper classes
    record TestObject(String name, int age, String secretPassword) {

        public int getAge() {
            return age;
        }

        public String getName() {
            return name;
        }

        public String getSecretPassword() {
            return secretPassword;
        }

        public String getSensitiveData() {
            return "sensitive-data";
        }

        public String getValueWithParam(String param) {
            return "value-" + param;
        }

        public void getVoidMethod() {
            // This should be excluded (returns void)
        }
    }

    static class EmptyObject {
        // No custom getters
    }

    static class ThrowingObject {
        public String getThrowingValue() {
            throw new RuntimeException("This getter always throws");
        }
    }
}

