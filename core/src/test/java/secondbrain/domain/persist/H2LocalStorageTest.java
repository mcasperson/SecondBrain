package secondbrain.domain.persist;

import org.junit.Test;

public class H2LocalStorageTest {

    @Test
    public void testConnection() {
        final H2LocalStorage h2LocalStorage = new H2LocalStorage();
        h2LocalStorage.getString("tool", "source", "promptHash");
    }
}
