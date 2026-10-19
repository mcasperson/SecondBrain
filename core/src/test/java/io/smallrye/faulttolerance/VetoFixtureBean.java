package io.smallrye.faulttolerance;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * A test fixture in a SmallRye Fault Tolerance package, used by {@code secondbrain.domain.cdi.VetoTest} to verify
 * that such classes are vetoed. SmallRye Fault Tolerance is not on the test classpath, so a fixture is required.
 */
@ApplicationScoped
public class VetoFixtureBean {
}
