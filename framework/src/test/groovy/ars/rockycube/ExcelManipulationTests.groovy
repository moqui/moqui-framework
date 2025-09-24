package ars.rockycube

import ars.rockycube.excel.Manipulator
import ars.rockycube.util.TestUtilities
import com.google.gson.Gson
import org.apache.commons.io.FileUtils
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.reference.ComponentResourceReference
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

class ExcelManipulationTests extends Specification {
    protected final static Logger logger = LoggerFactory.getLogger(ExcelManipulationTests.class)
    private Gson gson = new Gson()
    protected String[] testDir = ["src", "test", "resources"]

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

    def test_basic_update() {
        when:
        def fileToConvert = FileUtils.getFile(TestUtilities.extendList(testDir, (String[]) ["Utils", "excel-manipulator", "IA_v19_0.INPUT.xlsm"]))

        // update
        def converted = Manipulator.modifyXlsm(fileToConvert.newInputStream(), [
                "nastavenia":
                        [
                                ["cell": "B16", "value":"name set correctly"]
                        ]
        ])

        // store the output
        def debugPath = (String[])["__temp", "Utils", "excel-manipulator", "created-IA_v19_0.INPUT.xlsm"]
        def outputFile = TestUtilities.extendList(testDir, debugPath)
        def outputStream = new FileOutputStream(new File(outputFile.join(File.separator)))
        converted.writeTo(outputStream)
        outputStream.close()

        then:

        // load created file and test values in it
        def fileCreated = FileUtils.getFile(TestUtilities.extendList(testDir, debugPath))
        XSSFWorkbook workbook = new XSSFWorkbook(fileCreated.newInputStream())

        // cell being written to
        def cellWrittenTo = workbook.getSheet("nastavenia").getRow(15).getCell(1)
        assert cellWrittenTo.stringCellValue == "name set correctly"
    }

    /**
     * Downloading file using service
     */
    def test_template_file_download() {
        when:

        def result = this.ec.service.sync().name("excel.GenerationServices.download#TemplateFile")
                .disableAuthz()
                .parameters([
                        contractType: 'fProject'
                ])
                .call()

        then:

        assert result

        def returnObject = result.data
        assert returnObject instanceof ComponentResourceReference
        assert returnObject.exists
        assert returnObject.size == 560598
    }

    def test_template_generation_01() {
        when:

        def result = this.ec.service.sync().name("excel.GenerationServices.generate#ExcelFile")
                .disableAuthz()
                .parameters([
                        contractType: 'fProject',
                        values: [
                                "nastavenia": [
                                        ["cell": "B16", "value": "name set, hopefully"]
                                ]
                        ]
                ])
                .call()

        then:

        assert result

        def returnObject = result.data
        assert returnObject instanceof ByteArrayOutputStream

        // store file
        def debugPath = (String[])["__temp", "Utils", "excel-manipulator", "generated", "created-01.xlsm"]
        TestUtilities.dumpToDebugUsingFile(TestUtilities.extendList(testDir, debugPath), returnObject as ByteArrayOutputStream)

        assert (returnObject as ByteArrayOutputStream).size() == 470899
    }
}
