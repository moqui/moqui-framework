package dtq.rockycube.util

import com.google.gson.internal.LinkedTreeMap
import org.apache.groovy.json.internal.LazyMap

class CollectionUtils {
    /**
     * Search for a keyword inside a LazyMap, supports nested objects
     * @param whereToSearch
     * @param searchFor
     * @param expectedType
     * @param defaultIfNotFound
     * @return
     */
    public static <T> T findKeyInMap(Map whereToSearch, ArrayList searchFor, Class<T> expectedType, Object defaultIfNotFound=null){
        if (searchFor.empty) return defaultIfNotFound as T
        def keyUsed = searchFor[0]
        if (!whereToSearch.containsKey(keyUsed)) return defaultIfNotFound as T

        // this is it
        // we have the key
        def isLastStep = searchFor.size() == 1
        if (isLastStep)
        {
            def originalValue = whereToSearch.get(keyUsed)

            // treat boolean specially
            if (expectedType == Boolean)
            {
                switch (originalValue.toString().toLowerCase())
                {
                    case "true":
                    case "1":
                    case "allow":
                    case "allowed":
                        return true
                    case "false":
                    case "0":
                    case "not-allow":
                    case "not-allowed":
                        return false
                    default:
                        return false
                }
            }

            // cast it
            try {
                def casted = originalValue.asType(expectedType)
                return casted
            } catch (Exception exc){
                return defaultIfNotFound as T
            }
        }

        // update `searchFor` key and `whereToSearch` map and run the same procedure
        ArrayList newSearchFor = searchFor.clone() as ArrayList
        newSearchFor.remove(0)
        return findKeyInMap(whereToSearch.get(keyUsed) as LazyMap, newSearchFor, expectedType, defaultIfNotFound ) as T
    }

    /**
     * Return key that has been used for searching
     * @param searchForKey
     * @return
     */
    public static String keyInUse(String searchForKey)
    {
        return searchForKey.split('\\.')[-1]
    }

    public static <T> T findKeyInMap(Object whereToSearch, String searchForKey, Class<T> expectedType, Object defaultIfNotFound=null)
    {
        // convert incoming object to a map
        switch (whereToSearch.getClass())
        {
            case HashMap.class:
            case LinkedHashMap.class:
            case LinkedTreeMap.class:
            case LazyMap.class:
                return findKeyInMap(
                        (HashMap) whereToSearch,
                        (ArrayList) searchForKey.split('\\.'),
                        expectedType,
                        defaultIfNotFound
                )
            default:
                throw new Exception("Unable to perform search for a key in object of class [${whereToSearch.getClass().simpleName}]")
        }



    }

    public static <T> T findKeyInMap(Map whereToSearch, String searchForKey, Class<T> expectedType, Object defaultIfNotFound=null)
    {
        return findKeyInMap(
                whereToSearch,
                (ArrayList) searchForKey.split('\\.'),
                expectedType,
                defaultIfNotFound
        )
    }

    /**
     * Convert LazyMap to a HashMap
     * @param lm
     * @return
     */
    public static HashMap convertLazyMap(LazyMap lm)
    {
        def res = new HashMap()
        for ( prop in lm ) {
            def actualValue = prop.value

            // convert to map, even if it's deeper
            if (actualValue.getClass() == LazyMap.class) {
                actualValue = convertLazyMap((LazyMap) actualValue)
            } else if (actualValue.getClass() == ArrayList.class) {
                actualValue = TestUtilities.convertArrayWithLazyMap((ArrayList) actualValue)
            }
            res[prop.key] = actualValue
        }
        return res;
    }
}
