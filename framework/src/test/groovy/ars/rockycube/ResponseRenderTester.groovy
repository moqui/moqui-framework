package ars.rockycube

import com.google.gson.Gson
import org.apache.commons.fileupload.FileItem
import org.apache.commons.fileupload.disk.DiskFileItem
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.resource.ResourceReference
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

class ResponseRenderTester extends Specification {
    protected final static Logger logger = LoggerFactory.getLogger(ResponseRenderTester.class)
    private Gson gson = new Gson()

    @Shared
    ExecutionContext ec

    @Shared
    ExecutionContextFactoryImpl ecfi

    def setupSpec() {
        System.setProperty("moqui.conf", "conf/MoquiDevConf.xml")

        // init the framework, get the ec
        ec = Moqui.getExecutionContext()
        ec.user.loginUser('john.hardy', 'moqui')

        // initialize ECFI
        ecfi = (ExecutionContextFactoryImpl) Moqui.getExecutionContextFactory()

        // gson
        this.gson = new Gson()
    }

    def setup() {
        // logger.info("Running before each test")
    }

    def cleanupSpec() {
        if (ec) {
            // add some delay before turning off
            logger.info("Delaying deconstruction of ExecutionContext, for the purpose of storing data. Without this delay, the commit would have failed.")
            sleep(3000)

            // stop it all
            ec.destroy()
        }
    }

    def "test file upload - directly as bytes"() {
        when:

        // load a file from resource
        ResourceReference rr = ec.resource.getLocationReference("classpath://src/test/resources/FileUpload/directory.xlsx")

        // create a file
        def result = this.ec.service.sync().name("create#ars.rockycube.test.TestFile")
                .disableAuthz()
                .parameters([
                        externalGuid: "1111-22222-333333",
                        file: rr.openStream().readAllBytes()
                ])
                .call()
        then:

        // assert size
        result.size() == 1

        // download file from the entity and compare the size to original
        def loaded = this.ec.entity.find(CONST_TEST_ENTITY)
            .condition("fileId", result['fileId']).one()
        assert loaded

        // SerialBlob size compared to the file uploaded
        def storedFile = loaded['file']
        assert storedFile.length() == rr.size
    }
}
