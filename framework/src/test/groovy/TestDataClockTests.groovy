package org.moqui.util

import spock.lang.Specification
import java.nio.file.Files
import java.nio.file.Path

/** Change 1: file-clock rules, including invalid updates after a valid assignment. */
class TestDataClockTests extends Specification {
    def "supplied clock advances and never falls back after invalid file updates"() {
        setup:
        Path file = Files.createTempFile("moqui-data-clock", ".txt")
        when:
        Files.writeString(file, "1700000000000\n")
        long first = TestDataClock.readAssignment(file)
        Files.writeString(file, "1700000000001\n")
        then:
        first == 1700000000000L
        TestDataClock.readAssignment(file) == first + 1
        when:
        Files.writeString(file, "invalid")
        TestDataClock.fromSetting(file.toString())
        then:
        thrown(IllegalStateException)
        cleanup:
        Files.deleteIfExists(file)
    }

    def "disabled clock uses real time and malformed assignments fail"() {
        setup:
        Path file = Files.createTempFile("moqui-data-clock", ".txt")
        long before = System.currentTimeMillis()
        long actual = TestDataClock.fromSetting(null)
        expect:
        actual >= before && actual <= System.currentTimeMillis()
        ["", "0", "-1", "1.5", " 12", "12\r\n", "253402300800000", "1" * 40].every { bad ->
            Files.writeString(file, bad)
            try { TestDataClock.readAssignment(file); return false } catch (IOException expected) { return true }
        }
        cleanup:
        Files.deleteIfExists(file)
    }
}
