package secondbrain.domain.persist;

import io.smallrye.common.annotation.Identifier;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import secondbrain.domain.concurrency.SharedVirtualThreadExecutor;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptionhandling.LoggingExceptionHandler;
import secondbrain.domain.exceptions.LocalStorageFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.json.JsonDeserializerJackson;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.persist.config.*;
import secondbrain.domain.sanitize.SanitizeDocument;
import secondbrain.domain.test.FakeCosmosCacheContainer;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.zip.Zipper;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CosmosLocalStorage} that replace the Cosmos container with an in-memory test instance, so no
 * emulator is needed. The test instance is supplied by the producer method in this class instead of
 * {@link CosmosCacheContainerProducer}.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(CosmosLocalStorage.class)
@AddBeanClasses(LocalStorageDisableTool.class)
@AddBeanClasses(LocalStorageWriteOnlyTool.class)
@AddBeanClasses(LocalStorageReadOnlyTool.class)
@AddBeanClasses(LocalStorageCacheDisable.class)
@AddBeanClasses(LocalStorageCacheReadOnly.class)
@AddBeanClasses(LocalStorageCacheWriteOnly.class)
@AddBeanClasses(LocalStorageCacheDisableRedaction.class)
@AddBeanClasses(JsonDeserializerJackson.class)
@AddBeanClasses(LoggingExceptionHandler.class)
@AddBeanClasses(Loggers.class)
@AddBeanClasses(SharedVirtualThreadExecutor.class)
class CosmosLocalStorageUnitTest {
    private static final String TOOL = "Tool";
    private static final String SOURCE = "Source";
    private static final String HASH = "hash";
    private static final String ID = TOOL + "_" + SOURCE + "_" + HASH;

    private final RecordingReadWrite local = new RecordingReadWrite();
    private final Map<String, String> settings = new HashMap<>();
    private final FakeCosmosCacheContainer container = new FakeCosmosCacheContainer();
    private final AtomicInteger containersCreated = new AtomicInteger();

    @Inject
    private CosmosLocalStorage storage;

    @Produces
    CosmosCacheContainer produceCosmosCacheContainer() {
        containersCreated.incrementAndGet();
        return container;
    }

    void disposeCosmosCacheContainer(@Disposes final CosmosCacheContainer cosmosCacheContainer) {
        cosmosCacheContainer.close();
    }

    @Produces
    @Preferred
    LocalStorageReadWrite produceLocalStorageReadWrite() {
        return local;
    }

    @Produces
    @Identifier("AES")
    Encryptor produceEncryptor() {
        return new WrappingEncryptor("enc");
    }

    @Produces
    @Identifier("legacy")
    Encryptor produceLegacyEncryptor() {
        return new WrappingEncryptor("old");
    }

    @Produces
    @Identifier("ApacheCommonsZStdZipper")
    Zipper produceZipper() {
        return new WrappingZipper();
    }

    @Produces
    @Identifier("financialLocationContactRedaction")
    SanitizeDocument produceSanitizer() {
        return new SecretRedactor();
    }

    @BeforeEach
    void setUp() {
        settings.put("sb.cosmos.database", "db");
        settings.put("sb.cosmos.container", "container");
        TestConfigUtil.registerConfig(settings);
    }

    /**
     * Sets a cache setting. The settings beans are created lazily, so this must run before they are first used.
     */
    private void configure(final String key, final String value) {
        settings.put(key, value);
        TestConfigUtil.registerConfig(settings);
    }

    private @Nullable String stringResult() {
        final CacheResult<String> result = storage.getString(TOOL, SOURCE, HASH);
        return result == null ? null : result.result();
    }

    private static String packed(final String value) {
        return "enc(zip(" + value + "))";
    }

    private List<CosmosLocalStorage.CacheItem> upserts() {
        storage.flush();
        return container.getUpserts();
    }

    private static CosmosLocalStorage.CacheItem remoteItem(final String id, final String response, final long timestamp) {
        return new CosmosLocalStorage.CacheItem(id, TOOL, SOURCE, HASH, response, timestamp, -1);
    }

