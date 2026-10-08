/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.service.ServiceFacadeImpl
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Timeout

import java.lang.reflect.Field
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Timeout(180)
class VirtualThreadStressTests extends Specification {
    @Shared ExecutionContext ec
    @Shared ExecutionContextFactoryImpl ecfi
    @Shared ServiceFacadeImpl sfi
    @Shared ThreadPoolExecutor workerPool
    @Shared ThreadPoolExecutor jobWorkerPool
    @Shared ThreadPoolExecutor entityStatementExecutor

    def setupSpec() {
        ecfi = (ExecutionContextFactoryImpl) Moqui.getExecutionContextFactory()
        ec = Moqui.getExecutionContext()
        sfi = ecfi.serviceFacade
        workerPool = (ThreadPoolExecutor) ecfi.workerPool
        jobWorkerPool = (ThreadPoolExecutor) sfi.jobWorkerPool
        entityStatementExecutor = getField(ecfi.entityFacade, "statementExecutor", ThreadPoolExecutor)
    }

    def cleanupSpec() {
        if (ec != null) ec.destroy()
    }

    def setup() {
        ec.artifactExecution.disableAuthz()
        ec.message.clearAll()
    }

    def cleanup() {
        try {
            ec.message.clearAll()
        } finally {
            ec.artifactExecution.enableAuthz()
        }

        assert ecfi.waitWorkerPoolEmpty(120)
        assert waitExecutorIdle(workerPool, 120)
        assert waitExecutorIdle(jobWorkerPool, 120)
        assert waitExecutorIdle(entityStatementExecutor, 120)
    }

    def "worker pool runs burst load on virtual threads"() {
        given:
        int taskCount = Math.max(workerPool.corePoolSize * 4, 48)

        when:
        Map burst = runBurst(workerPool, taskCount) { int taskNumber ->
            ExecutionContext localEc = Moqui.getExecutionContext()
            try {
                localEc.artifactExecution.disableAuthz()
                long enumCount = localEc.entity.find("moqui.basic.Enumeration")
                    .useCache(false).count()
                Thread.sleep(120)
                return [taskNumber: taskNumber, virtual: Thread.currentThread().isVirtual(),
                    name: Thread.currentThread().getName(), enumCount: enumCount]
            } finally {
                localEc.artifactExecution.enableAuthz()
                localEc.destroy()
            }
        }

        then:
        !ec.message.hasError()
        burst.results.size() == taskCount
        burst.results.every { ((Boolean) it.virtual) }
        burst.results.every { ((String) it.name).startsWith("MoquiWorker-") }
        burst.results.every { ((Number) it.enumCount).longValue() >= 0L }
        burst.maxRunning >= minimumExpectedConcurrency(workerPool.corePoolSize)
    }

    def "async services complete cleanly under worker pool load"() {
        given:
        int taskCount = Math.max(workerPool.corePoolSize * 4, 64)
        List<Future<Map<String, Object>>> futures = []

        when:
        for (int i = 0; i < taskCount; i++) {
            futures.add(ec.service.async()
                .name("org.moqui.impl.ServerServices.get#VisitClientIpData")
                .parameters([visitId: "VT_ASYNC_${i}".toString()])
                .callFuture())
        }
        List<Map<String, Object>> results = futures.collect {
            (Map<String, Object>) it.get(60, TimeUnit.SECONDS)
        }

        then:
        !ec.message.hasError()
        results.size() == taskCount
        results.every { it instanceof Map }
        ecfi.waitWorkerPoolEmpty(120)
    }

