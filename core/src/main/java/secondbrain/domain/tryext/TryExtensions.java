package secondbrain.domain.tryext;

import io.vavr.CheckedFunction0;
import io.vavr.CheckedFunction1;
import io.vavr.control.Try;

public final class TryExtensions {
    /**
     * Provide a simple way to nest Try.withResources calls for two resources where the
     * second resource depends on the first.
     */
    public static <T1 extends AutoCloseable, T2 extends AutoCloseable> Try.WithResources1<T2> withResources(
            final CheckedFunction0<T1> t1Supplier,
            final CheckedFunction1<T1, T2> t2Supplier) {
        return Try.withResources(t1Supplier)
                .of(x -> Try.withResources(() -> t2Supplier.apply(x)))
                .get();
    }
}
