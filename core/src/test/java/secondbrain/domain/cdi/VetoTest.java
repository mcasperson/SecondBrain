package secondbrain.domain.cdi;

import io.smallrye.config.inject.ConfigExtension;
import io.smallrye.faulttolerance.VetoFixtureBean;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;
import secondbrain.domain.sanitize.SanitizeDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies through Weld that {@link Veto} removes classes in SmallRye Fault Tolerance packages from CDI while leaving
 * other classes managed.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddExtensions(Veto.class)
@AddBeanClasses(VetoFixtureBean.class)
@AddBeanClasses(SanitizeDate.class)
class VetoTest {
	@Inject
	private BeanManager beanManager;

	@Test
	void classesInFaultTolerancePackagesAreNotBeans() {
		assertTrue(beanManager.getBeans(VetoFixtureBean.class, Any.Literal.INSTANCE).isEmpty());
	}

	@Test
	void classesInOtherPackagesRemainBeans() {
		assertEquals(1, beanManager.getBeans(SanitizeDate.class, Any.Literal.INSTANCE).size());
	}
}
