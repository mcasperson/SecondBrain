package secondbrain.domain.persist;

import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.CosmosItemResponse;
import com.azure.cosmos.models.PartitionKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CosmosCacheContainerLive}. The Cosmos SDK objects are passed to the adapter's constructor as
 * Mockito mocks, so no emulator is needed.
 */
class CosmosCacheContainerLiveTest {
	private static final String ID = "Tool_Source_hash";
	private static final String PARTITION = "Tool";
	private static final CosmosLocalStorage.CacheItem ITEM =
			new CosmosLocalStorage.CacheItem(ID, "Tool", "Source", "hash", "cached response", 1234L, 60);

	private final CosmosContainer container = mock(CosmosContainer.class);
	/*
	 CosmosClient is a final class that can only be built by connecting to Cosmos DB. This module uses the subclass
	 mock maker, which can not mock final classes, so no client is supplied. The client is only used by close().
	 */
	@SuppressWarnings("NullAway")
	private final CosmosCacheContainerLive live = new CosmosCacheContainerLive(null, container);

	private static CosmosException cosmosException(final int status) {
		return new CosmosException(status, "status " + status) {
		};
	}

	@SuppressWarnings({"unchecked", "NullAway"})
	private void stubRead(final CosmosLocalStorage.CacheItem item) {
		final CosmosItemResponse<CosmosLocalStorage.CacheItem> response = mock(CosmosItemResponse.class);
		when(response.getItem()).thenReturn(item);
		when(container.readItem(ID, new PartitionKey(PARTITION), CosmosLocalStorage.CacheItem.class)).thenReturn(response);
	}

	@Test
	void readItemReturnsTheStoredItem() {
		stubRead(ITEM);

		assertEquals(Optional.of(ITEM), live.readItem(ID, PARTITION));
	}

	@Test
	@SuppressWarnings("NullAway")
	void readItemReturnsEmptyWhenTheResponseHasNoItem() {
		stubRead(null);

		assertEquals(Optional.empty(), live.readItem(ID, PARTITION));
	}

	@Test
	void readItemReturnsEmptyWhenTheItemIsNotFound() {
		when(container.readItem(ID, new PartitionKey(PARTITION), CosmosLocalStorage.CacheItem.class)).thenThrow(cosmosException(404));

		assertEquals(Optional.empty(), live.readItem(ID, PARTITION));
	}

	@ParameterizedTest
	@ValueSource(ints = {403, 405, 409, 500})
	void readItemRethrowsStatusesOtherThanNotFound(final int status) {
		final CosmosException failure = cosmosException(status);
		when(container.readItem(ID, new PartitionKey(PARTITION), CosmosLocalStorage.CacheItem.class)).thenThrow(failure);

		final CosmosException thrown = assertThrows(CosmosException.class, () -> live.readItem(ID, PARTITION));

		assertSame(failure, thrown);
	}

	@Test
	void upsertItemSavesTheItemInThePartitionWithoutConditions() {
		final ArgumentCaptor<CosmosItemRequestOptions> options = ArgumentCaptor.forClass(CosmosItemRequestOptions.class);

		live.upsertItem(ITEM, PARTITION);

		verify(container).upsertItem(eq(ITEM), eq(new PartitionKey(PARTITION)), options.capture());
		assertNull(options.getValue().getIfMatchETag());
	}

	@Test
	void upsertItemPropagatesFailures() {
		final CosmosException failure = cosmosException(500);
		doThrow(failure).when(container).upsertItem(eq(ITEM), eq(new PartitionKey(PARTITION)), any(CosmosItemRequestOptions.class));

		final CosmosException thrown = assertThrows(CosmosException.class, () -> live.upsertItem(ITEM, PARTITION));

		assertSame(failure, thrown);
	}

}