    def "service job pool scales past core size on virtual threads"() {
        given:
        int queueCapacity = jobWorkerPool.queue.size() + jobWorkerPool.queue.remainingCapacity()
        int taskCount = Math.max(queueCapacity + jobWorkerPool.maximumPoolSize - 1, 32)

        when:
        Map burst = runBurst(jobWorkerPool, taskCount) { int taskNumber ->
            ExecutionContext localEc = Moqui.getExecutionContext()
            try {
                localEc.artifactExecution.disableAuthz()
                Map result = localEc.service.sync()
                    .name("org.moqui.impl.ServerServices.get#VisitClientIpData")
                    .parameters([visitId: "VT_JOB_${taskNumber}".toString()])
                    .call()
                Thread.sleep(160)
                return [taskNumber: taskNumber, virtual: Thread.currentThread().isVirtual(),
                    name: Thread.currentThread().getName(), resultMap: result]
            } finally {
                localEc.artifactExecution.enableAuthz()
                localEc.destroy()
            }
        }

        then:
        !ec.message.hasError()
        burst.results.size() == taskCount
        burst.results.every { ((Boolean) it.virtual) }
        burst.results.every { ((String) it.name).startsWith("MoquiJob-") }
        burst.results.every { it.resultMap instanceof Map }
        if (jobWorkerPool.maximumPoolSize > jobWorkerPool.corePoolSize) {
            assert burst.maxRunning > jobWorkerPool.corePoolSize
        } else {
            assert burst.maxRunning >= jobWorkerPool.corePoolSize
        }
    }

    def "entity statement pool handles burst load on virtual threads"() {
        given:
        int taskCount = Math.max(entityStatementExecutor.corePoolSize * 6, 30)

        when:
        Map burst = runBurst(entityStatementExecutor, taskCount) { int taskNumber ->
            ExecutionContext localEc = Moqui.getExecutionContext()
            boolean beganTx = false
            try {
                localEc.artifactExecution.disableAuthz()
                beganTx = localEc.transaction.begin(60)
                long enumCount = localEc.entity.find("moqui.basic.Enumeration")
                    .useCache(false).count()
                Thread.sleep(120)
                return [taskNumber: taskNumber, virtual: Thread.currentThread().isVirtual(),
                    name: Thread.currentThread().getName(), enumCount: enumCount]
            } finally {
                localEc.transaction.commit(beganTx)
                localEc.artifactExecution.enableAuthz()
                localEc.destroy()
            }
        }

        then:
        !ec.message.hasError()
        burst.results.size() == taskCount
        burst.results.every { ((Boolean) it.virtual) }
        burst.results.every { ((String) it.name).startsWith("MoquiEntityExec-") }
        burst.results.every { ((Number) it.enumCount).longValue() >= 0L }
        burst.maxRunning >= minimumExpectedConcurrency(entityStatementExecutor.corePoolSize)
    }

    private static Map runBurst(ThreadPoolExecutor executor, int taskCount, Closure<Map> work) {
        CountDownLatch startLatch = new CountDownLatch(1)
        AtomicInteger runningNow = new AtomicInteger(0)
        AtomicInteger maxRunning = new AtomicInteger(0)
        List<Future<Map>> futures = new ArrayList<>(taskCount)

        try {
            for (int i = 0; i < taskCount; i++) {
                final int taskNumber = i
                futures.add(executor.submit({
                    assert startLatch.await(15, TimeUnit.SECONDS)
                    int current = runningNow.incrementAndGet()
                    updateMax(maxRunning, current)
                    try {
                        return work.call(taskNumber)
                    } finally {
                        runningNow.decrementAndGet()
                    }
                } as Callable<Map>))
            }
        } finally {
            startLatch.countDown()
        }
        List<Map> results = futures.collect { Future<Map> future -> future.get(90, TimeUnit.SECONDS) }
        return [results: results, maxRunning: maxRunning.get()]
    }

    private static void updateMax(AtomicInteger currentMax, int candidate) {
        while (true) {
            int previous = currentMax.get()
            if (candidate <= previous) return
            if (currentMax.compareAndSet(previous, candidate)) return
        }
    }

    private static int minimumExpectedConcurrency(int corePoolSize) {
        return Math.max(2, Math.min(corePoolSize, 6))
    }

    private static boolean waitExecutorIdle(ThreadPoolExecutor executor, int retryLimit) {
        int count = 0
        while (count < retryLimit && (executor.activeCount > 0 || executor.queue.size() > 0)) {
            Thread.sleep(100)
            count++
        }
        return executor.activeCount == 0 && executor.queue.size() == 0
    }

    private static <T> T getField(Object target, String fieldName, Class<T> type) {
        Field field = target.getClass().getDeclaredField(fieldName)
        field.setAccessible(true)
        return type.cast(field.get(target))
    }
}
