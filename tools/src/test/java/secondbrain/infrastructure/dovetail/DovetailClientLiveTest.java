package secondbrain.infrastructure.dovetail;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.apache.commons.codec.digest.DigestUtils;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import secondbrain.domain.exceptions.InvalidResponse;
import secondbrain.domain.httpclient.HttpClientCaller;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.mutex.Mutex;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.response.OkResponseValidation;
import secondbrain.domain.test.DirectHttpClientCaller;
import secondbrain.domain.test.FakeJaxRs;
import secondbrain.domain.test.FixedClientConstructor;
import secondbrain.domain.test.RecordingLocalStorage;
import secondbrain.domain.test.RecordingMutex;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.web.ClientConstructor;
import secondbrain.infrastructure.dovetail.api.*;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;
import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(DovetailClientLive.class)
@AddBeanClasses(OkResponseValidation.class)
@AddBeanClasses(Loggers.class)
// The Dovetail API records are built with null fields to match real responses
@SuppressWarnings("NullAway")
class DovetailClientLiveTest {
    private static final String URL = "https://dovetail.test";
    private static final String KEY = "dt-key";
    private static final String FROM = "2024-01-01T00:00:00Z";
    private static final String TO = "2024-02-01T00:00:00Z";
    private static final long LIST_TTL = 60L * 60 * 24;
    private static final long TTL = 60L * 60 * 24 * 365;
    private static final String ITEMS_SOURCE = "DovetailAPIDataItemsV1";
    private static final String PAGE_SOURCE = "DovetailAPIDataListPageV1";
    private static final String MARKDOWN_SOURCE = "DovetailAPIMarkdownExportV2";
    private static final String TOOL = "DovetailClientLive";

    private final DirectHttpClientCaller httpCaller = new DirectHttpClientCaller();
    private final RecordingLocalStorage localStorage = new RecordingLocalStorage();
    private final RecordingMutex mutex = new RecordingMutex();
    private final FakeJaxRs http = new FakeJaxRs();
    private final Map<String, String> settings = new HashMap<>();

    @Inject
    @Preferred
    private DovetailClientLive client;

    @Produces
    @Preferred
    LocalStorage produceLocalStorage() {
        return localStorage;
    }

    @Produces
    @Preferred
    Mutex produceMutex() {
        return mutex;
    }

    @Produces
    HttpClientCaller produceHttpClientCaller() {
        return httpCaller;
    }

    @Produces
    ClientConstructor produceClientConstructor() {
        return new FixedClientConstructor(http.client);
    }

    @BeforeEach
    void registerConfig() {
        settings.put("sb.dovetail.url", URL);
        TestConfigUtil.registerConfig(settings);
    }

    private static DovetailDataItem item(final String id) {
        return new DovetailDataItem(id, "note", "Title " + id, new DovetailDataProject("p1", "Project"), FROM, false, null);
    }

    private static DovetailDataList page(final boolean hasMore, final String nextCursor, final DovetailDataItem... items) {
        return new DovetailDataList(Arrays.asList(items), new DovetailPage(items.length, hasMore, nextCursor));
    }

    private static List<String> ids(final List<DovetailDataItem> items) {
        return items.stream().map(DovetailDataItem::id).toList();
    }

    private static DovetailDataExportResponse export(final String markdown) {
        return new DovetailDataExportResponse(new DovetailDataExport("d1", "note", "Title", FROM, false, markdown));
    }

    @Test
    void getDataItemsFollowsTheCursorUntilThereAreNoMorePages() {
        http.enqueue(FakeJaxRs.response(200, page(true, "cursor-2", item("a"), item("b"))))
                .enqueue(FakeJaxRs.response(200, page(false, null, item("c"))));

        final List<DovetailDataItem> result = client.getDataItems(KEY, FROM, TO);

        assertEquals(List.of("a", "b", "c"), ids(result));
        assertEquals(List.of(URL + "/api/v1/data", URL + "/api/v1/data"), http.targets());
        assertEquals(List.of(FROM, FROM), http.queryParams("filter[created_at][gte]"));
        assertEquals(List.of(TO, TO), http.queryParams("filter[created_at][lte]"));
        assertEquals(List.of("cursor-2"), http.queryParams("page[start_cursor]"));
        assertEquals(List.of("Bearer " + KEY, "Bearer " + KEY), http.headers("Authorization"));
        assertEquals(List.of("sb_dovetail.lock", "sb_dovetail.lock"), mutex.lockNames);
    }

