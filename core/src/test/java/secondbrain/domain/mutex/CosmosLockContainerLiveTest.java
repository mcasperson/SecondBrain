package secondbrain.domain.mutex;

import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.implementation.NotFoundException;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.CosmosItemResponse;
import com.azure.cosmos.models.CosmosQueryRequestOptions;
import com.azure.cosmos.models.ModelBridgeInternal;
import com.azure.cosmos.models.PartitionKey;
import com.azure.cosmos.util.CosmosPagedIterable;
import com.azure.cosmos.util.UtilBridgeInternal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import secondbrain.domain.exceptions.LockFail;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CosmosLockContainerLive}. The Cosmos SDK objects are passed to the adapter's constructor as
 * Mockito mocks, so no emulator is needed.
 */
class CosmosLockContainerLiveTest {
	private static final String ID = "lock-1";
	private static final String PARTITION = "locks";
	private static final CosmosMutex.LockDocument DOCUMENT =
			new CosmosMutex.LockDocument(ID, "lock-1", Instant.parse("2026-01-02T03:04:05Z"), "etag-old", 30);

	private final CosmosContainer container = mock(CosmosContainer.class);
	/*
	 CosmosClient is a final class that can only be built by connecting to Cosmos DB. This module uses the subclass
	 mock maker, which can not mock final classes, so no client is supplied. The client is only used by close().
	 */
	@SuppressWarnings("NullAway")
	private final CosmosLockContainerLive live = new CosmosLockContainerLive(null, container);

	private static CosmosException cosmosException(final int status) {
		return new CosmosException(status, "status " + status) {
		};
	}

	@SuppressWarnings("unchecked")
	private static <T> CosmosItemResponse<T> response(final T item, final String etag) {
		final CosmosItemResponse<T> response = mock(CosmosItemResponse.class);
		when(response.getItem()).thenReturn(item);
		when(response.getETag()).thenReturn(etag);
		return response;
	}

	@Test
	void readItemReturnsTheStoredLock() {
		final CosmosItemResponse<CosmosMutex.LockDocument> response = response(DOCUMENT, "etag-old");
		when(container.readItem(ID, new PartitionKey(PARTITION), CosmosMutex.LockDocument.class)).thenReturn(response);

		assertEquals(Optional.of(DOCUMENT), live.readItem(ID, PARTITION));
	}

	@Test
	@SuppressWarnings("NullAway")
	void readItemReturnsEmptyWhenTheResponseHasNoItem() {
		final CosmosItemResponse<CosmosMutex.LockDocument> response = response(null, "etag");
		when(container.readItem(ID, new PartitionKey(PARTITION), CosmosMutex.LockDocument.class)).thenReturn(response);

		assertEquals(Optional.empty(), live.readItem(ID, PARTITION));
	}

	@Test
	void readItemReturnsEmptyWhenTheLockIsNotFound() {
		when(container.readItem(ID, new PartitionKey(PARTITION), CosmosMutex.LockDocument.class)).thenThrow(cosmosException(404));

		assertEquals(Optional.empty(), live.readItem(ID, PARTITION));
	}

	@ParameterizedTest
	@ValueSource(ints = {409, 412, 500})
	void readItemRethrowsStatusesOtherThanNotFound(final int status) {
		final CosmosException failure = cosmosException(status);
		when(container.readItem(ID, new PartitionKey(PARTITION), CosmosMutex.LockDocument.class)).thenThrow(failure);

		final CosmosException thrown = assertThrows(CosmosException.class, () -> live.readItem(ID, PARTITION));

		assertSame(failure, thrown);
	}

	@Test
	void upsertItemReturnsTheNewEtagAndSendsTheIfMatchEtag() {
		final CosmosItemResponse<CosmosMutex.LockDocument> response = response(DOCUMENT, "etag-new");
		final ArgumentCaptor<CosmosItemRequestOptions> options = ArgumentCaptor.forClass(CosmosItemRequestOptions.class);
		when(container.upsertItem(eq(DOCUMENT), eq(new PartitionKey(PARTITION)), options.capture())).thenReturn(response);

		assertEquals("etag-new", live.upsertItem(DOCUMENT, PARTITION, "etag-old"));
		assertEquals("etag-old", options.getValue().getIfMatchETag());
	}

	@Test
	void upsertItemWithoutAnEtagSendsNoIfMatchCondition() {
		final CosmosItemResponse<CosmosMutex.LockDocument> response = response(DOCUMENT, "etag-new");
		final ArgumentCaptor<CosmosItemRequestOptions> options = ArgumentCaptor.forClass(CosmosItemRequestOptions.class);
		when(container.upsertItem(eq(DOCUMENT), eq(new PartitionKey(PARTITION)), options.capture())).thenReturn(response);

		assertEquals("etag-new", live.upsertItem(DOCUMENT, PARTITION, null));
		assertNull(options.getValue().getIfMatchETag());
	}

