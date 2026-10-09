package secondbrain.infrastructure.snowflake;

import jakarta.enterprise.context.ApplicationScoped;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A mock implementation of the SnowflakeClient interface for testing purposes.
 */
@ApplicationScoped
public class SnowflakeClientMock implements SnowflakeClient {

    private List<SnowflakeLicenseDetails> mockLicenseDetails = new ArrayList<>();

    @Nullable
    private SnowflakeAdoptionIndexDetails mockAdoptionIndex;

    public void setMockLicenseDetails(final SnowflakeLicenseDetails... details) {
        this.mockLicenseDetails = List.of(details);
    }

    public void setMockAdoptionIndex(final SnowflakeAdoptionIndexDetails adoptionIndex) {
        this.mockAdoptionIndex = adoptionIndex;
    }

    public void resetMocks() {
        this.mockLicenseDetails = new ArrayList<>();
        this.mockAdoptionIndex = null;
    }

    @Override
    public List<SnowflakeLicenseDetails> getLicenseDetails(final String id) {
        return mockLicenseDetails;
    }

    @Override
    public Optional<SnowflakeAdoptionIndexDetails> getAdoptionIndex(final String id) {
        return Optional.ofNullable(mockAdoptionIndex);
    }
}
