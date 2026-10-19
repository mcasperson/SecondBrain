package secondbrain.infrastructure.snowflake;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(SnowflakeClientMock.class)
class SnowflakeClientMockTest {

	@Inject
	private SnowflakeClientMock client;

	private static SnowflakeLicenseDetails license(final String accountId, final int totalLicenses) {
		return new SnowflakeLicenseDetails(accountId, totalLicenses, null, null, null, null, null, null, null, null,
				null, null, null, null, null, null, null, null, null, null, null, null);
	}

	@Test
	void defaultsToNoLicenseDetails() {
		assertEquals(List.of(), client.getLicenseDetails("account-1"));
	}

	@Test
	void defaultsToNoAdoptionIndex() {
		assertEquals(Optional.empty(), client.getAdoptionIndex("account-1"));
	}

	@Test
	void returnsConfiguredLicenseDetailsInOrder() {
		final SnowflakeLicenseDetails first = license("a1", 10);
		final SnowflakeLicenseDetails second = license("a2", 20);

		client.setMockLicenseDetails(first, second);

		assertEquals(List.of(first, second), client.getLicenseDetails("a1"));
	}

	@Test
	void returnsConfiguredLicenseDetailsRegardlessOfId() {
		final SnowflakeLicenseDetails details = license("a1", 10);

		client.setMockLicenseDetails(details);

		assertEquals(List.of(details), client.getLicenseDetails("some-other-id"));
		assertEquals(List.of(details), client.getLicenseDetails(""));
	}

	@Test
	void settingLicenseDetailsReplacesPreviousValues() {
		final SnowflakeLicenseDetails replacement = license("a3", 30);
		client.setMockLicenseDetails(license("a1", 10), license("a2", 20));

		client.setMockLicenseDetails(replacement);

		assertEquals(List.of(replacement), client.getLicenseDetails("a1"));
	}

	@Test
	void settingNoLicenseDetailsClearsThem() {
		client.setMockLicenseDetails(license("a1", 10));

		client.setMockLicenseDetails();

		assertEquals(List.of(), client.getLicenseDetails("a1"));
	}

	@Test
	void returnsConfiguredAdoptionIndexRegardlessOfId() {
		final SnowflakeAdoptionIndexDetails adoption =
				new SnowflakeAdoptionIndexDetails("a1", 0.75, "Healthy", LocalDate.of(2025, 3, 1));

		client.setMockAdoptionIndex(adoption);

		assertEquals(Optional.of(adoption), client.getAdoptionIndex("a1"));
		assertEquals(Optional.of(adoption), client.getAdoptionIndex("another"));
	}

	@Test
	void resetMocksClearsLicenseDetailsAndAdoptionIndex() {
		client.setMockLicenseDetails(license("a1", 10));
		client.setMockAdoptionIndex(new SnowflakeAdoptionIndexDetails("a1", 0.5, "At risk", LocalDate.of(2025, 1, 1)));

		client.resetMocks();

		assertEquals(List.of(), client.getLicenseDetails("a1"));
		assertEquals(Optional.empty(), client.getAdoptionIndex("a1"));
	}

	@Test
	void canBeConfiguredAgainAfterReset() {
		final SnowflakeLicenseDetails details = license("a2", 5);
		client.setMockLicenseDetails(license("a1", 10));
		client.resetMocks();

		client.setMockLicenseDetails(details);

		assertEquals(List.of(details), client.getLicenseDetails("a2"));
	}
}