    @Test
    void getDataItemsCachesTheListAndEachPage() {
        http.enqueue(FakeJaxRs.response(200, page(true, "cursor-2", item("a"))))
                .enqueue(FakeJaxRs.response(200, page(false, "", item("b"))));

        client.getDataItems(KEY, FROM, TO);

        assertEquals(List.of(new RecordingLocalStorage.Call(TOOL, ITEMS_SOURCE, DigestUtils.sha256Hex(FROM + TO), LIST_TTL)),
                localStorage.callsFor(ITEMS_SOURCE));
        assertEquals(List.of(
                        new RecordingLocalStorage.Call(TOOL, PAGE_SOURCE, DigestUtils.sha256Hex(FROM + TO), TTL),
                        new RecordingLocalStorage.Call(TOOL, PAGE_SOURCE, DigestUtils.sha256Hex(FROM + TO + "cursor-2"), TTL)),
                localStorage.callsFor(PAGE_SOURCE));
    }

    @Test
    void getDataItemsStopsWhenMorePagesAreReportedWithoutACursor() {
        http.enqueue(FakeJaxRs.response(200, page(true, " ", item("a"))));

        assertEquals(List.of("a"), ids(client.getDataItems(KEY, FROM, TO)));
        assertEquals(1, httpCaller.calls());
    }

    @Test
    void getDataItemsStopsWhenThereAreNoMorePagesEvenWithACursor() {
        http.enqueue(FakeJaxRs.response(200, page(false, "stale-cursor", item("a"))));

        assertEquals(List.of("a"), ids(client.getDataItems(KEY, FROM, TO)));
        assertEquals(1, httpCaller.calls());
    }

    @Test
    void getDataItemsReturnsAnEmptyListForANullCachedPage() {
        localStorage.primeNull(PAGE_SOURCE);

        assertEquals(List.of(), client.getDataItems(KEY, FROM, TO));
        assertEquals(1, localStorage.callsFor(PAGE_SOURCE).size());
    }

    @Test
    void getDataItemsStopsWhenThePageIsMissing() {
        http.enqueue(FakeJaxRs.response(200, new DovetailDataList(List.of(item("a")), null)));

        assertEquals(List.of("a"), ids(client.getDataItems(KEY, FROM, TO)));
        assertEquals(1, httpCaller.calls());
    }

    @Test
    void getDataItemsTreatsMissingDataAsAnEmptyPage() {
        http.enqueue(FakeJaxRs.response(200, new DovetailDataList(null, new DovetailPage(0, false, null))));

        assertEquals(List.of(), client.getDataItems(KEY, FROM, TO));
    }

    @Test
    void getDataItemsDefaultsTheEndDateAndOmitsAMissingStartDate() {
        final String today = OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS).format(ISO_OFFSET_DATE_TIME);
        http.enqueue(FakeJaxRs.response(200, page(false, null, item("a"))));

        client.getDataItems(KEY, null, " ");

