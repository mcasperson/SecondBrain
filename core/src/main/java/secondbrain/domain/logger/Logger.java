package secondbrain.domain.logger;

import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.validation.constraints.NotNull;

import java.util.logging.Logger;

class Loggers {
    @Produces
    @NotNull Logger getLogger(@NotNull final InjectionPoint injectionPoint) {
        return Logger.getLogger( injectionPoint.getMember().getDeclaringClass()
                .getSimpleName() );
    }
}