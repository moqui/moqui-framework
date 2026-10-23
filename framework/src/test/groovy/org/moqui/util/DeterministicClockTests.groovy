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
    String randomBody = '{"bytes":"000102030405060708090a0b0c0d0e0f"}'
    int status = 200
    boolean stallBody = false
    def releaseBody = new java.util.concurrent.CountDownLatch(1)

    def setup() {
        log = File.createTempFile('deterministic-protocol-', '.jsonl')
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/transaction') { exchange ->
            Map request = (Map) new JsonSlurper().parse(exchange.requestBody)
            requests.add(request)
            byte[] body = (request.event == 'random' ? randomBody : request.event == 'begin' ? beginBody : completeBody).getBytes('UTF-8')
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
    def 'Elastic startup defaults to enabled'() {
        given:
        String old = System.getProperty('MOQUI_DETERMINISTIC_ELASTIC')
        System.clearProperty('MOQUI_DETERMINISTIC_ELASTIC')
        expect:
        DeterministicClock.isElasticEnabled() == (System.getenv('MOQUI_DETERMINISTIC_ELASTIC') != 'false')
        cleanup:
        if (old != null) System.setProperty('MOQUI_DETERMINISTIC_ELASTIC', old)
    }
    @Unroll
    def 'Elastic startup flag accepts #flag'() {
        given:
        String old = System.getProperty('MOQUI_DETERMINISTIC_ELASTIC')
        System.setProperty('MOQUI_DETERMINISTIC_ELASTIC', flag)
        expect:
        DeterministicClock.isElasticEnabled() == enabled
        cleanup:
        if (old == null) System.clearProperty('MOQUI_DETERMINISTIC_ELASTIC')
        else System.setProperty('MOQUI_DETERMINISTIC_ELASTIC', old)
        where:
        flag    | enabled
        'true'  | true
        'false' | false
    }
    def 'Elastic startup rejects a malformed opt-in'() {
        given:
        String old = System.getProperty('MOQUI_DETERMINISTIC_ELASTIC')
        System.setProperty('MOQUI_DETERMINISTIC_ELASTIC', 'yes')
        when:
        DeterministicClock.isElasticEnabled()
        then:
        thrown(IllegalArgumentException)
        cleanup:
        if (old == null) System.clearProperty('MOQUI_DETERMINISTIC_ELASTIC')
        else System.setProperty('MOQUI_DETERMINISTIC_ELASTIC', old)
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
        log.readLines().collect { new JsonSlurper().parseText(it) }.count { it.event != 'trace' } == 4
        !clock.hasTime()
        !clock.hasTransaction()
    }
    def 'a thread the lane holder waits on runs its operations in the holder lane'() {
        given:
        def clock = new DeterministicClock(settings())
        Throwable thrown = null
        def child = new Thread({
            try { clock.begin([], false).complete('committed') } catch (Throwable t) { thrown = t }
        } as Runnable)
        when:
        def parent = clock.begin([], false)
        DeterministicClock.runAndJoin(child)
        parent.complete('committed')
        then:
        thrown == null
        requests*.event == ['begin', 'begin', 'complete', 'complete']
        requests[1].parent_sequence == null
        !DeterministicClock.laneHeldByCurrentThread()
    }
    def 'append and sequence survive another clock owner'() {
        when:
        def a = new DeterministicClock(settings()).begin([], false)
        a.complete('committed')
        def b = new DeterministicClock(settings()).begin([], false)
        b.complete('begin_failed')
        then:
        b.sequence > a.sequence
        log.readLines().collect { new JsonSlurper().parseText(it) }.count { it.event != 'trace' } == 4
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
        new JsonSlurper().parseText(log.readLines().last()).outcome == 'requested'
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
        log.readLines().collect { new JsonSlurper().parseText(it) }.count { it.event != 'trace' } == 2 // actual DB outcome is retained
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
        new JsonSlurper().parseText(log.readLines().last()).outcome == 'requested'
    }
    def 'operation completion synchronously calls the Python comparison hook'() {
        given:
        def clock = new DeterministicClock(settings() + [MOQUI_DETERMINISTIC_OPERATIONS:'true'])
        def operation = clock.begin([], true)
        when:
        clock.observe('AT_ENTITY', 'list', 'moqui.example.Thing', 'exit')
        operation.complete('committed')
        then:
        requests*.event == ['begin', 'operation', 'complete']
        requests[1].kind == 'AT_ENTITY'
        requests[1].name == 'moqui.example.Thing'
        requests[1].transaction_sequence == operation.sequence
        requests[1].outcome == 'exit'
    }

    def 'random bytes use the coordinator without manufacturing a transaction or local fallback'() {
        given:
        def clock = new DeterministicClock(settings())
        when:
        byte[] bytes = clock.randomBytes('entity:moqui.server.Visitor', 16)
        then:
        HexFormat.of().formatHex(bytes) == '000102030405060708090a0b0c0d0e0f'
        requests == [[version:1, run_id:'test', engine:'java', event:'random',
                      domain:'entity:moqui.server.Visitor', length:16]]
        !clock.hasTime()
        when:
        randomBody = '{"bytes":"bad"}'
        clock.randomBytes('uuid', 16)
        then:
        thrown(IllegalStateException)
        when:
        clock.randomBytes('uuid', 16)
        then:
        thrown(IllegalStateException)
        requests.size() == 2
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
