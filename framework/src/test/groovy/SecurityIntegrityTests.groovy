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
        given:
        File dir = Files.createTempDirectory("sec-zipslip").toFile()
        File zipFile = new File(dir, "evil.zip")
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))
        zos.putNextEntry(new ZipEntry("../sec-zipslip-pwned.txt"))
        zos.write("pwned".getBytes("UTF-8"))
        zos.closeEntry()
        zos.close()
        File outside = new File(dir.parentFile, "sec-zipslip-pwned.txt")
        if (outside.exists()) outside.delete()

        when:
        Exception thrown = null
        try {
            new ExecutionContextFactoryImpl.ComponentInfo(zipFile.toURI().toString(), ecfi)
        } catch (Exception e) {
            thrown = e
        }
        then:
        !outside.exists()
        thrown != null
        cleanup:
        zipFile.delete()
        dir.delete()
        if (outside.exists()) outside.delete()
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
}
