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

    public static SnowflakeAdoptionIndexDetails fromResultSet(final java.sql.ResultSet rs) throws java.sql.SQLException {
        final java.sql.Date adoptionIndexDate = rs.getDate("ADOPTION_INDEX_DATE");
        return new SnowflakeAdoptionIndexDetails(
                rs.getString("ACCOUNT_ID"),
                rs.getObject("ADOPTION_INDEX", Double.class),
                rs.getString("ADOPTION_INDEX_STATUS"),
                adoptionIndexDate != null ? adoptionIndexDate.toLocalDate() : null
        );
    }
}
