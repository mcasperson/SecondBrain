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
        if (duration > warningThresholdMs) {
            logger.warning("Operation " + name + " took " + (System.currentTimeMillis() - startTime) + " ms");
        } else {
            logger.info("Operation " + name + " took " + (System.currentTimeMillis() - startTime) + " ms");
        }
    }
}
