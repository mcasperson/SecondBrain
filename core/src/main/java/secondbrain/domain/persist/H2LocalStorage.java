package secondbrain.domain.persist;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang3.StringUtils;

import java.sql.DriverManager;
import java.sql.PreparedStatement;

@ApplicationScoped
public class H2LocalStorage implements LocalStorage {

    private static final String DATABASE = "jdbc:h2:file:./localstorage;"
            + "INIT=CREATE SCHEMA IF NOT EXISTS SECONDBRAIN\\;"
            + "SET SCHEMA SECONDBRAIN\\;"
            + "CREATE TABLE local_storage (tool VARCHAR(100) NOT NULL, source VARCHAR(1024) NOT NULL, prompt_hash VARCHAR(1024) NOT NULL, response CLOB NOT NULL);";

    @Override
    public String getString(final String tool, final String source, final String promptHash) {
        return Try.withResources(() -> DriverManager.getConnection(DATABASE))
                .of(connection -> Try
                        .of(() -> connection.prepareStatement("SELECT response FROM local_storage WHERE tool = ? AND source = ? AND prompt_hash = ?"))
                        .mapTry(preparedStatement -> {
                            preparedStatement.setString(1, tool);
                            preparedStatement.setString(2, source);
                            preparedStatement.setString(3, promptHash);
                            return preparedStatement;
                        })
                        .mapTry(PreparedStatement::executeQuery)
                        .mapTry(resultSet -> {
                            if (resultSet.next()) {
                                return resultSet.getString(1);
                            }
                            return null;
                        })
                        .onFailure(Throwable::printStackTrace)
                        .getOrNull())
                .onFailure(Throwable::printStackTrace)
                .getOrNull();
    }

    @Override
    public String getOrPutString(final String tool, final String source, final String promptHash, final GenerateValue generateValue) {
        final String cache = getString(tool, source, promptHash);
        if (StringUtils.isNotBlank(cache)) {
            return cache;
        }

        final String newValue = generateValue.generate();
        putString(tool, source, promptHash, newValue);
        return newValue;
    }

    @Override
    public void putString(final String tool, final String source, final String promptHash, final String response) {
        Try.withResources(() -> DriverManager.getConnection(DATABASE))
                .of(connection -> Try
                        .of(() -> connection.prepareStatement("INSERT INTO local_storage (tool, source, prompt_hash, response) VALUES (?, ?, ?, ?)"))
                        .mapTry(preparedStatement -> {
                            preparedStatement.setString(1, tool);
                            preparedStatement.setString(2, source);
                            preparedStatement.setString(3, promptHash);
                            preparedStatement.setString(4, response);
                            preparedStatement.executeUpdate();
                            return preparedStatement;
                        })
                        .onFailure(Throwable::printStackTrace))
                .onFailure(Throwable::printStackTrace);
    }
}
