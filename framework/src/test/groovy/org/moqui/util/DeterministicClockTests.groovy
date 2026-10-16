package org.moqui.util

import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.Unroll

class DeterministicClockTests extends Specification {
    HttpServer server
    File log
    List<Map> requests = Collections.synchronizedList([])
    String beginBody = '{"operation_id":"op-1","time_epoch_ms":1760000000000}'
    String completeBody = '{"ok":true}'
    int status = 200
    boolean stallBody = false
    def releaseBody = new java.util.concurrent.CountDownLatch(1)

    def setup() {
        log = File.createTempFile('deterministic-protocol-', '.jsonl')
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/transaction') { exchange ->
            Map request = (Map) new JsonSlurper().parse(exchange.requestBody)
            requests.add(request)
            byte[] body = (request.event == 'begin' ? beginBody : completeBody).getBytes('UTF-8')
            exchange.sendResponseHeaders(status, body.length)
            if (stallBody) releaseBody.await(25, java.util.concurrent.TimeUnit.SECONDS)
            exchange.responseBody.withCloseable { it.write(body) }
        }
        server.start()
    }
    def cleanup() { releaseBody.countDown(); server?.stop(0); log?.delete() }
    Map<String, String> settings() {
        [MOQUI_DETERMINISTIC_HOOK: "http://127.0.0.1:${server.address.port}/transaction".toString(),
         MOQUI_DETERMINISTIC_ENGINE:'java', MOQUI_DETERMINISTIC_RUN:'test', MOQUI_DETERMINISTIC_LOG:log.absolutePath]
    }
    def 'metadata is whitelisted and nested operations restore their scope'() {
        given:
        def clock = new DeterministicClock(settings())
        def metadata = [type:'AT_SERVICE', sub_type:'sync', name:'example', password:'SECRET']
        when:
        def parent = clock.begin([metadata], true)
        parent.suspended = true
        def child = clock.begin([], true)
        child.complete('rolled_back')
        parent.suspended = false
        parent.complete('committed')
        then:
        requests*.event == ['begin', 'begin', 'complete', 'complete']
        requests[0].parent_sequence == null
        requests[1].parent_sequence == requests[0].sequence
        requests[0].artifact == [type:'AT_SERVICE', sub_type:'sync', name:'example']
        requests[1].artifact == DeterministicClock.bootstrapArtifact()
        !log.text.contains('SECRET')
        log.readLines().size() == 4
        !clock.hasTime()
        !clock.hasTransaction()
    }
    def 'append and sequence survive another clock owner'() {
        when:
        def a = new DeterministicClock(settings()).begin([], false)
        a.complete('committed')
        def b = new DeterministicClock(settings()).begin([], false)
        b.complete('begin_failed')
        then:
        b.sequence > a.sequence
        log.readLines().size() == 4
        requests.last().outcome == 'begin_failed'
    }
    @Unroll
    def 'rejects invalid begin response #body'() {
        given:
        beginBody = body
        def clock = new DeterministicClock(settings())
        when:
        clock.begin([], true)
        then:
        thrown(IllegalStateException)
        !clock.hasTime()
        log.text == ''
        when:
        clock.begin([], true)
        then:
        thrown(IllegalStateException)
        requests.size() == 1
        where:
        body << ['{}', '{"operation_id":"x","time_epoch_ms":"1"}',
                 '{"operation_id":"x","time_epoch_ms":1.5}', '{"operation_id":"","time_epoch_ms":1}',
                 '{"operation_id":"x","time_epoch_ms":9223372036854775808}',
                 '{"operation_id":"x","time_epoch_ms":1} {}',
                 '{"operation_id":"x","operation_id":"y","time_epoch_ms":1}', 'not json']
    }
    def 'strict completion acknowledgement and HTTP status'() {
        given:
        def clock = new DeterministicClock(settings())
        def operation = clock.begin([], true)
        completeBody = '{"ok":false}'
        when:
        operation.complete('committed')
        then:
        thrown(IllegalStateException)
        !clock.hasTime()
        log.readLines().size() == 2 // actual DB outcome is retained
        when:
        status = 503
        new DeterministicClock(settings()).begin([], true)
        then:
        thrown(IllegalStateException)
    }
    def 'stalled response body is bounded by a real deadline, not assigned time'() {
        given:
        stallBody = true
        def clock = new DeterministicClock(settings() + [MOQUI_DETERMINISTIC_TIMEOUT_SECONDS:'2'])
        long start = System.nanoTime()
        when:
        clock.begin([], true)
        then:
        thrown(IllegalStateException)
        (System.nanoTime() - start) / 1000000L >= 1800
        (System.nanoTime() - start) / 1000000L < 8000
        !clock.hasTime()
        log.text.empty
    }
    def 'required configuration is validated before requesting work'() {
        when:
        new DeterministicClock(settings() + [MOQUI_DETERMINISTIC_ENGINE:'rust'])
        then:
        thrown(IllegalArgumentException)
        when:
        new DeterministicClock(settings() + [MOQUI_DETERMINISTIC_LOG:'relative.log'])
        then:
        thrown(IllegalArgumentException)
        when:
        new DeterministicClock(settings() + [MOQUI_DETERMINISTIC_RUN:''])
        then:
        thrown(IllegalArgumentException)
        requests.empty
    }
}
