package secondbrain.domain.cdi;

import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.AnnotatedType;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.ProcessAnnotatedType;

/**
 * Need to fix this error introduce by the Azure Cosmos DB SDK dependency:
 * Exception in thread "main" org.jboss.weld.exceptions.DeploymentException: WELD-001408: Unsatisfied dependencies for type MeterRegistry with qualifiers @Default
 * at injection point [UnbackedAnnotatedField] @Inject io.smallrye.faulttolerance.metrics.MicrometerProvider.registry
 */
public class Veto implements Extension {
    public void vetoSpecificClass(@Observes final ProcessAnnotatedType<?> pat) {
        final AnnotatedType<?> annotatedType = pat.getAnnotatedType();
        final Class<?> clazz = annotatedType.getJavaClass();

        // We want to exclude any fault tolerance classes from being managed by CDI
        if (clazz.getPackageName().startsWith("io.smallrye.faulttolerance")) {
            pat.veto(); // Veto the class
            System.out.println("Vetoed class: " + clazz.getName());
        }
    }
}