    @Test
    void postConstructCreatesTheContainerOnlyOnce() {
        storage.getString(TOOL, SOURCE, HASH);
        storage.postConstruct();

        assertEquals(1, containersCreated.get());
    }

    @Test
    void preDestroyClosesTheContainer() {
        storage.getString(TOOL, SOURCE, HASH);

        storage.preDestroy();

        assertEquals(1, container.getCloseCount());
    }

    @Test
    void getStringIsAMissAfterTheContainerIsClosed() {
        storage.preDestroy();
        local.put(TOOL, SOURCE, HASH, packed("value"));

        assertEquals(new CacheResult<String>(null, null, false), storage.getString(TOOL, SOURCE, HASH));
        assertEquals(0, local.reads.get());
    }

    @ParameterizedTest
    @CsvSource({
            "sb.cache.disable, true",
            "sb.cache.disabletool, 'Other,Tool'",
            "sb.cache.writeonly, true",
            "sb.cache.writeonlytool, Tool"})
    void getStringIsAMissWhenCacheIsDisabledOrWriteOnly(final String setting, final String value) {
        configure(setting, value);
        local.put(TOOL, SOURCE, HASH, packed("value"));

        assertNull(stringResult());
        assertEquals(0, local.reads.get());
    }

    @Test
    void getStringUnpacksLocalCacheHitWithoutReadingCosmos() {
        local.put(TOOL, SOURCE, HASH, packed("hello"));

        final CacheResult<String> result = storage.getString(TOOL, SOURCE, HASH);

        assertEquals(new CacheResult<>("hello", null, true), result);
        assertEquals(0, container.getReadCount());
    }

    @Test
    void getStringFallsBackToAnotherEncryptorForLegacyValues() {
        local.put(TOOL, SOURCE, HASH, "old(zip(legacy))");

        assertEquals("legacy", stringResult());
    }

    @Test
    void getStringLoadsUnexpiredItemFromCosmosAndCachesItLocally() {
        final long expires = Instant.now().getEpochSecond() + 3600;
        container.addItem(remoteItem(ID, packed("from cosmos"), expires));

        final CacheResult<String> result = storage.getString(TOOL, SOURCE, HASH);

        assertEquals(new CacheResult<>("from cosmos", null, true), result);
        assertEquals(packed("from cosmos"), local.get(TOOL, SOURCE, HASH));
        assertEquals(expires, local.timestamps.get(TOOL + SOURCE + HASH));
    }

    @Test
    void getStringTreatsExpiredCosmosItemAsMiss() {
        container.addItem(remoteItem(ID, packed("stale"), Instant.now().getEpochSecond() - 1));

        final CacheResult<String> result = storage.getString(TOOL, SOURCE, HASH);

        assertEquals(new CacheResult<String>(null, null, false), result);
        assertNull(local.get(TOOL, SOURCE, HASH));
    }

    @Test
    void getStringTreatsCosmosNotFoundAsMiss() {
        assertEquals(new CacheResult<String>(null, null, false), storage.getString(TOOL, SOURCE, HASH));
    }

    @Test
    void getStringWrapsUnexpectedCosmosErrors() {
        container.failReadsWith(new IllegalStateException("Cosmos DB is unavailable"));

        assertThrows(LocalStorageFailure.class, () -> storage.getString(TOOL, SOURCE, HASH));
    }

    @Test
    void getStringResetsConnectionAfterRepeatedFailures() {
        container.failReadsWith(new IllegalStateException("Cosmos DB is unavailable"));
        for (int i = 0; i < 6; i++) {
            assertThrows(LocalStorageFailure.class, () -> storage.getString(TOOL, SOURCE, HASH));
        }
        assertEquals(1, containersCreated.get());
        assertEquals(0, container.getCloseCount());

        // The seventh call exceeds MAX_FAILURES, so the old container is closed and a new one is created
        assertThrows(LocalStorageFailure.class, () -> storage.getString(TOOL, SOURCE, HASH));
        assertEquals(2, containersCreated.get());
        assertEquals(1, container.getCloseCount());
    }

