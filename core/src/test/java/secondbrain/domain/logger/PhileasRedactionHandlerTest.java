package secondbrain.domain.logger;

import ai.philterd.phileas.PhileasConfiguration;
import ai.philterd.phileas.services.context.DefaultContextService;
import ai.philterd.phileas.services.disambiguation.vector.InMemoryVectorService;
import ai.philterd.phileas.services.filters.filtering.PlainTextFilterService;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.ErrorManager;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
class PhileasRedactionHandlerTest {
    private final RecordingHandler delegate = new RecordingHandler();
    private final RecordingErrorManager errors = new RecordingErrorManager();

    @Inject
    private PlainTextFilterService filterService;

    private PhileasRedactionHandler handler;

    /**
     * The real, in-process Phileas filter service, built the same way as in Loggers. The producer is static so the
     * test instance can inject the service without a circular dependency on itself.
     */
    @Produces
    static PlainTextFilterService produceFilterService() {
        return new PlainTextFilterService(
                new PhileasConfiguration(new Properties()),
                new DefaultContextService(),
                new InMemoryVectorService(),
                null);
    }

    /**
     * PhileasRedactionHandler is not a CDI bean: Loggers creates it with a delegate handler and the filter service.
     */
    @BeforeEach
    void createHandler() {
        handler = new PhileasRedactionHandler(delegate, filterService);
        handler.setErrorManager(errors);
    }

    @Test
    void publishRedactsEmailAndIpAddressesBeforeDelegating() {
        final LogRecord record = new LogRecord(Level.INFO, "Contact support@example.com from 192.168.10.20 now");

        handler.publish(record);

        assertEquals(List.of(record), delegate.records);
        assertEquals("Contact {{{REDACTED-EMAIL}}} from {{{REDACTED-IP}}} now", record.getMessage());
        assertEquals(Level.INFO, record.getLevel());
        assertTrue(errors.errors.isEmpty());
    }

    @Test
    void publishLeavesTextWithoutPiiUnchanged() {
        final LogRecord record = new LogRecord(Level.FINE, "upload completed successfully");

        handler.publish(record);

        assertEquals("upload completed successfully", delegate.records.getFirst().getMessage());
    }

    @Test
    void publishReportsAWriteFailureWhenTheDelegateFails() {
        delegate.failure = new IllegalStateException("disk full");

        handler.publish(new LogRecord(Level.WARNING, "plain message"));

        assertEquals(1, errors.errors.size());
        final RecordedError error = errors.errors.getFirst();
        assertEquals("Redaction failed", error.message());
        assertEquals(ErrorManager.WRITE_FAILURE, error.code());
        assertSame(delegate.failure, error.exception());
    }

    @Test
    void publishDoesNotDelegateWhenRedactionFails() {
        // A null message cannot be filtered
        handler.publish(new LogRecord(Level.INFO, null));

        assertTrue(delegate.records.isEmpty());
        assertEquals(List.of(ErrorManager.WRITE_FAILURE), errors.errors.stream().map(RecordedError::code).toList());
    }

    @Test
    void flushAndCloseAreDelegated() {
        handler.flush();
        handler.flush();
        handler.close();

        assertEquals(2, delegate.flushes);
        assertEquals(1, delegate.closes);
    }

    private record RecordedError(String message, Exception exception, int code) {
    }

    private static final class RecordingErrorManager extends ErrorManager {
        final List<RecordedError> errors = new CopyOnWriteArrayList<>();

        @Override
        public synchronized void error(final String msg, final Exception ex, final int code) {
            errors.add(new RecordedError(msg, ex, code));
        }
    }

    private static final class RecordingHandler extends Handler {
        final List<LogRecord> records = new CopyOnWriteArrayList<>();
        @Nullable
        RuntimeException failure;
        int flushes;
        int closes;

        @Override
        public void publish(final LogRecord record) {
            if (failure != null) {
                throw failure;
            }
            records.add(record);
        }

        @Override
        public void flush() {
            flushes++;
        }

        @Override
        public void close() {
            closes++;
        }
    }
}
