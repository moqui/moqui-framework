import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.util.DeterministicClock
import spock.lang.Specification
import java.sql.Timestamp
import java.util.concurrent.TimeUnit

class DeterministicDisabledTests extends Specification {
    def 'disabled mode preserves wall time, effective time, async threads and require-new threads'() {
        given:
        Map properties = [MOQUI_DETERMINISTIC:'false', entity_ds_url:'jdbc:h2:mem:deterministic-off;DB_CLOSE_DELAY=-1',
                          entity_ds_user:'sa', entity_ds_password:'', entity_ds_crypt_pass:'',
                          'bitronix.tm.serverId':'deterministic-off-test', 'bitronix.tm.journal':'null']
        Map old = properties.collectEntries { key, value -> [(key):System.getProperty(key)] }
        properties.each { key, value -> System.setProperty(key, value) }
        File conf = new File(System.getProperty('moqui.runtime'), 'conf/MoquiDeterministicOffTestConf.xml')
        conf.text = getClass().getResource('/DeterministicConf.xml').text
        ExecutionContextFactoryImpl factory
        def ec
        when:
        factory = new ExecutionContextFactoryImpl(System.getProperty('moqui.runtime'), 'conf/' + conf.name)
        ec = factory.getEci()
        ec.artifactExecution.disableAuthz()
        long threadId = Thread.currentThread().threadId()
        assert ec.deterministicOperation == null
        assert org.moqui.util.DeterministicRandom.uuid('normal').version() == 4
        assert org.moqui.util.DeterministicRandom.uuid('normal') != org.moqui.util.DeterministicRandom.uuid('normal')
        assert org.moqui.util.DeterministicRandom.bytes('normal', 16).length == 16
        assert Math.abs(DeterministicClock.nowMillis() - System.currentTimeMillis()) < 1000
        ec.user.setEffectiveTime(new Timestamp(1000L))
        assert ec.user.nowTimestamp.time == 1000L
        assert ec.transaction.begin(30)
        List<Long> workerThreads = Collections.synchronizedList([])
        def worker = ec.runAsync { workerThreads.add(Thread.currentThread().threadId()) }
        worker.get(10, TimeUnit.SECONDS) // real async runs even while caller holds a transaction
        List<Long> nestedThreads = []
        ec.transaction.runRequireNew(30, 'normal nested', { nestedThreads.add(Thread.currentThread().threadId()) })
        ec.transaction.commit()
        then:
        nestedThreads.size() == 1
        nestedThreads[0] != threadId
        workerThreads.size() == 1
        workerThreads[0] != threadId
        cleanup:
        try { ec?.destroy(); factory?.destroy() }
        finally {
            old?.each { key, value -> if (value == null) System.clearProperty(key) else System.setProperty(key, value.toString()) }
            conf?.delete()
        }
    }
}
