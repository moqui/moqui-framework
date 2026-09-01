/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 */
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ExecutionContextFactoryImpl
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SecurityIntegrityTests extends Specification {
    @Shared ExecutionContext ec
    @Shared ExecutionContextFactoryImpl ecfi

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ecfi = ((org.moqui.impl.context.ExecutionContextImpl) ec).ecfi
        SecurityTestSupport.ensureUsers(ec)
    }
    def cleanupSpec() { SecurityTestSupport.logout(ec); ec.destroy() }

    def "component zip with path traversal is rejected"() {
        expect:
        zipSlipRejected("../sec-zipslip-pwned.txt", new File(System.getProperty("java.io.tmpdir"), "sec-zipslip-pwned.txt"))
    }

    def "component zip with absolute entry is rejected"() {
        expect:
        zipSlipRejected("/tmp/sec-zipslip-abs.txt", new File("/tmp/sec-zipslip-abs.txt"))
    }

    def "component zip with nested parent segments is rejected"() {
        expect:
        zipSlipRejected("foo/../../sec-zipslip-nested.txt",
                new File(System.getProperty("java.io.tmpdir"), "sec-zipslip-nested.txt"))
    }

    private boolean zipSlipRejected(String entryName, File outside) {
        File dir = Files.createTempDirectory("sec-zipslip").toFile()
        File zipFile = new File(dir, "evil.zip")
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))
        zos.putNextEntry(new ZipEntry(entryName))
        zos.write("pwned".getBytes("UTF-8"))
        zos.closeEntry()
        zos.close()
        if (outside.exists()) outside.delete()
        Exception thrown = null
        try {
            new ExecutionContextFactoryImpl.ComponentInfo(zipFile.toURI().toString(), ecfi)
        } catch (Exception e) {
            thrown = e
        }
        boolean ok = thrown != null && !outside.exists()
        zipFile.delete()
        dir.delete()
        if (outside.exists()) outside.delete()
        return ok
    }

    def "MNode XML parse does not expand external entities"() {
        given:
        File secret = java.nio.file.Files.createTempFile("sec-xxe", ".txt").toFile()
        secret.write("XXE_SECRET_MARKER")
        String xml = """<?xml version="1.0"?>
<!DOCTYPE foo [<!ENTITY xxe SYSTEM "${secret.toURI()}">]>
<root>&xxe;</root>
"""
        when:
        String text = null
        Exception thrown = null
        try {
            org.moqui.util.MNode node = org.moqui.util.MNode.parseText("xxe-test", xml)
            text = node != null ? ((node.getText() ?: "") + node.toString()) : ""
        } catch (Exception e) {
            thrown = e
        }
        then:
        (thrown != null) || (text != null && !text.contains("XXE_SECRET_MARKER"))
        cleanup:
        secret.delete()
    }

    def "create UserAccount is not allow-remote"() {
        when:
        def sd = ecfi.serviceFacade.getServiceDefinition("org.moqui.impl.UserServices.create#UserAccount")
        then:
        sd != null
        !sd.allowRemote
    }

    def "reset Password is anonymous-all and allow-remote"() {
        // Current design: remote password reset exists. Catalog documents this; proofs cover enumeration separately.
        when:
        def sd = ecfi.serviceFacade.getServiceDefinition("org.moqui.impl.UserServices.reset#Password")
        then:
        sd != null
        sd.allowRemote
    }

    def "ElFinder hashed path cannot walk above resourceRoot"() {
        given:
        def conn = new org.moqui.impl.util.ElFinderConnector(ec, "dbresource://mantle/content", "v0_")
        when:
        String locDots = conn.getLocation(conn.hash("../secret"))
        String locAbs = conn.getLocation(conn.hash("/etc/passwd"))
        String locOk = conn.getLocation(conn.hash("docs/readme.txt"))
        then:
        locDots == "dbresource://mantle/content"
        !locDots.contains("..")
        locAbs == "dbresource://mantle/content/etc/passwd"
        !locAbs.contains("..")
        locOk == "dbresource://mantle/content/docs/readme.txt"
    }

    def "create UserAccount extra currentPassword is not stored plaintext"() {
        given:
        String uname = "sec.mass." + System.currentTimeMillis()
        when:
        ec.message.clearAll()
        def created = ec.service.sync().name("org.moqui.impl.UserServices.create#UserAccount")
                .parameters([username: uname, newPassword: "SecMass1!!", newPasswordVerify: "SecMass1!!",
                             currentPassword: "plaintext-should-not-stick", passwordSalt: "evilsalt"])
                .disableAuthz().call()
        def ua = null
        SecurityTestSupport.withAuthzDisabled(ec) {
            ua = ec.entity.find("moqui.security.UserAccount").condition("username", uname).one()
        }
        then:
        created?.userId
        ua != null
        ua.currentPassword != "plaintext-should-not-stick"
        ua.passwordSalt != "evilsalt"
        when:
        SecurityTestSupport.logout(ec)
        boolean withNew = SecurityTestSupport.login(ec, uname, "SecMass1!!")
        SecurityTestSupport.logout(ec)
        boolean withExtra = ec.user.loginUser(uname, "plaintext-should-not-stick")
        then:
        withNew
        !withExtra
        cleanup:
        SecurityTestSupport.logout(ec)
        ec.message.clearAll()
        // Unique timestamped username; UserAccount is in a DataFeed so a raw delete is not worth it here.
    }
}
