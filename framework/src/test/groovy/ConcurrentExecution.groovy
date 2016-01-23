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


import groovy.transform.CompileStatic

import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

@CompileStatic
class ConcurrentExecution {
    def static executeConcurrently(int threads, Closure closure) {
        ExecutorService executor = Executors.newFixedThreadPool(threads)
        CyclicBarrier barrier = new CyclicBarrier(threads)

        def futures = []
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(new Callable() {
                public def call() throws Exception {
                    barrier.await()
                    closure.call()
                }
            }))
        }

        def values = []
        for (Future future: futures) {
            try {
                def value = future.get()
                values << value
            } catch (ExecutionException e) {
                values << e.cause
            }
        }

        return values
    }
}
