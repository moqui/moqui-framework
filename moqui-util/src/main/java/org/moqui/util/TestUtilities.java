package org.moqui.util;

import com.google.gson.Gson;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class TestUtilities {
    private static final String[] RESOURCE_PATH = {"src", "test", "resources"};

    public static String[] extendList(String[] listToExtend, String[] valuesToAppend) {
        String[] result = new String[listToExtend.length + valuesToAppend.length];
        for (int i = 0; i < listToExtend.length; i++) {
            result[i] = listToExtend[i];
        }

        // append the final item
        int lastUsed = listToExtend.length;
        for (int i = 0; i < valuesToAppend.length; i++) {
            result[lastUsed + i] = valuesToAppend[i];
        }

        return result;
    }

    public static File getInputFile(String... path) throws URISyntaxException, IOException {
        return FileUtils.getFile(path);
    }

    public static List<HashMap<String, Object>> loadTestDataIntoArray(String[] resDirPath) throws URISyntaxException, IOException {
        // load data to import from a JSON
        String[] importFilePath = TestUtilities.extendList(RESOURCE_PATH, resDirPath);
        FileInputStream fisImport = new FileInputStream(getInputFile(importFilePath));

        Gson gson = new Gson();
        return gson.fromJson(new InputStreamReader(fisImport, StandardCharsets.UTF_8), ArrayList.class);
    }

}
