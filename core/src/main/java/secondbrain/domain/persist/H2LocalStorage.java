package secondbrain.domain.persist;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang3.StringUtils;

import java.sql.DriverManager;
import java.sql.PreparedStatement;

@ApplicationScoped
public class H2LocalStorage implements LocalStorage {

    private static final String DATABASE = "jdbc:h2:~/se";

    @Override
    public String getString(final String tool, final String source, final String promptHash) {
        return Try.withResources(() -> DriverManager.getConnection(DATABASE))
                .of(connection -> Try
                        .of(() -> connection.prepareStatement("SELECT value FROM local_storage WHERE tool = ? AND source = ? AND prompt_hash = ?"))
                        .mapTry(preparedStatement -> {
                            preparedStatement.setString(1, tool);
                            preparedStatement.setString(2, source);
                            preparedStatement.setString(3, promptHash);
                            return preparedStatement;
                        })
                        .mapTry(PreparedStatement::executeQuery)
                        .mapTry(resultSet -> {
                            resultSet.next();
                            return resultSet.getString(1);
                        })
                        .getOrNull())
                .getOrNull();
    }

    @Override
    public String getOrPutString(String tool, String source, String promptHash, GenerateValue generateValue) {
        final String cache = getString(tool, source, promptHash);
        if (StringUtils.isNotBlank(cache)) {
            return cache;
        }

        final String newValue = generateValue.generate();
        putString(tool, source, promptHash, newValue);
        return newValue;
    }

    @Override
    public void putString(final String tool, final String source, final String promptHash, final String value) {
        Try.withResources(() -> DriverManager.getConnection(DATABASE))
                .of(connection -> Try
                        .of(() -> connection.prepareStatement("INSERT INTO local_storage (tool, source, prompt_hash, value) VALUES (?, ?, ?, ?)"))
                        .mapTry(preparedStatement -> {
                            preparedStatement.setString(1, tool);
                            preparedStatement.setString(2, source);
                            preparedStatement.setString(3, promptHash);
                            preparedStatement.setString(4, value);
                            preparedStatement.executeUpdate();
                            return preparedStatement;
                        }));
    }
}
