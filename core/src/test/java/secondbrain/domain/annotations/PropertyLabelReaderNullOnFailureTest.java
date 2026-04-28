package secondbrain.domain.annotations;

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
@AddBeanClasses(PropertyLabelReaderNullOnFailure.class)
class PropertyLabelReaderNullOnFailureTest {

	@Inject
    private PropertyLabelReaderNullOnFailure reader;

	@Test
    void getValues_withNull() {
        List<PropertyLabelDescriptionValue> result = reader.getValues(null);
        assertTrue(result.isEmpty());
       }

	@Test
    void getValues_withAnnotatedProperties() {
        AnnotatedRecord obj = new AnnotatedRecord("value", "description");
        List<PropertyLabelDescriptionValue> result = reader.getValues(obj);

        assertEquals(1, result.size());
        assertEquals("Field Value", result.get(0).description());
        assertEquals("value", result.get(0).value());
       }

	@Test
    void getValues_filtersOutComponentsWithoutPropertyLabel() {
        RecordWithUnlabeledField obj = new RecordWithUnlabeledField("value", "description");
        List<PropertyLabelDescriptionValue> result = reader.getValues(obj);

        assertEquals(1, result.size());
        assertEquals("Annotated Field", result.get(0).description());
        assertEquals("value", result.get(0).value());
       }

	@Test
    void getValues_filtersOutNullValues() {
        RecordWithNullValue obj = new RecordWithNullValue("present", null);
        List<PropertyLabelDescriptionValue> result = reader.getValues(obj);

        assertEquals(1, result.size());
        assertEquals("Present Field", result.get(0).description());
        assertEquals("present", result.get(0).value());
       }

	@Test
    void getValues_withRecordThrowingException() {
           // Record accessors always read from the backing field directly, so overriding
           // getOther() has no effect. The Try.of() doesn't catch anything here. So both "value"
           // and "other" are included in the result.
        RecordWithCustomAccessor obj = new RecordWithCustomAccessor("value", "other");
        List<PropertyLabelDescriptionValue> result = reader.getValues(obj);

           // Both properties are included since record accessors bypass overridden getters
        assertEquals(2, result.size());
           // Verify "value" is included
        assertTrue(result.stream().anyMatch(v -> "value".equals(v.description())));
           // Verify "other" is also included (accessor override has no effect on records)
        assertTrue(result.stream().anyMatch(v -> "other".equals(v.description())));
     }

	@Test
    void getValues_withMultipleAnnotatedProperties() {
        MultipleAnnotatedProperties obj = new MultipleAnnotatedProperties("name", 42, "email");
        List<PropertyLabelDescriptionValue> result = reader.getValues(obj);

        assertEquals(3, result.size());

        assertEquals("name", result.stream().filter(v -> "Name".equals(v.description())).findFirst().orElseThrow().value());
        assertEquals(42, result.stream().filter(v -> "Age".equals(v.description())).findFirst().orElseThrow().value());
        assertEquals("email", result.stream().filter(v -> "Email".equals(v.description())).findFirst().orElseThrow().value());
     }

	@Test
    void getValues_withNoAnnotations() {
        PlainRecord obj = new PlainRecord("value");
        List<PropertyLabelDescriptionValue> result = reader.getValues(obj);

        assertTrue(result.isEmpty());
     }

	@Test
    void getValues_withAnnotatedGetter() {
        NonRecordObject obj = new NonRecordObject("value");
        List<PropertyLabelDescriptionValue> result = reader.getValues(obj);

        assertEquals(1, result.size());
        assertEquals("Field", result.get(0).description());
        assertEquals("value", result.get(0).value());
     }

	@Test
    void getValues_withAnnotatedField() {
        NonRecordObjectWithField obj = new NonRecordObjectWithField("value");
        List<PropertyLabelDescriptionValue> result = reader.getValues(obj);

        assertEquals(1, result.size());
        assertEquals("Field Label", result.get(0).description());
        assertEquals("value", result.get(0).value());
     }

	@Test
    void getValues_withAnnotatedGetterAndField() {
        NonRecordObjectWithBoth obj = new NonRecordObjectWithBoth("getterValue", "fieldValue");
        List<PropertyLabelDescriptionValue> result = reader.getValues(obj);

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(v -> "Getter Label".equals(v.description()) && "getterValue".equals(v.value())));
        assertTrue(result.stream().anyMatch(v -> "Field Label".equals(v.description()) && "fieldValue".equals(v.value())));
     }

	@Test
    void getValues_withIsGetter() {
        ObjectWithIsGetter obj = new ObjectWithIsGetter(true);
        List<PropertyLabelDescriptionValue> result = reader.getValues(obj);

        assertEquals(1, result.size());
        assertEquals("Active", result.get(0).description());
        assertEquals(true, result.get(0).value());
     }

	@Test
    void getValues_withAnnotatedSetterIgnored() {
        NonRecordObjectWithSetter obj = new NonRecordObjectWithSetter("value");
        List<PropertyLabelDescriptionValue> result = reader.getValues(obj);

        // Sets are not read, so empty result
        assertTrue(result.isEmpty());
     }

	// Test helper records
    record AnnotatedRecord(
              @PropertyLabel(description = "Field Value") String value,
            String unlabeled
       ) { }

    record RecordWithUnlabeledField(
              @PropertyLabel(description = "Annotated Field") String value,
            String unlabeled
       ) { }

    record RecordWithNullValue(
              @PropertyLabel(description = "Present Field") String present,
              @PropertyLabel(description = "Null Field") String nullField
       ) { }

      // Record accessors always read from the backing field directly, so overriding
      // getOther() has no effect. The Try.of() in the implementation doesn't catch anything here.
    record RecordWithCustomAccessor(
              @PropertyLabel(description = "value") String value,
              @PropertyLabel(description = "other") String other
       ) {
          public String getOther() {
              throw new RuntimeException("Intentional failure");
           }
     }

    record MultipleAnnotatedProperties(
              @PropertyLabel(description = "Name") String name,
              @PropertyLabel(description = "Age") int age,
              @PropertyLabel(description = "Email") String email
       ) { }

    record PlainRecord(String value) { }

    static class NonRecordObject {
        private final String value;

        NonRecordObject(String value) {
            this.value = value;
         }

         @PropertyLabel(description = "Field")
        public String getField() {
            return value;
         }
     }

    static class NonRecordObjectWithField {
         @PropertyLabel(description = "Field Label")
        private final String value;

        NonRecordObjectWithField(String value) {
            this.value = value;
         }

        public String getValue() {
            return value;
         }
     }

    static class NonRecordObjectWithBoth {
         @PropertyLabel(description = "Field Label")
        private final String fieldValue;

         @PropertyLabel(description = "Getter Label")
        private final String getterValue;

        NonRecordObjectWithBoth(String getterValue, String fieldValue) {
            this.getterValue = getterValue;
            this.fieldValue = fieldValue;
         }

        public String getGetterValue() {
            return getterValue;
         }

        public String getFieldValue() {
            return fieldValue;
         }
     }

    static class ObjectWithIsGetter {
        private final boolean active;

        ObjectWithIsGetter(boolean active) {
            this.active = active;
         }

         @PropertyLabel(description = "Active")
        public boolean isActive() {
            return active;
         }
     }

    static class NonRecordObjectWithSetter {
        private String value;

        NonRecordObjectWithSetter(String value) {
            this.value = value;
         }

        public String getValue() {
            return value;
         }

         @PropertyLabel(description = "Setter Label")
        public void setValue(String value) {
            this.value = value;
         }
     }
}
