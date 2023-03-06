package org.moqui.util;

import com.google.gson.Gson
import com.google.gson.JsonElement
import groovy.json.JsonSlurper
import org.apache.commons.io.FileUtils
import org.apache.groovy.json.internal.LazyMap

import java.nio.charset.StandardCharsets
import java.util.regex.Pattern;


public class TestUtilities {
    public static final String[] RESOURCE_PATH = ["src", "test", "resources"]

    public static String[] extendList(String[] listToExtend, String[] valuesToAppend) {
        String[] result = new String[listToExtend.length + valuesToAppend.length]
        for (int i = 0; i < listToExtend.length; i++) {
            result[i] = listToExtend[i]
        }

        // append the final item
        int lastUsed = listToExtend.length
        for (int i = 0; i < valuesToAppend.length; i++) {
            result[lastUsed + i] = valuesToAppend[i]
        }
        return result
    }

    public static File getInputFile(String... path) throws URISyntaxException, IOException {
        return FileUtils.getFile(path)
    }

    public static List<JsonElement> loadTestDataIntoArray(String[] resDirPath) throws URISyntaxException, IOException {
        // load data to import from a JSON
        String[] importFilePath = TestUtilities.extendList(RESOURCE_PATH, resDirPath)
        FileInputStream fisImport = new FileInputStream(getInputFile(importFilePath))

        Gson gson = new Gson()
        return gson.fromJson(new InputStreamReader(fisImport, StandardCharsets.UTF_8), ArrayList.class)
    }

    public static void readFileLines(String[] resDirPath, String separator, Closure cb){
        String[] importFilePath = extendList(RESOURCE_PATH, resDirPath)
        FileInputStream fisImport = new FileInputStream(getInputFile(importFilePath))

        def is = new InputStreamReader(fisImport, StandardCharsets.UTF_8)
        def line
        is.withReader {reader->
            while ((line = reader.readLine()) != null) {
                // split into array
                String[] values = line.toString().split(separator)

                cb(values)
            }
        }
    }

    public static InputStream loadTestResource(String[] resDir)
    {
        String[] importFilePath = extendList(RESOURCE_PATH, resDir)
        def is = new FileInputStream(getInputFile(importFilePath))
        return is
    }

    public static void testSingleFile(String[] resDirPath, Closure cb) throws IOException, URISyntaxException {
        String[] importFilePath = extendList(RESOURCE_PATH, resDirPath)
        FileInputStream fisImport = new FileInputStream(getInputFile(importFilePath))

        def js = new JsonSlurper()
        ArrayList tests = js.parse(new InputStreamReader(fisImport, StandardCharsets.UTF_8)) as ArrayList

        // cycle through test definitions and evaluate
        tests.eachWithIndex{ ArrayList t, idx ->
            // [0] > com.google.gson.internal.LinkedTreeMap
            // [1] > could be a String, could be an object

            // expected value may be a value itself, or a path to JSON file with value
            def processedEntity = t[0]
            def expectedValue = t[1]
            if (expectedValue instanceof String)
            {
                String[] expectedPath = extendList(RESOURCE_PATH, t[1] as String)
                FileInputStream expValStream = new FileInputStream(getInputFile(expectedPath))
                expectedValue = js.parse(new InputStreamReader(expValStream, StandardCharsets.UTF_8)) as HashMap
            }
            def proc = convertLazyMap(processedEntity as LazyMap)
            def exp = convertLazyMap(expectedValue as LazyMap)

            cb(proc, exp, idx)
        }
    }

    private static HashMap convertLazyMap(LazyMap lm)
    {
        def res = new HashMap()
        for ( prop in lm ) {
            res[prop.key] = prop.value
        }
        return res;
    }

    // create debug Writer
    private static Writer createDebugWriter(String[] debugTo)
    {
        // log and store output
        String[] debugFilePath = extendList(RESOURCE_PATH, debugTo)
        FileOutputStream debug = new FileOutputStream(getInputFile(debugFilePath))
        return new OutputStreamWriter(debug, StandardCharsets.UTF_8)
    }

    public static String formattedTimestamp()
    {
        Date act = new Date()
        return act.format("yyMMdd_HHmmss")
    }

    public static String insertTimestampToFilename(String filename)
    {
        def ts = formattedTimestamp()
        return insertBeforeExtension(filename, ts)
    }

    // insert string just before where file extension begins
    public static String insertBeforeExtension(String fileName, String insert)
    {
        def recFileAndExt = Pattern.compile("^(.+)\\.(\\w{3,4})")
        def m = recFileAndExt.matcher(fileName)
        if (!m.matches()) return fileName

        def origFile = m.group(1)
        def origExt = m.group(2)


        return "${origFile}_${insert}.${origExt}".toString()
    }

    // write to log, for debug purposes - entire string
    public static void dumpToDebug(String[] debugPath, Closure cb)
    {
        def fw = createDebugWriter(debugPath)
        fw.write(cb() as String)
        fw.close();
    }

    // write to log - with per-line command
    static void dumpToDebugUsingWriter(String[] debugPath, boolean appendTimeStamp, Closure cb)
    {
        // modify string
        if (appendTimeStamp)
        {
            def newPath = debugPath
            def filename = insertTimestampToFilename(debugPath.last())
            debugPath[debugPath.length - 1] = filename
        }

        dumpToDebugUsingWriter(debugPath, cb)
    }

    static void dumpToDebugUsingWriter(String[] debugPath, boolean appendTimeStamp, ByteArrayOutputStream bt)
    {
        // modify string
        if (appendTimeStamp)
        {
            def newPath = debugPath
            def filename = insertTimestampToFilename(debugPath.last())
            debugPath[debugPath.length - 1] = filename
        }

        dumpToDebugUsingWriter(debugPath, bt)
    }


    static void dumpToDebugUsingWriter(String[] debugPath, Closure cb)
    {
        def fw = createDebugWriter(debugPath)

        // attempt to write using closure
        cb(fw)

        fw.close();
    }

    static void dumpToDebugUsingWriter(String[] debugPath, ByteArrayOutputStream bt)
    {
        def fw = createDebugWriter(debugPath)

        fw.write(bt.toString(StandardCharsets.UTF_8))
        fw.close();
    }
}
