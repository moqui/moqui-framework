/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 */
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.WebFacadeImpl
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

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

    def "component zip with nested parent segments is rejected"() {
        expect:
        zipSlipRejected("foo/../../sec-zipslip-nested.txt",
                new File(System.getProperty("java.io.tmpdir"), "sec-zipslip-nested.txt"))
    }

    def "component zip with absolute entry stays under the target directory"() {
        // new File(targetDir, "/abs/path") resolves under targetDir, so this proves the entry is contained
        // rather than that it is rejected; the file must never land at the absolute path.
        expect:
        zipSlipRejected("/tmp/sec-zipslip-abs.txt", new File("/tmp/sec-zipslip-abs.txt"))
    }

    /** Positive control: a well-formed component zip expands and is accepted.
     * Without this, an implementation that refused every zip would pass all of the zip-slip proofs. */
    def "component zip with contained entries expands"() {
        given:
        File dir = Files.createTempDirectory("sec-zipok").toFile()
        File zipFile = new File(dir, "goodcomp.zip")
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))
        zos.putNextEntry(new ZipEntry("goodcomp/component.xml"))
        zos.write('<component name="goodcomp" version="1.0.0"/>'.getBytes("UTF-8"))
        zos.closeEntry()
        zos.putNextEntry(new ZipEntry("goodcomp/data.txt"))
        zos.write("contained".getBytes("UTF-8"))
        zos.closeEntry()
        zos.close()
        when:
        def compInfo = new ExecutionContextFactoryImpl.ComponentInfo(zipFile.toURI().toString(), ecfi)
        File expanded = new File(dir, "goodcomp/data.txt")
        then:
        noExceptionThrown()
        compInfo != null
        expanded.exists()
        expanded.getText("UTF-8") == "contained"
        cleanup:
        expanded.delete()
        new File(dir, "goodcomp/component.xml").delete()
        new File(dir, "goodcomp").delete()
        zipFile.delete()
        dir.delete()
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
        // The file not being written outside is the real assertion. Require the zip-slip guard message too when a
        // traversal was attempted: ComponentInfo also throws later ("Could not find component directory") for any
        // zip that does not contain a component directory, so "it threw" alone proves nothing.
        boolean containsOutside = outside.exists()
        boolean ok = !containsOutside
        if (ok && entryName.contains("..")) {
            ok = thrown != null && thrown instanceof IllegalArgumentException &&
                    thrown.message != null && thrown.message.contains("Zip entry")
        }
        zipFile.delete()
        dir.delete()
        if (outside.exists()) outside.delete()
        return ok
    }

    def "MNode parseText does not expand external entities"() {
        given:
        File secret = java.nio.file.Files.createTempFile("sec-xxe", ".txt").toFile()
        secret.write("XXE_SECRET_MARKER")
        String xml = """<?xml version="1.0"?>
<!DOCTYPE foo [<!ENTITY xxe SYSTEM "${secret.toURI()}">]>
<root>&xxe;</root>
"""
        when:
        // must parse successfully; "it threw" would also pass for a parser that rejects all DOCTYPEs
        org.moqui.util.MNode node = org.moqui.util.MNode.parseText("xxe-test", xml)
        String text = ((node?.getText() ?: "") + (node != null ? node.toString() : ""))
        then:
        noExceptionThrown()
        node != null
        node.attribute("nonexistent") == null
        !text.contains("XXE_SECRET_MARKER")
        cleanup:
        secret.delete()
    }

    def "MNode parse of a stream does not expand external entities"() {
        given:
        File secret = java.nio.file.Files.createTempFile("sec-xxe", ".txt").toFile()
        secret.write("XXE_SECRET_MARKER")
        File xmlFile = java.nio.file.Files.createTempFile("sec-xxe-doc", ".xml").toFile()
        // NOTE: an external entity reference inside an attribute value is rejected outright by the parser
        // ("The external entity reference "&xxe;" is not permitted in an attribute value"), so only element
        // content is exercised here.
        xmlFile.setText("""<?xml version="1.0"?>
<!DOCTYPE foo [<!ENTITY xxe SYSTEM "${secret.toURI()}">]>
<root><a>&xxe;</a><b>plain</b></root>
""", "UTF-8")
        when:
        // this is the path real config and data loading uses
        org.moqui.util.MNode node = org.moqui.util.MNode.parse(xmlFile.getPath(), new FileInputStream(xmlFile))
        String text = node != null ? node.toString() : ""
        then:
        noExceptionThrown()
        node != null
        node.first("a") != null
        !text.contains("XXE_SECRET_MARKER")
        cleanup:
        xmlFile.delete()
        secret.delete()
    }

    def "MNode parse does not load an external DTD subset"() {
        given:
        File secret = java.nio.file.Files.createTempFile("sec-xxe", ".txt").toFile()
        secret.write("XXE_SECRET_MARKER")
        File dtd = java.nio.file.Files.createTempFile("sec-xxe-dtd", ".dtd").toFile()
        dtd.setText('<!ENTITY xxe SYSTEM "' + secret.toURI() + '">', "UTF-8")
        File xmlFile = java.nio.file.Files.createTempFile("sec-xxe-ext", ".xml").toFile()
        xmlFile.setText("""<?xml version="1.0"?>
<!DOCTYPE foo SYSTEM "${dtd.toURI()}">
<root><a>&xxe;</a></root>
""", "UTF-8")
        when:
        org.moqui.util.MNode node = org.moqui.util.MNode.parse(xmlFile)
        String text = node != null ? node.toString() : ""
        then:
        noExceptionThrown()
        node != null
        !text.contains("XXE_SECRET_MARKER")
        cleanup:
        xmlFile.delete()
        dtd.delete()
        secret.delete()
    }

    def "create UserAccount is not allow-remote"() {
        when:
        def sd = ecfi.serviceFacade.getServiceDefinition("org.moqui.impl.UserServices.create#UserAccount")
        then:
        sd != null
        !sd.allowRemote
    }

    def "update Password is not allow-remote"() {
        when:
        def sd = ecfi.serviceFacade.getServiceDefinition("org.moqui.impl.UserServices.update#Password")
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
        sd.authenticate == "anonymous-all"
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

    @Unroll
    def "ElFinder joinUnderRoot keeps #label under the root"() {
        given:
        def conn = new org.moqui.impl.util.ElFinderConnector(ec, root, "v0_")
        when:
        String loc = conn.joinUnderRoot(unhashed)
        then:
        loc == expected
        loc.startsWith(root)
        !loc.contains("..")
        where:
        label                     | root                          | unhashed                              | expected
        "parent segment"          | "dbresource://mantle/content" | "../secret"                           | "dbresource://mantle/content"
        "nested parents"          | "dbresource://mantle/content" | "a/b/../../../secret"                 | "dbresource://mantle/content"
        "absolute path"           | "dbresource://mantle/content" | "/etc/passwd"                         | "dbresource://mantle/content/etc/passwd"
        "backslash separators"    | "dbresource://mantle/content" | "..\\secret"                          | "dbresource://mantle/content"
        "scheme-like segment"     | "dbresource://mantle/content" | "component://webroot/screen"          | "dbresource://mantle/content"
        "other scheme segment"    | "dbresource://mantle/content" | "file:/etc/passwd"                    | "dbresource://mantle/content"
        "dot segment"             | "dbresource://mantle/content" | "./docs/readme.txt"                   | "dbresource://mantle/content/docs/readme.txt"
        "traversal in the middle" | "dbresource://mantle/content" | "docs/../../secret"                   | "dbresource://mantle/content"
        "empty"                   | "dbresource://mantle/content" | ""                                    | "dbresource://mantle/content"
        "root slash"              | "dbresource://mantle/content" | "/"                                   | "dbresource://mantle/content"
        "root sentinel"           | "dbresource://mantle/content" | "root"                                | "dbresource://mantle/content"
        "file root parent"        | "file:runtime"                | "../conf/MoquiProductionConf.xml"     | "file:runtime"
    }

    def "ElFinder joinUnderRoot allows a normal nested path"() {
        // positive control: the block above must not be passing because everything is flattened to the root
        given:
        def conn = new org.moqui.impl.util.ElFinderConnector(ec, "dbresource://mantle/content", "v0_")
        expect:
        conn.joinUnderRoot("docs/readme.txt") == "dbresource://mantle/content/docs/readme.txt"
        conn.joinUnderRoot("/docs/nested/readme.txt") == "dbresource://mantle/content/docs/nested/readme.txt"
    }

    def "ElFinder round-trips a hashed path"() {
        given:
        def conn = new org.moqui.impl.util.ElFinderConnector(ec, "dbresource://mantle/content", "v0_")
        when:
        String hashed = conn.hash("docs/readme.txt")
        then:
        conn.getLocation(hashed) == "dbresource://mantle/content/docs/readme.txt"
        conn.getPathRelativeToRoot(conn.getLocation(hashed)) == "docs/readme.txt"
        conn.isRoot(conn.getLocation(conn.hash("root")))
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

    def "email tracking pixel marks a known message viewed"() {
        given:
        String id = SecurityTestSupport.EMAIL_PIXEL_ID
        SecurityTestSupport.withAuthzDisabled(ec) {
            def em = ec.entity.find("moqui.basic.email.EmailMessage").condition("emailMessageId", id).one()
            if (em != null && em.statusId != "ES_SENT") {
                ec.service.sync().name("update#moqui.basic.email.EmailMessage")
                        .parameters([emailMessageId: id, statusId: "ES_SENT"]).disableAuthz().requireNewTransaction(true).call()
            }
        }
        when:
        WebFacadeImpl.markEmailMessageViewed(SecurityTestSupport.eci(ec), id + ".png")
        def after = null
        SecurityTestSupport.withAuthzDisabled(ec) {
            after = ec.entity.find("moqui.basic.email.EmailMessage").condition("emailMessageId", id).one()
        }
        then:
        after != null
        after.statusId == "ES_VIEWED"
    }
}
