package org.moqui.impl.context

import spock.lang.Specification

/** Change 1: supplied epochs cannot expire a transaction or extend its actual duration. */
class TestDataClockTransactionTests extends Specification {
    def "transaction timeout uses monotonic elapsed time independently of supplied epoch"() {
        given:
        def frame = new ContextJavaUtil.TxStackInfo(null)
        frame.transactionTimeout = 60
        frame.transactionBeginNanos = System.nanoTime() - 2_000_000_000L
        frame.transactionBeginStartTime = 1000L
        expect:
        frame.timeoutRemainingMillis() > 55000L
        frame.timeoutRemainingMillis() <= 58000L
        when:
        frame.transactionBeginStartTime = 253402300799999L
        then:
        frame.timeoutRemainingMillis() > 55000L
        frame.timeoutRemainingMillis() <= 58000L
        when:
        frame.transactionBeginNanos = System.nanoTime() - 61_000_000_000L
        then:
        frame.timeoutRemainingMillis() < 0L
    }
}
