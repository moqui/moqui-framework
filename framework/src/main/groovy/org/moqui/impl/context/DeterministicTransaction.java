package org.moqui.impl.context;

import org.moqui.context.ArtifactExecutionInfo;
import org.moqui.util.DeterministicClock;
import java.util.concurrent.Callable;

/** Makes normally autocommit entity operations real facade transactions before defaults/stamps or SQL. */
public final class DeterministicTransaction {
    private DeterministicTransaction() { }
    public static boolean needed(TransactionFacadeImpl tf) {
        return DeterministicClock.isEnabled() && !tf.isTransactionInPlace();
    }
    public static <T> T run(TransactionFacadeImpl tf, Callable<T> work) {
        boolean began = tf.begin(null);
        try {
            T result = work.call();
            tf.commit(began);
            return result;
        } catch (Throwable failure) {
            if (began && tf.isTransactionInPlace()) tf.rollback("Implicit entity operation failed", failure);
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            if (failure instanceof Error) throw (Error) failure;
            throw new IllegalStateException("Implicit entity operation failed", failure);
        }
    }
    public static <T> T entity(ExecutionContextFactoryImpl ecfi, String name, String detail, Callable<T> work) {
        ArtifactExecutionFacadeImpl artifacts = ecfi.getEci().artifactExecutionFacade;
        ArtifactExecutionInfoImpl artifact = new ArtifactExecutionInfoImpl(name, ArtifactExecutionInfo.AT_ENTITY,
                ArtifactExecutionInfo.AUTHZA_ALL, detail);
        // The normal operation still performs its usual authorization; this entry provides its identity before begin.
        artifacts.pushInternal(artifact, false, false);
        try { return run(ecfi.transactionFacade, work); }
        finally { artifacts.pop(artifact); }
    }
}