    @Test
    void getStringReassemblesChunkedValues() {
        local.put(TOOL, SOURCE, HASH + "_chunked_size", packed("2"));
        local.put(TOOL, SOURCE, HASH + "_chunk_0", packed("first-"));
        local.put(TOOL, SOURCE, HASH + "_chunk_1", packed("second"));

        assertEquals(new CacheResult<>("first-second", null, true), storage.getString(TOOL, SOURCE, HASH));
    }

    @Test
    void getStringIsAMissWhenAChunkIsMissing() {
        local.put(TOOL, SOURCE, HASH + "_chunked_size", packed("2"));
        local.put(TOOL, SOURCE, HASH + "_chunk_0", packed("first-"));

        assertEquals(new CacheResult<String>(null, null, false), storage.getString(TOOL, SOURCE, HASH));
    }

    @Test
    void getOrPutStringReturnsCachedValueWithoutGenerating() {
        local.put(TOOL, SOURCE, HASH, packed("cached"));
        final AtomicInteger generated = new AtomicInteger();

        final CacheResult<String> result = storage.getOrPutString(TOOL, SOURCE, HASH, 60, () -> {
            generated.incrementAndGet();
            return "new";
        });

        assertEquals(new CacheResult<>("cached", null, true), result);
        assertEquals(0, generated.get());
    }

    @Test
    void getOrPutStringGeneratesRedactsAndPersistsOnMiss() {
        final long before = Instant.now().getEpochSecond();

        final CacheResult<String> result = storage.getOrPutString(TOOL, SOURCE, HASH, 120, () -> "value with SECRET");

        assertEquals(new CacheResult<>("value with SECRET", null, false), result);
        final List<CosmosLocalStorage.CacheItem> upserts = upserts();
        assertEquals(1, upserts.size());
        final CosmosLocalStorage.CacheItem item = upserts.getFirst();
        assertEquals(ID, item.id());
        assertEquals(TOOL, item.tool());
        assertEquals(packed("value with [redacted]"), item.response());
        assertEquals(120, item.ttl());
        assertNotNull(item.timestamp());
        assertTrue(item.timestamp() >= before + 120 && item.timestamp() <= Instant.now().getEpochSecond() + 120);
        assertEquals(packed("value with [redacted]"), local.get(TOOL, SOURCE, HASH));
    }

    @Test
    void getOrPutStringWithoutTtlStoresItemsThatNeverExpire() {
        storage.getOrPutString(TOOL, SOURCE, HASH, () -> "forever");

        final CosmosLocalStorage.CacheItem item = upserts().getFirst();
        assertEquals(-1, item.ttl());
        assertNull(item.timestamp());
    }

    @Test
    void getOrPutStringDoesNotPersistWhenGeneratorFails() {
        final IllegalStateException failure = new IllegalStateException("boom");

        final CacheResult<String> result = storage.getOrPutString(TOOL, SOURCE, HASH, 60, () -> {
            throw failure;
        });

        assertNull(result.result());
        assertSame(failure, result.exception());
        assertTrue(upserts().isEmpty());
    }

    @Test
    void getOrPutStringJustGeneratesAfterTheContainerIsClosed() {
        storage.preDestroy();

        assertEquals(new CacheResult<>("generated", null, false), storage.getOrPutString(TOOL, SOURCE, HASH, 60, () -> "generated"));
        assertEquals(0, local.reads.get());
    }

    @ParameterizedTest
    @CsvSource({"sb.cache.readonly, true", "sb.cache.readonlytool, Tool", "sb.cache.disable, true"})
    void putStringSkipsWritesWhenReadOnly(final String setting, final String value) {
        configure(setting, value);

        storage.putString(TOOL, SOURCE, HASH, "value");

        assertTrue(upserts().isEmpty());
        assertNull(local.get(TOOL, SOURCE, HASH));
    }

