/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 */
import org.moqui.Moqui
import org.moqui.context.ArtifactTarpitException
import org.moqui.context.ExecutionContext
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.impl.context.ExecutionContextFactoryImpl
import spock.lang.Shared
import spock.lang.Specification

class SecurityTarpitTests extends Specification {
    @Shared ExecutionContext ec
    @Shared ExecutionContextFactoryImpl ecfi
    @Shared Boolean prevServiceTarpit
    @Shared Boolean prevTransTarpit

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ecfi = ((org.moqui.impl.context.ExecutionContextImpl) ec).ecfi
        SecurityTestSupport.ensureUsers(ec)
        prevServiceTarpit = ecfi.artifactTypeTarpitEnabled.get(ArtifactExecutionInfo.AT_SERVICE)
        prevTransTarpit = ecfi.artifactTypeTarpitEnabled.get(ArtifactExecutionInfo.AT_XML_SCREEN_TRANS)
        ecfi.artifactTypeTarpitEnabled.put(ArtifactExecutionInfo.AT_SERVICE, Boolean.TRUE)
        ecfi.artifactTypeTarpitEnabled.put(ArtifactExecutionInfo.AT_XML_SCREEN_TRANS, Boolean.TRUE)
    }
    def cleanupSpec() {
        if (prevServiceTarpit != null) ecfi.artifactTypeTarpitEnabled.put(ArtifactExecutionInfo.AT_SERVICE, prevServiceTarpit)
        if (prevTransTarpit != null) ecfi.artifactTypeTarpitEnabled.put(ArtifactExecutionInfo.AT_XML_SCREEN_TRANS, prevTransTarpit)
        SecurityTestSupport.logout(ec)
        ec.destroy()
    }
    def setup() {
        SecurityTestSupport.logout(ec)
        ec.message.clearAll()
        clearTapLocks()
    }

    void clearTapLocks() {
        String uid = SecurityTestSupport.userIdForUsername(ec, SecurityTestSupport.TAP_USERNAME)
        SecurityTestSupport.withAuthzDisabled(ec) {
            if (uid) ec.entity.find("moqui.security.ArtifactTarpitLock").condition("userId", uid).deleteAll()
        }
        ec.cache.getCache("artifact.tarpit.hits")?.clear()
    }

    def "service tarpit locks after maxHitsCount"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.TAP_USERNAME, SecurityTestSupport.TAP_PASSWORD)
        // The call that exceeds maxHitsCount creates the lock; the next call throws.
        int warmup = SecurityTestSupport.TAP_MAX_HITS + 1
        Throwable locked = null
        for (int i = 0; i < warmup; i++) {
            ec.message.clearAll()
            ec.service.sync().name(SecurityTestSupport.TAP_SVC_NAME)
                    .parameters([preferenceKey: "secTap" + i, preferenceValue: "v"]).call()
        }
        try {
            ec.service.sync().name(SecurityTestSupport.TAP_SVC_NAME)
                    .parameters([preferenceKey: "secTapLock", preferenceValue: "v"]).call()
        } catch (Throwable t) { locked = t }
        then:
        locked instanceof ArtifactTarpitException
        cleanup:
        clearTapLocks()
        SecurityTestSupport.logout(ec)
    }

    def "tarpit duration window ignores hits older than maxHitsDuration"() {
        given:
        SecurityTestSupport.login(ec, SecurityTestSupport.TAP_USERNAME, SecurityTestSupport.TAP_PASSWORD)
        String uid = SecurityTestSupport.userIdForUsername(ec, SecurityTestSupport.TAP_USERNAME)
        String cacheKey = uid + "@AT_SERVICE:" + SecurityTestSupport.TAP_SVC_NAME
        def hits = new ArrayList<Long>()
        // one hit older than the 60s window
        hits.add(System.currentTimeMillis() - 120000L)
        ec.cache.getCache("artifact.tarpit.hits").put(cacheKey, hits)
        when:
        Throwable locked = null
        // TAP_MAX_HITS current-window calls should not lock if the stale hit is ignored
        for (int i = 0; i < SecurityTestSupport.TAP_MAX_HITS; i++) {
            ec.message.clearAll()
            try {
                ec.service.sync().name(SecurityTestSupport.TAP_SVC_NAME)
                        .parameters([preferenceKey: "secTapOld" + i, preferenceValue: "v"]).call()
            } catch (Throwable t) { locked = t; break }
        }
        then:
        locked == null
        cleanup:
        clearTapLocks()
        SecurityTestSupport.logout(ec)
    }
}
