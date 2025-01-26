package secondbrain.domain.persist;

import io.vavr.control.Try;
import org.junit.Assert;
import org.junit.Test;

import java.util.UUID;

public class H2LocalStorageTest {

    @Test
    public void testConnection() {
        final H2LocalStorage h2LocalStorage = new H2LocalStorage();
        Assert.assertNull(h2LocalStorage.getString(
                H2LocalStorageTest.class.getSimpleName(),
                "test",
                UUID.randomUUID().toString()));
    }

    @Test
    public void testSave() {
        final H2LocalStorage h2LocalStorage = new H2LocalStorage();
        final String randomValue = UUID.randomUUID().toString();
        Assert.assertEquals(randomValue, h2LocalStorage.getOrPutString(
                H2LocalStorageTest.class.getSimpleName(),
                "test",
                randomValue,
                () -> randomValue));
        Assert.assertEquals(randomValue, h2LocalStorage.getString(
                H2LocalStorageTest.class.getSimpleName(),
                "test",
                randomValue));
    }

    @Test
    public void testTTL() {
        final H2LocalStorage h2LocalStorage = new H2LocalStorage();
        final String randomValue = UUID.randomUUID().toString();
        Assert.assertEquals(randomValue, h2LocalStorage.getOrPutString(
                H2LocalStorageTest.class.getSimpleName(),
                "test",
                randomValue,
                10,
                () -> randomValue));
        Assert.assertEquals(randomValue, h2LocalStorage.getString(
                H2LocalStorageTest.class.getSimpleName(),
                "test",
                randomValue));
        Try.run(() -> Thread.sleep(15000));
        Assert.assertNull(h2LocalStorage.getString(
                H2LocalStorageTest.class.getSimpleName(),
                "test",
                randomValue));
    }
}
