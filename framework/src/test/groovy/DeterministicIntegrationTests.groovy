import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.context.ArtifactExecutionInfoImpl
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.util.CollectionUtilities
import org.moqui.util.DeterministicClock
import spock.lang.Specification

import java.sql.Timestamp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DeterministicIntegrationTests extends Specification {
    def 'real H2 lifecycle, clocks, FIFO async, single lane, shutdown and factory restart'() {
        given:
        File log = File.createTempFile('deterministic-integration-', '.jsonl')
        List<Map> events = Collections.synchronizedList([])
        HttpServer server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/transaction') { exchange ->
            Map event = (Map) new JsonSlurper().parse(exchange.requestBody)
            events.add(event)
            Map response = event.event == 'random' ?
                    [bytes:(0..<event.length).collect { String.format('%02x', (it + events.count { e -> e.event == 'random' && e.domain == event.domain } - 1) & 255) }.join('')] :
                    event.event == 'begin' ?
                    [operation_id:"op-${event.sequence}", time_epoch_ms:1760000000000L + event.sequence * 1000L] : [ok:true]
            byte[] body = JsonOutput.toJson(response).getBytes('UTF-8')
            exchange.sendResponseHeaders(200, body.length)
            exchange.responseBody.withCloseable { it.write(body) }
        }
        server.start()
        Map<String, String> properties = [MOQUI_DETERMINISTIC:'true',
                MOQUI_DETERMINISTIC_HOOK:"http://127.0.0.1:${server.address.port}/transaction".toString(),
                MOQUI_DETERMINISTIC_ENGINE:'java', MOQUI_DETERMINISTIC_RUN:'integration',
                MOQUI_DETERMINISTIC_LOG:log.absolutePath, scheduled_job_check_time:'0',
                entity_ds_url:'jdbc:h2:mem:deterministic;DB_CLOSE_DELAY=-1',
                entity_ds_user:'sa', entity_ds_password:'', entity_ds_crypt_pass:'',
                'bitronix.tm.serverId':'deterministic-test',
                'bitronix.tm.journal':'null']
        Map old = properties.collectEntries { key, value -> [(key):System.getProperty(key)] }
        properties.each { key, value -> System.setProperty(key, value) }
        File conf = new File(System.getProperty('moqui.runtime'), 'conf/MoquiDeterministicTestConf.xml')
        conf.text = getClass().getResource('/DeterministicConf.xml').text
        ExecutionContextFactoryImpl factory
        ExecutionContextImpl ec
        when:
        factory = new ExecutionContextFactoryImpl(System.getProperty('moqui.runtime'), 'conf/MoquiDeterministicTestConf.xml')
        factory.checkEmptyDb()
        ec = factory.getEci()
        ec.artifactExecution.disableAuthz()
        assert org.moqui.util.DeterministicRandom.uuid('vector-uuid').toString() == '00010203-0405-4607-8809-0a0b0c0d0e0f'
        assert org.moqui.util.DeterministicRandom.string('vector-string', 12) == 'AAECAwQFBgcI'
        assert org.moqui.util.DeterministicRandom.nextInt('vector-int', 999999) == 66051
        assert org.moqui.util.DeterministicRandom.unit('vector-unit') == (0x0001020304050607L >>> 11) * Math.scalb(1.0d, -53)
        def email = new org.moqui.util.DeterministicHtmlEmail()
        email.setHostName('localhost'); email.setFrom('sender@example.com'); email.addTo('recipient@example.com')
        email.setHtmlMsg('test-only; no transport')
        email.buildMimeMessage()
        email.mimeMessage.saveChanges()
        assert email.mimeMessage.messageID == '<000102030405060708090a0b@example.com>'
        email.mimeMessage.saveChanges()
        assert email.mimeMessage.messageID == '<000102030405060708090a0b@example.com>'
        assert events.count { it.event == 'random' && it.domain == 'mail-message-id' } == 1
        def tf = factory.transactionFacade
        // Direct entity calls must open REAL transactions, not reuse the enclosing synthetic EC clock.
        assert !tf.isTransactionInPlace()
        long ecTime = DeterministicClock.nowMillis()
        def implicit = ec.entity.makeValue('moqui.basic.EnumerationType')
                .setAll([enumTypeId:'IMPLICIT_TEST', description:'direct']).create()
        Map createEvent = events.findAll { it.event == 'begin' && it.artifact.name == 'moqui.basic.EnumerationType' && it.artifact.sub_type == 'create' }.last()
        assert implicit.getTimestamp('lastUpdatedStamp').time == 1760000000000L + createEvent.sequence * 1000L
        assert implicit.getTimestamp('lastUpdatedStamp').time != ecTime
        assert !tf.isTransactionInPlace()
        implicit.set('description', 'updated').store()
        assert !tf.isTransactionInPlace()
        assert implicit.refresh()
        assert ec.entity.fastFindOne('moqui.basic.EnumerationType', false, true, 'IMPLICIT_TEST').description == 'updated'
        assert ec.entity.find('moqui.basic.EnumerationType').condition('enumTypeId', 'IMPLICIT_TEST').count() == 1
        ec.entity.find('moqui.basic.EnumerationType').condition('enumTypeId', 'IMPLICIT_TEST').updateAll([description:'bulk'])
        assert ec.entity.find('moqui.basic.EnumerationType').condition('enumTypeId', 'IMPLICIT_TEST').useCache(false).one().description == 'bulk'
        ec.entity.find('moqui.basic.EnumerationType').condition('enumTypeId', 'IMPLICIT_TEST').deleteAll()
        try { ec.entity.getConnection('transactional'); assert false : 'raw autocommit must be rejected' }
        catch (IllegalStateException expected) { }
        try { ec.entity.find('moqui.basic.EnumerationType').iterator(); assert false : 'streaming needs an explicit transaction' }
        catch (IllegalStateException expected) { }
        ec.artifactExecution.pushInternal(new ArtifactExecutionInfoImpl('deterministic-test',
                ArtifactExecutionInfo.AT_SERVICE, ArtifactExecutionInfo.AUTHZA_ALL, 'sync'), false, true)
        assert tf.begin(30)
        long parent = DeterministicClock.nowMillis()
        int begins = events.count { it.event == 'begin' }
        assert !tf.begin(30)
        assert events.count { it.event == 'begin' } == begins
        assert ec.user.nowTimestamp.time == parent
        ec.user.setEffectiveTime(new Timestamp(1L))
        assert ec.user.nowTimestamp.time == parent
        assert ec.user.calendarSafe.timeInMillis == parent
        def row = ec.entity.makeValue('moqui.basic.EnumerationType').setAll([enumTypeId:'DETERMINISTIC_TEST', description:'one']).create()
        assert row.getTimestamp('lastUpdatedStamp').time == parent
        assert tf.txTimeoutRemainingMillis > 0 && tf.txTimeoutRemainingMillis <= 30000
        List<Long> times = []
        tf.runRequireNew(30, 'nested test', {
            times.add(DeterministicClock.nowMillis())
            assert ec.user.nowTimestamp.time == times[0]
        })
        assert times[0] != parent
        assert DeterministicClock.nowMillis() == parent
        List<String> order = []
        def future = ec.runAsync { order.add('first'); assert !tf.isTransactionInPlace() }
        ec.runAsync { order.add('second') }
        assert order.empty
        try { future.get(); assert false : 'waiting inside a tx must fail' }
        catch (IllegalStateException expected) { }
        def asyncService = ec.service.async().name('update#moqui.basic.EnumerationType')
                .parameters([enumTypeId:'DETERMINISTIC_TEST', description:'async']).callFuture()
        assert !asyncService.done
        tf.commit()
        assert asyncService.done
        asyncService.get()
        assert ec.entity.find('moqui.basic.EnumerationType').condition('enumTypeId', 'DETERMINISTIC_TEST').one().description == 'async'
        assert order == ['first', 'second']
        assert future.done
        assert factory.getEci().is(ec)
        assert tf.begin(30)
        tf.setRollbackOnly('test rollback-only', null)
        tf.commit()
        assert tf.begin(30)
        long rollbackTime = DeterministicClock.nowMillis()
        def rolledBackRow = ec.entity.find('moqui.basic.EnumerationType').condition('enumTypeId', 'DETERMINISTIC_TEST').useCache(false).one()
        rolledBackRow.set('description', 'rollback').update()
        assert rolledBackRow.getTimestamp('lastUpdatedStamp').time == rollbackTime
        tf.rollback('explicit test rollback', null)
        assert ec.entity.find('moqui.basic.EnumerationType').condition('enumTypeId', 'DETERMINISTIC_TEST').one().description == 'async'
        def utField = tf.class.getDeclaredField('ut')
        utField.accessible = true
        def realUt = utField.get(tf)
        utField.set(tf, Stub(jakarta.transaction.UserTransaction) {
            getStatus() >> jakarta.transaction.Status.STATUS_NO_TRANSACTION
            begin() >> { throw new jakarta.transaction.SystemException('test begin failure') }
        })
        try {
            try { tf.begin(null); assert false : 'begin must fail' }
            catch (org.moqui.context.TransactionException expected) { }
        } finally { utField.set(tf, realUt) }
        ec.artifactExecution.pop()
        ec.destroy()
        ec = null
        boolean missingTimeFailed = false
        try { DeterministicClock.nowMillis() }
        catch (IllegalStateException expected) { missingTimeFailed = true }
        assert missingTimeFailed
        // A second application thread cannot acquire an EC while the first owns its nontransactional lane.
        ec = factory.getEci()
        CountDownLatch started = new CountDownLatch(1), acquired = new CountDownLatch(1)
        Thread other = Thread.start {
            started.countDown()
            def otherEc = factory.getEci()
            acquired.countDown()
            otherEc.destroy()
        }
        assert started.await(5, TimeUnit.SECONDS)
        assert !acquired.await(100, TimeUnit.MILLISECONDS)
        ec.destroy(); ec = null
        assert acquired.await(10, TimeUnit.SECONDS)
        other.join(10000)
        ec = factory.getEci()
        ec.artifactExecution.disableAuthz()
        // Exercise a rollover without sleeps; collection must retain all hits and both bins.
        long statsStart = System.currentTimeMillis()
        factory.countArtifactHit(ArtifactExecutionInfo.AT_SERVICE, 'sync', 'statistics-persistence-test',
                [password:'DO_NOT_PERSIST', label:'visible'], statsStart, 10d, 1L)
        factory.countArtifactHit(ArtifactExecutionInfo.AT_SERVICE, 'sync', 'statistics-persistence-test',
                null, statsStart + 1L, 20d, 2L)
        factory.countArtifactHit(ArtifactExecutionInfo.AT_SERVICE, 'sync', 'statistics-persistence-test',
                null, statsStart + 900001L, 30d, 3L)
        assert factory.deferredHitInfoQueue.size() >= 3
        assert ec.entity.find('moqui.server.ArtifactHit').condition('artifactName', 'statistics-persistence-test').count() == 0
        assert ec.entity.find('moqui.server.ArtifactHitBin').condition('artifactName', 'statistics-persistence-test').count() == 0
        ec.destroy(); ec = null
        factory.destroy()
        factory = null
        long lastSequence = events.findAll { it.event == 'begin' }.last().sequence
        int linesBeforeRestart = log.readLines().size()
        factory = new ExecutionContextFactoryImpl(System.getProperty('moqui.runtime'), 'conf/MoquiDeterministicTestConf.xml')
        ec = factory.getEci()
        ec.artifactExecution.disableAuthz()
        def savedHits = ec.entity.find('moqui.server.ArtifactHit').condition('artifactName', 'statistics-persistence-test').list()
        def savedBins = ec.entity.find('moqui.server.ArtifactHitBin').condition('artifactName', 'statistics-persistence-test').list()
        assert savedHits.size() == 3
        assert savedHits*.runningTimeMillis.sort() == [10, 20, 30]
        assert savedHits*.outputSize.sort() == [1, 2, 3]
        assert savedHits.any { it.parameterString == 'label=visible' }
        assert !savedHits.toString().contains('DO_NOT_PERSIST')
        assert savedBins.size() == 2
        assert savedBins*.hitCount.sort() == [1, 2]
        assert savedBins*.totalTimeMillis.sum() == 60
        // The real pushed/popped service above must also be counted and persisted, not just synthetic test hits.
        assert ec.entity.find('moqui.server.ArtifactHit').condition('artifactName', 'deterministic-test').count() == 1
        def actualBins = ec.entity.find('moqui.server.ArtifactHitBin').condition('artifactName', 'deterministic-test').list()
        assert actualBins*.hitCount.sum() == 1
        ec.destroy(); ec = null
        factory.destroy(); factory = null
        then:
        log.readLines().size() > linesBeforeRestart
        events.findAll { it.event == 'begin' && it.sequence > lastSequence }.size() > 0
        events.findAll { it.event == 'begin' }.any { it.artifact == [type:'AT_SERVICE', sub_type:'sync', name:'deterministic-test'] }
        events.findAll { it.event == 'begin' }.any { it.artifact.name == 'update#moqui.basic.EnumerationType' && it.artifact.type == 'AT_SERVICE' }
        events.findAll { it.event == 'complete' }.any { it.outcome == 'rolled_back' }
        events.findAll { it.event == 'complete' }.any { it.outcome == 'begin_failed' }
        events.findAll { it.event == 'begin' }*.sequence.toSet().size() == events.count { it.event == 'begin' }
        events.count { it.event == 'begin' } == events.count { it.event == 'complete' }
        cleanup:
        try { ec?.destroy(); factory?.destroy() }
        finally {
            old?.each { key, value -> if (value == null) System.clearProperty(key) else System.setProperty(key, value.toString()) }
            server?.stop(0)
            conf?.delete()
            // Keep the concrete protocol evidence in the private worktree's test report directory.
            if (log != null) {
                File evidence = new File('build/reports/tests/deterministic-integration.jsonl')
                evidence.parentFile.mkdirs(); evidence.text = log.text
                log.delete()
            }
        }
    }
}
