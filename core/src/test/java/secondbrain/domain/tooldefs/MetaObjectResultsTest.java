package secondbrain.domain.tooldefs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("NullAway")
class MetaObjectResultsTest {

    @Test
    @DisplayName("Default constructor creates empty results with empty filename and id")
    void testDefaultConstructor() {
        MetaObjectResults results = new MetaObjectResults();

        assertTrue(results.isEmpty());
        assertEquals("", results.getFilename());
        assertEquals("", results.getId());
    }

    @Test
    @DisplayName("Constructor with single result adds the result")
    void testSingleResultConstructor() {
        MetaObjectResult result = new MetaObjectResult("testName", "testValue");
        MetaObjectResults results = new MetaObjectResults(result);

        assertEquals(1, results.size());
        assertTrue(results.contains(result));
        assertEquals("", results.getFilename());
        assertEquals("", results.getId());
    }

    @Test
    @DisplayName("Constructor with single null result creates empty list")
    void testSingleNullResultConstructor() {
        MetaObjectResults results = new MetaObjectResults((MetaObjectResult) null);

        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("Constructor with iterable adds all results")
    void testIterableConstructor() {
        List<MetaObjectResult> resultList = Arrays.asList(
                new MetaObjectResult("name1", "value1"),
                new MetaObjectResult("name2", "value2")
        );
        MetaObjectResults results = new MetaObjectResults(resultList);

        assertEquals(2, results.size());
        assertEquals("", results.getFilename());
        assertEquals("", results.getId());
    }

    @Test
    @DisplayName("Constructor with iterable, filename and id sets all fields")
    void testFullConstructor() {
        List<MetaObjectResult> resultList = Arrays.asList(
                new MetaObjectResult("name1", "value1"),
                new MetaObjectResult("name2", "value2")
        );
        MetaObjectResults results = new MetaObjectResults(resultList, "test.txt", "123");

        assertEquals(2, results.size());
        assertEquals("test.txt", results.getFilename());
        assertEquals("123", results.getId());
    }

    @Test
    @DisplayName("Constructor with null iterable creates empty list")
    void testNullIterableConstructor() {
        MetaObjectResults results = new MetaObjectResults(null, "test.txt", "123");

        assertTrue(results.isEmpty());
        assertEquals("test.txt", results.getFilename());
        assertEquals("123", results.getId());
    }

    @Test
    @DisplayName("hasName returns true when name exists")
    void testHasNameExists() {
        MetaObjectResults results = new MetaObjectResults();
        results.add(new MetaObjectResult("testName", "testValue"));

        assertTrue(results.hasName("testName"));
    }

    @Test
    @DisplayName("hasName returns false when name does not exist")
    void testHasNameDoesNotExist() {
        MetaObjectResults results = new MetaObjectResults();
        results.add(new MetaObjectResult("testName", "testValue"));

        assertFalse(results.hasName("otherName"));
    }

    @Test
    @DisplayName("hasName returns false for blank name")
    void testHasNameBlank() {
        MetaObjectResults results = new MetaObjectResults();
        results.add(new MetaObjectResult("testName", "testValue"));

        assertFalse(results.hasName(""));
        assertFalse(results.hasName("   "));
        assertFalse(results.hasName(null));
    }

    @Test
    @DisplayName("getByName returns result when name exists")
    void testGetByNameExists() {
        MetaObjectResult result = new MetaObjectResult("testName", "testValue");
        MetaObjectResults results = new MetaObjectResults();
        results.add(result);

        Optional<MetaObjectResult> found = results.getByName("testName");

        assertTrue(found.isPresent());
        assertEquals(result, found.get());
    }

    @Test
    @DisplayName("getByName returns empty when name does not exist")
    void testGetByNameDoesNotExist() {
        MetaObjectResults results = new MetaObjectResults();
        results.add(new MetaObjectResult("testName", "testValue"));

        Optional<MetaObjectResult> found = results.getByName("otherName");

        assertFalse(found.isPresent());
    }

    @Test
    @DisplayName("getByName returns empty for blank name")
    void testGetByNameBlank() {
        MetaObjectResults results = new MetaObjectResults();
        results.add(new MetaObjectResult("testName", "testValue"));

        assertFalse(results.getByName("").isPresent());
        assertFalse(results.getByName("   ").isPresent());
        assertFalse(results.getByName(null).isPresent());
    }

    @Test
    @DisplayName("getIntValueByName returns integer value when exists")
    void testGetIntValueByNameExists() {
        MetaObjectResults results = new MetaObjectResults();
        results.add(new MetaObjectResult("count", "42"));

        Optional<Integer> value = results.getIntValueByName("count");

        assertTrue(value.isPresent());
        assertEquals(42, value.get());
    }

    @Test
    @DisplayName("getIntValueByName returns zero for non-numeric value")
    void testGetIntValueByNameNonNumeric() {
        MetaObjectResults results = new MetaObjectResults();
        results.add(new MetaObjectResult("count", "not a number"));

        Optional<Integer> value = results.getIntValueByName("count");

        assertTrue(value.isPresent());
        assertEquals(0, value.get());
    }

    @Test
    @DisplayName("getIntValueByName returns empty when name does not exist")
    void testGetIntValueByNameDoesNotExist() {
        MetaObjectResults results = new MetaObjectResults();

        Optional<Integer> value = results.getIntValueByName("count");

        assertFalse(value.isPresent());
    }

    @Test
    @DisplayName("getIntValueByName returns empty for blank name")
    void testGetIntValueByNameBlank() {
        MetaObjectResults results = new MetaObjectResults();
        results.add(new MetaObjectResult("count", "42"));

        assertFalse(results.getIntValueByName("").isPresent());
        assertFalse(results.getIntValueByName(null).isPresent());
    }

    @Test
    @DisplayName("getIntValueByName with default returns value when exists")
    void testGetIntValueByNameWithDefaultExists() {
        MetaObjectResults results = new MetaObjectResults();
        results.add(new MetaObjectResult("count", "42"));

        int value = results.getIntValueByName("count", 10);

        assertEquals(42, value);
    }

    @Test
    @DisplayName("getIntValueByName with default returns default when name does not exist")
    void testGetIntValueByNameWithDefaultDoesNotExist() {
        MetaObjectResults results = new MetaObjectResults();

        int value = results.getIntValueByName("count", 10);

        assertEquals(10, value);
    }

    @Test
    @DisplayName("getIntValueByName with default returns default for blank name")
    void testGetIntValueByNameWithDefaultBlank() {
        MetaObjectResults results = new MetaObjectResults();
        results.add(new MetaObjectResult("count", "42"));

        assertEquals(10, results.getIntValueByName("", 10));
        assertEquals(10, results.getIntValueByName(null, 10));
    }

    @Test
    @DisplayName("getStringValueByName returns string value when exists")
    void testGetStringValueByNameExists() {
        MetaObjectResults results = new MetaObjectResults();
        results.add(new MetaObjectResult("name", "testValue"));

        Optional<String> value = results.getStringValueByName("name");

        assertTrue(value.isPresent());
        assertEquals("testValue", value.get());
    }

    @Test
    @DisplayName("getStringValueByName converts integer to string")
    void testGetStringValueByNameInteger() {
        MetaObjectResults results = new MetaObjectResults();
        results.add(new MetaObjectResult("count", 42));

        Optional<String> value = results.getStringValueByName("count");

        assertTrue(value.isPresent());
        assertEquals("42", value.get());
    }

    @Test
    @DisplayName("getStringValueByName returns empty when name does not exist")
    void testGetStringValueByNameDoesNotExist() {
        MetaObjectResults results = new MetaObjectResults();

        Optional<String> value = results.getStringValueByName("name");

        assertFalse(value.isPresent());
    }

    @Test
    @DisplayName("getStringValueByName returns empty for blank name")
    void testGetStringValueByNameBlank() {
        MetaObjectResults results = new MetaObjectResults();
        results.add(new MetaObjectResult("name", "testValue"));

        assertFalse(results.getStringValueByName("").isPresent());
        assertFalse(results.getStringValueByName(null).isPresent());
    }

    @Test
    @DisplayName("Multiple results with same name returns first match")
    void testMultipleResultsWithSameName() {
        MetaObjectResults results = new MetaObjectResults();
        results.add(new MetaObjectResult("duplicate", "first"));
        results.add(new MetaObjectResult("duplicate", "second"));

        Optional<String> value = results.getStringValueByName("duplicate");

        assertTrue(value.isPresent());
        assertEquals("first", value.get());
    }

    @Test
    @DisplayName("getFilename and getId return correct values")
    void testGetFilenameAndId() {
        MetaObjectResults results = new MetaObjectResults(
                Arrays.asList(new MetaObjectResult("test", "value")),
                "document.pdf",
                "doc-123"
        );

        assertEquals("document.pdf", results.getFilename());
        assertEquals("doc-123", results.getId());
    }
}

