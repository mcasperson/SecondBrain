package secondbrain.domain.yaml;

import org.junit.jupiter.api.Test;
import secondbrain.domain.exceptions.DeserializationFailed;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class YamlDeserializerJacksonTest {

    private final YamlDeserializerJackson deserializer = new YamlDeserializerJackson();

    @Test
    void deserializeMapsYamlFieldsToRecord() {
        final Person person = deserializer.deserialize("""
                name: Alice
                age: 42
                tags:
                  - admin
                  - user
                """, Person.class);

        assertEquals(new Person("Alice", 42, List.of("admin", "user")), person);
    }

    @Test
    void deserializeUsesRegisteredJavaTimeModule() {
        final Event event = deserializer.deserialize("date: 2024-02-29", Event.class);

        assertEquals(LocalDate.of(2024, 2, 29), event.date());
    }

    @Test
    void deserializeThrowsDeserializationFailedForMalformedYaml() {
        final DeserializationFailed exception = assertThrows(DeserializationFailed.class,
                () -> deserializer.deserialize("name: [unclosed", Person.class));

        assertNotNull(exception.getCause());
    }

    @Test
    void deserializeThrowsDeserializationFailedForTypeMismatch() {
        assertThrows(DeserializationFailed.class,
                () -> deserializer.deserialize("name: Bob\nage: not-a-number", Person.class));
    }

    record Person(String name, int age, List<String> tags) {
    }

    record Event(LocalDate date) {
    }
}
