package secondbrain.domain.persist;

import java.util.logging.Logger;

public class TimedOperation implements AutoCloseable {
    private static final long DEFAULT_WARNING_THRESHOLD_MS = 10000;
    private static final Logger logger = Logger.getLogger(TimedOperation.class.getName());
    private final long startTime = System.currentTimeMillis();
    private final String name;
    private final long warningThresholdMs;

    public TimedOperation(final String name) {
        this.name = name;
        this.warningThresholdMs = DEFAULT_WARNING_THRESHOLD_MS;
    }

    public TimedOperation(final String name, final long warningThresholdMs) {
        this.name = name;
        this.warningThresholdMs = warningThresholdMs;
    }

    @Override
    public void close() throws Exception {
        final long duration = System.currentTimeMillis() - startTime;
        final double durationMinutes = duration / 60000.0;
        final double durationHours = duration / 3600000.0;
        final String durationMessage = String.format(
                "Operation %s took %d ms (%.2f minutes, %.2f hours)",
                name,
                duration,
                durationMinutes,
                durationHours);

        if (duration > warningThresholdMs) {
            logger.warning(durationMessage);
        } else {
            logger.fine(durationMessage);
        }
    }
}
