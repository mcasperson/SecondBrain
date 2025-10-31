package secondbrain.domain.persist;

import java.util.logging.Logger;

public class TimedOperation implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(TimedOperation.class.getName());
    private final long startTime = System.currentTimeMillis();
    private final String name;

    public TimedOperation(final String name) {
        this.name = name;
    }

    @Override
    public void close() throws Exception {
        logger.fine("Operation " + name + " took " + (System.currentTimeMillis() - startTime) + " ms");
    }
}