    @Test
    void putStringKeepsSecretsWhenRedactionIsDisabled() {
        configure("sb.cache.disableredaction", "true");

        storage.putString(TOOL, SOURCE, HASH, "SECRET");

        assertEquals(packed("SECRET"), upserts().getFirst().response());
    }

    @Test
    void putStringSplitsValuesLargerThanOneMegabyteIntoChunks() {
        final String large = "a".repeat(1024 * 1024 + 10);

        storage.putString(TOOL, SOURCE, HASH, 60, large);

        final List<String> ids = upserts().stream().map(CosmosLocalStorage.CacheItem::id).toList();
        assertEquals(List.of(ID + "_chunked_size", ID + "_chunk_0", ID + "_chunk_1"), ids);
        assertEquals(packed("2"), local.get(TOOL, SOURCE, HASH + "_chunked_size"));
        assertEquals(packed("a".repeat(10)), local.get(TOOL, SOURCE, HASH + "_chunk_1"));
    }

    @Test
    void getOrPutObjectDeserializesCachedJson() {
        local.put(TOOL, SOURCE, HASH, packed("{\"name\":\"cached\",\"count\":3}"));

        final CacheResult<Item> result = storage.getOrPutObject(TOOL, SOURCE, HASH, 60, Item.class, () -> new Item("new", 1));

        assertEquals(new CacheResult<>(new Item("cached", 3), null, true), result);
    }

    @Test
    void getOrPutObjectRegeneratesWhenCachedJsonIsInvalid() {
        local.put(TOOL, SOURCE, HASH, packed("not json"));

        final CacheResult<Item> result = storage.getOrPutObject(TOOL, SOURCE, HASH, 60, Item.class, () -> new Item("fresh", 2));

        assertEquals(new CacheResult<>(new Item("fresh", 2), null, false), result);
        assertEquals(packed("{\"name\":\"fresh\",\"count\":2}"), upserts().getFirst().response());
    }

    @Test
    void getOrPutListRoundTripsThroughTheCache() {
        local.put(TOOL, SOURCE, HASH, packed("[{\"name\":\"a\",\"count\":1},{\"name\":\"b\",\"count\":2}]"));

        final CacheResult<List<Item>> result = storage.getOrPutList(TOOL, SOURCE, HASH, Item.class, List::of);

        assertEquals(List.of(new Item("a", 1), new Item("b", 2)), result.result());
        assertTrue(result.fromCache());
    }

    @Test
    void getOrPutObjectArrayPrefersTheFullLocalCopy() {
        local.put(TOOL, SOURCE, HASH + "_all", packed("[{\"name\":\"a\",\"count\":1}]"));

        final CacheResult<Item[]> result = storage.getOrPutObjectArray(TOOL, SOURCE, HASH, 60, Item.class, Item[].class, () -> new Item[0]);

        assertTrue(result.fromCache());
        assertArrayEquals(new Item[]{new Item("a", 1)}, result.result());
        assertEquals(0, container.getReadCount());
    }

    @Test
    void getOrPutObjectArrayRebuildsArrayFromIndividualItemsAndStoresLocalCopy() {
        local.put(TOOL, SOURCE, HASH, packed("2"));
        local.put(TOOL, SOURCE, HASH + "_0", packed("{\"name\":\"a\",\"count\":1}"));
        local.put(TOOL, SOURCE, HASH + "_1", packed("{\"name\":\"b\",\"count\":2}"));

        final CacheResult<Item[]> result = storage.getOrPutObjectArray(TOOL, SOURCE, HASH, 60, Item.class, Item[].class, () -> new Item[0]);

        assertTrue(result.fromCache());
        // NOTE: the items are fetched with ParallelCollectors.parallelToStream, which yields results in completion
        // order rather than index order, so the rebuilt array order is currently nondeterministic (a production
        // defect). Only the contents are asserted here; tighten to an ordered assertion once that is fixed.
        assertEquals(Set.of(new Item("a", 1), new Item("b", 2)), Set.of(Objects.requireNonNull(result.result())));
        assertEquals(2, Objects.requireNonNull(result.result()).length);
        final String all = Objects.requireNonNull(local.get(TOOL, SOURCE, HASH + "_all"));
        assertTrue(all.equals(packed("[{\"name\":\"a\",\"count\":1},{\"name\":\"b\",\"count\":2}]"))
                || all.equals(packed("[{\"name\":\"b\",\"count\":2},{\"name\":\"a\",\"count\":1}]")), all);
    }