	@ParameterizedTest
	@ValueSource(ints = {409, 412})
	void upsertItemReportsConflictsAsLockFail(final int status) {
		final CosmosException failure = cosmosException(status);
		when(container.upsertItem(eq(DOCUMENT), eq(new PartitionKey(PARTITION)), any(CosmosItemRequestOptions.class))).thenThrow(failure);

		final LockFail thrown = assertThrows(LockFail.class, () -> live.upsertItem(DOCUMENT, PARTITION, "etag-old"));

		assertEquals("Failed to obtain lock - lock lock-1 was saved by another process", thrown.getMessage());
		assertSame(failure, thrown.getCause());
	}

	@ParameterizedTest
	@ValueSource(ints = {404, 408, 411, 413, 500})
	void upsertItemRethrowsOtherStatusesUnchanged(final int status) {
		final CosmosException failure = cosmosException(status);
		when(container.upsertItem(eq(DOCUMENT), eq(new PartitionKey(PARTITION)), any(CosmosItemRequestOptions.class))).thenThrow(failure);

		final CosmosException thrown = assertThrows(CosmosException.class, () -> live.upsertItem(DOCUMENT, PARTITION, "etag-old"));

		assertSame(failure, thrown);
	}

	@Test
	void deleteItemSendsTheIfMatchEtag() {
		final ArgumentCaptor<CosmosItemRequestOptions> options = ArgumentCaptor.forClass(CosmosItemRequestOptions.class);

		live.deleteItem(ID, PARTITION, "etag-old");

		verify(container).deleteItem(eq(ID), eq(new PartitionKey(PARTITION)), options.capture());
		assertEquals("etag-old", options.getValue().getIfMatchETag());
	}

	@ParameterizedTest
	@ValueSource(ints = {404, 412})
	void deleteItemIgnoresMissingOrReplacedLocks(final int status) {
		when(container.deleteItem(eq(ID), eq(new PartitionKey(PARTITION)), any(CosmosItemRequestOptions.class))).thenThrow(cosmosException(status));

		assertDoesNotThrow(() -> live.deleteItem(ID, PARTITION, "etag-old"));
	}

	@ParameterizedTest
	@ValueSource(ints = {409, 500})
	void deleteItemRethrowsOtherStatuses(final int status) {
		final CosmosException failure = cosmosException(status);
		when(container.deleteItem(eq(ID), eq(new PartitionKey(PARTITION)), any(CosmosItemRequestOptions.class))).thenThrow(failure);

		final CosmosException thrown = assertThrows(CosmosException.class, () -> live.deleteItem(ID, PARTITION, "etag-old"));

		assertSame(failure, thrown);
	}

	@Test
	void deleteAllItemsDeletesEveryQueriedLockInThePartition() {
		final CosmosMutex.LockDocument second = new CosmosMutex.LockDocument("lock-2", "lock-2", Instant.EPOCH, null, 30);
		// CosmosPagedIterable is final, so a real instance is built over an in-memory page
		final CosmosPagedIterable<CosmosMutex.LockDocument> results = UtilBridgeInternal.createCosmosPagedIterable(
				UtilBridgeInternal.createCosmosPagedFlux(options ->
						Flux.just(ModelBridgeInternal.createFeedResponse(List.of(DOCUMENT, second), Map.of()))));
		when(container.queryItems(eq("SELECT * FROM c"), any(CosmosQueryRequestOptions.class), eq(CosmosMutex.LockDocument.class)))
				.thenReturn(results);

		live.deleteAllItems(PARTITION);

		final ArgumentCaptor<CosmosItemRequestOptions> options = ArgumentCaptor.forClass(CosmosItemRequestOptions.class);
		verify(container).deleteItem(eq(ID), eq(new PartitionKey(PARTITION)), options.capture());
		verify(container).deleteItem(eq("lock-2"), eq(new PartitionKey(PARTITION)), any(CosmosItemRequestOptions.class));
		verify(container, times(2)).deleteItem(anyString(), any(PartitionKey.class), any(CosmosItemRequestOptions.class));
		// Clearing locks is unconditional, so no etag is sent
		assertNull(options.getValue().getIfMatchETag());
	}

	@Test
	void deleteAllItemsIgnoresAMissingContainer() {
		when(container.queryItems(eq("SELECT * FROM c"), any(CosmosQueryRequestOptions.class), eq(CosmosMutex.LockDocument.class)))
				.thenThrow(new NotFoundException("missing"));

		assertDoesNotThrow(() -> live.deleteAllItems(PARTITION));
	}

	@Test
	void deleteAllItemsRethrowsOtherFailures() {
		final CosmosException failure = cosmosException(500);
		when(container.queryItems(eq("SELECT * FROM c"), any(CosmosQueryRequestOptions.class), eq(CosmosMutex.LockDocument.class)))
				.thenThrow(failure);

		final CosmosException thrown = assertThrows(CosmosException.class, () -> live.deleteAllItems(PARTITION));

		assertSame(failure, thrown);
	}

}
