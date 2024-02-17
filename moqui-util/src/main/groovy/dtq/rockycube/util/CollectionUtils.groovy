package dtq.rockycube.util

import com.google.gson.internal.LinkedTreeMap
import org.apache.groovy.json.internal.LazyMap
import org.codehaus.groovy.runtime.GStringImpl

class CollectionUtils {
    /**
     * Return class type
     * @param whereToSearch
     * @param searchFor
     * @return
     */
    public static Class<?> checkClassInMap(Map whereToSearch, ArrayList searchFor) {
        if (searchFor.empty) return null
        def keyUsed = searchFor[0]
        if (!whereToSearch.containsKey(keyUsed)) return null

        // this is it
        // we have the key
        def isLastStep = searchFor.size() == 1
        if (isLastStep) {
            def originalValue = whereToSearch.get(keyUsed)
            return originalValue.getClass()
        }
        ArrayList newSearchFor = searchFor.clone() as ArrayList
        newSearchFor.remove(0)
        return checkClassInMap(whereToSearch.get(keyUsed) as LazyMap, newSearchFor)
    }

    public static Class<?> checkClassInMap(Map whereToSearch, String searchFor) {
        return checkClassInMap(whereToSearch, (ArrayList) searchFor.split('\\.'))
    }

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
        if (keyUsed == '*') return whereToSearch.asType(expectedType)
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
            } catch (Exception ignored){
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
        def keys = searchForKey.split('\\.')

        // if there is an asterisk?
        if (keys.size() > 1) if (keys[-1] == '*') return keys[-2]

        // otherwise return last item
        return keys[-1]
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
     * This method searches in map, but also supports search using symlinks,
     * where the first part of the `searchForKey` is modified by search in
     * a list of sym links.
     * @param whereToSearch
     * @param replaceFirstKey
     * @param searchForKey
     * @param expectedType
     * @param defaultIfNotFound
     * @return
     */
    public static <T> T findKeyInMap(
            Map whereToSearch,
            Closure ftorSearchAlternativeKey,
            Object searchForKey,
            Class<T> expectedType,
            Object defaultIfNotFound=null)
    {
        // check searchForKey's class
        ArrayList modSearchForKey
        switch (searchForKey.getClass())
        {
            case String.class:
            case GStringImpl.class:
                def s = (String) searchForKey
                modSearchForKey = s.split('\\.')
                break
            case ArrayList.class:
                modSearchForKey = (ArrayList) searchForKey
                break
            default:
                throw new Exception("Search for key in with symlink support failed on searchForKey - unsupported class [${searchForKey.getClass().toString()}]")
        }

        // if the class matches, return it right away
        // or if the `ftor` does not exist
        if (checkClassInMap(whereToSearch, modSearchForKey) == expectedType || ftorSearchAlternativeKey == null)
        {
            return findKeyInMap(
                    whereToSearch,
                    modSearchForKey,
                    expectedType,
                    defaultIfNotFound
            )
        }

        // construct new key that will be used for search
        def newRootKey = ftorSearchAlternativeKey(modSearchForKey[0])
        // if no alternate key is found, return default
        if (!newRootKey) return defaultIfNotFound as T

        def alternateKey = (ArrayList) modSearchForKey.clone()
        alternateKey[0] = newRootKey
        return findKeyInMap(whereToSearch, alternateKey, expectedType, defaultIfNotFound)
    }

    /**
     * Convert LazyMap to a HashMap
     * @param lm
     * @return
     */
    public static LinkedHashMap convertLazyMap(LazyMap lm)
    {
        def res = new LinkedHashMap()
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
        return res
    }
}