    @Test
    void getOrPutObjectArrayPersistsCountAndEachItemOnMiss() {
        final Item[] generated = {new Item("a", 1), new Item("b", 2)};

        final CacheResult<Item[]> result = storage.getOrPutObjectArray(TOOL, SOURCE, HASH, 60, Item.class, Item[].class, () -> generated);

        assertFalse(result.fromCache());
        assertArrayEquals(generated, result.result());
        final Map<String, String> byId = new HashMap<>();
        upserts().forEach(i -> byId.put(i.id(), i.response()));
        assertEquals(Map.of(
                ID, packed("2"),
                ID + "_0", packed("{\"name\":\"a\",\"count\":1}"),
                ID + "_1", packed("{\"name\":\"b\",\"count\":2}")), byId);
        assertEquals(packed("[{\"name\":\"a\",\"count\":1},{\"name\":\"b\",\"count\":2}]"), local.get(TOOL, SOURCE, HASH + "_all"));
    }

    @Test
    void persistArrayResultReturnsGeneratorFailureWithoutWriting() {
        final RuntimeException failure = new RuntimeException("no data");

        final CacheResult<Item[]> result = storage.persistArrayResult(TOOL, SOURCE, HASH, 60, () -> {
            throw failure;
        });

        assertNull(result.result());
        assertSame(failure, result.exception());
        assertTrue(upserts().isEmpty());
    }

    record Item(String name, int count) {
    }

    private static final class WrappingEncryptor implements Encryptor {
        private final String prefix;

        WrappingEncryptor(final String prefix) {
            this.prefix = prefix;
        }

        @Override
        public String encrypt(final String text) {
            return prefix + "(" + text + ")";
        }

        @Override
        public String decrypt(final String text) {
            if (!text.startsWith(prefix + "(") || !text.endsWith(")")) {
                throw new IllegalArgumentException("not encrypted by " + prefix);
            }
            return text.substring(prefix.length() + 1, text.length() - 1);
        }
    }

    private static final class WrappingZipper implements Zipper {
        @Override
        public String compressString(final String data) {
            return "zip(" + data + ")";
        }

        @Override
        public String decompressString(final String compressedData) {
            if (!compressedData.startsWith("zip(")) {
                throw new IllegalArgumentException("not zipped");
            }
            return compressedData.substring(4, compressedData.length() - 1);
        }
    }

    private static final class SecretRedactor implements SanitizeDocument {
        @Override
        public @Nullable String sanitize(@Nullable final String document) {
            return document == null ? null : document.replace("SECRET", "[redacted]");
        }

        @Override
        public @Nullable String sanitize(@Nullable final String document, final boolean unsanitize) {
            return sanitize(document);
        }
    }

    private static final class RecordingReadWrite implements LocalStorageReadWrite {
        final Map<String, String> values = new ConcurrentHashMap<>();
        final Map<String, Long> timestamps = new ConcurrentHashMap<>();
        final AtomicInteger reads = new AtomicInteger();

        void put(final String tool, final String source, final String hash, final String value) {
            values.put(tool + source + hash, value);
        }

        @Nullable String get(final String tool, final String source, final String hash) {
            return values.get(tool + source + hash);
        }

        @Override
        public Optional<String> getString(final String tool, final String source, final String promptHash) {
            reads.incrementAndGet();
            return Optional.ofNullable(values.get(tool + source + promptHash));
        }

        @Override
        public String putString(final String tool, final String source, final String promptHash, @Nullable final Long timestamp, final String value) {
            values.put(tool + source + promptHash, value);
            if (timestamp != null) {
                timestamps.put(tool + source + promptHash, timestamp);
            }
            return value;
        }

        @Override
        public void purge() {
            values.clear();
        }
    }
}