        assertEquals(List.of(), http.queryParams("filter[created_at][gte]"));
        assertEquals(List.of(today), http.queryParams("filter[created_at][lte]"));
        assertEquals(List.of(), http.queryParams("page[start_cursor]"));
        // A missing start date is hashed as the text "null"
        assertEquals(DigestUtils.sha256Hex("null" + today), localStorage.callsFor(ITEMS_SOURCE).getFirst().promptHash());
    }

    @Test
    void getDataItemsReturnsCachedItemsWithoutCallingTheApi() {
        localStorage.prime(ITEMS_SOURCE, new DovetailDataItem[]{item("cached")});

        assertEquals(List.of("cached"), ids(client.getDataItems(KEY, FROM, TO)));
        assertEquals(0, httpCaller.calls());
        assertTrue(mutex.lockNames.isEmpty());
    }

    @Test
    void getDataItemsReturnsAnEmptyListForANullCacheEntry() {
        localStorage.primeNull(ITEMS_SOURCE);

        assertEquals(List.of(), client.getDataItems(KEY, FROM, TO));
    }

    @Test
    void getDataItemsStopsAfterOneHundredPages() {
        // Every page lookup returns the same cached page, which always reports another page
        localStorage.prime(PAGE_SOURCE, page(true, "again", item("loop")));

        final List<DovetailDataItem> result = client.getDataItems(KEY, FROM, TO);

        assertEquals(100, result.size());
        assertEquals(100, localStorage.callsFor(PAGE_SOURCE).size());
        assertEquals(0, httpCaller.calls());
    }

    @Test
    void getDataItemsPropagatesApiFailures() {
        http.enqueue(FakeJaxRs.response(500, "server error"));

        final RuntimeException ex = assertThrows(RuntimeException.class, () -> client.getDataItems(KEY, FROM, TO));

        assertTrue(ex.getMessage().startsWith("Failed to get data items from Dovetail API: "), ex.getMessage());
        assertInstanceOf(InvalidResponse.class, ex.getCause());
    }

    @Test
    void getDataItemsUsesTheConfiguredLock() {
        settings.put("sb.dovetail.lock", "custom.lock");
        TestConfigUtil.registerConfig(settings);
        http.enqueue(FakeJaxRs.response(200, page(false, null, item("a"))));

        client.getDataItems(KEY, FROM, TO);

        assertEquals(List.of("custom.lock"), mutex.lockNames);
    }

    @Test
    void exportDataItemAsMarkdownReturnsTheMarkdownAndCachesIt() {
        http.enqueue(FakeJaxRs.response(200, export("# Notes")));

        assertEquals("# Notes", client.exportDataItemAsMarkdown(KEY, "d1"));
        assertEquals(List.of(URL + "/api/v1/data/d1/export/markdown"), http.targets());
        assertEquals(List.of("Bearer " + KEY), http.headers("Authorization"));
        assertEquals(List.of(new RecordingLocalStorage.Call(TOOL, MARKDOWN_SOURCE, "d1", TTL)), localStorage.calls);
        assertEquals(List.of("sb_dovetail.lock"), mutex.lockNames);
    }

    @Test
    void exportDataItemAsMarkdownReturnsBlankForMissingContent() {
        http.enqueue(FakeJaxRs.response(200, export(null)))
                .enqueue(FakeJaxRs.response(200, new DovetailDataExportResponse(null)))
                .enqueue(FakeJaxRs.response(200, null));

        assertEquals("", client.exportDataItemAsMarkdown(KEY, "d1"));
        assertEquals("", client.exportDataItemAsMarkdown(KEY, "d2"));
        assertEquals("", client.exportDataItemAsMarkdown(KEY, "d3"));
    }

    @Test
    void exportDataItemAsMarkdownReturnsBlankWhenTheApiFails() {
        http.enqueue(FakeJaxRs.response(404, null));

        assertEquals("", client.exportDataItemAsMarkdown(KEY, "missing"));
        assertEquals(1, httpCaller.calls());
    }

    @Test
    void exportDataItemAsMarkdownUsesTheCache() {
        localStorage.prime(MARKDOWN_SOURCE, "cached markdown");

        assertEquals("cached markdown", client.exportDataItemAsMarkdown(KEY, "d1"));
        assertEquals(0, httpCaller.calls());
    }

    @Test
    void exportDataItemAsMarkdownReturnsBlankForANullCacheEntry() {
        localStorage.primeNull(MARKDOWN_SOURCE);

        assertEquals("", client.exportDataItemAsMarkdown(KEY, "d1"));
    }
}
