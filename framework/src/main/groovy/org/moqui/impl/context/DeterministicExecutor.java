package org.moqui.impl.context;

import org.moqui.util.DeterministicClock;
import java.util.List;
import java.util.concurrent.*;

/** FIFO inline worker queue. No worker threads; suspended transactions also fence draining. */
public final class DeterministicExecutor extends ThreadPoolExecutor {
    private final ExecutionContextFactoryImpl ecfi;
    private boolean draining;
    public DeterministicExecutor(ExecutionContextFactoryImpl ecfi) {
        super(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        this.ecfi = ecfi;
    }
    @Override public void execute(Runnable task) {
        DeterministicClock.lockLane();
        try {
            if (isShutdown()) throw new RejectedExecutionException("Deterministic worker is shut down");
            getQueue().add(task);
            drain();
        } finally { DeterministicClock.unlockLane(); }
    }
    public void drain() {
        DeterministicClock.lockLane();
        try {
        if (draining || DeterministicClock.get().hasTransaction()) return;
        draining = true;
        try {
            Runnable task;
            while ((task = getQueue().poll()) != null) {
                ExecutionContextImpl previous = ecfi.activeContext.get();
                ecfi.activeContext.remove();
                DeterministicClock.Operation operation = DeterministicClock.bootstrap();
                boolean success = false;
                try {
                    task.run();
                    if (task instanceof Future<?>) {
                        try { ((Future<?>) task).get(); }
                        catch (ExecutionException e) { throw new IllegalStateException("Deterministic queued work failed", e.getCause()); }
                        catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Deterministic queued work interrupted");
                        }
                    }
                    success = true;
                }
                finally {
                    try { ecfi.destroyActiveExecutionContext(); }
                    finally {
                        if (previous != null) {
                            ecfi.activeContext.set(previous);
                            ecfi.activeContextMap.put(Thread.currentThread().threadId(), previous);
                        }
                        operation.complete(success ? "committed" : "rolled_back");
                    }
                }
            }
        } finally { draining = false; }
        } finally { DeterministicClock.unlockLane(); }
    }
    @Override protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) { return checkedFuture(callable); }
    @Override protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        return checkedFuture(Executors.callable(runnable, value));
    }
    private <T> RunnableFuture<T> checkedFuture(Callable<T> callable) {
        return new FutureTask<>(callable) {
            private void ready() {
                if (!isDone() && DeterministicClock.get().hasTransaction())
                    throw new IllegalStateException("Cannot await queued async work inside a deterministic transaction");
                drain();
            }
            @Override public T get() throws InterruptedException, ExecutionException { ready(); return super.get(); }
            @Override public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
                ready(); return super.get(timeout, unit);
            }
        };
    }
    @Override public void shutdown() {
        drain();
        if (!getQueue().isEmpty()) throw new IllegalStateException("Pending deterministic work at shutdown");
        super.shutdown();
    }
    @Override public List<Runnable> shutdownNow() {
        if (!getQueue().isEmpty()) throw new IllegalStateException("Cannot discard deterministic work");
        return super.shutdownNow();
    }
}
