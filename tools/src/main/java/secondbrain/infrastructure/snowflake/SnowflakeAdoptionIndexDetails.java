package secondbrain.infrastructure.snowflake;

import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.util.Objects;

public record SnowflakeAdoptionIndexDetails(
        String accountId,
        @Nullable Double adoptionIndex,
        @Nullable String adoptionIndexStatus,
        @Nullable LocalDate adoptionIndexDate
) {
    public Double getAdoptionIndex() {
        return Objects.requireNonNullElse(adoptionIndex, 0.0);
    }

    public String getAdoptionIndexStatus() {
        return Objects.requireNonNullElse(adoptionIndexStatus, "");
    }

    public LocalDate getAdoptionIndexDate() {
        return Objects.requireNonNullElse(adoptionIndexDate, LocalDate.EPOCH);
    }

    // JDBC can return a null ID, which is passed through as it was when reading the ResultSet directly
    @SuppressWarnings("NullAway")
    public static SnowflakeAdoptionIndexDetails fromRow(final SnowflakeRow rs) {
        return new SnowflakeAdoptionIndexDetails(
                rs.getString("ACCOUNT_ID"),
                rs.getObject("ADOPTION_INDEX", Double.class),
                rs.getString("ADOPTION_INDEX_STATUS"),
                rs.getDate("ADOPTION_INDEX_DATE")
        );
    }
}
