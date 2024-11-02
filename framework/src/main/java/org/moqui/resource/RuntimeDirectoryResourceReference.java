/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.resource;

import org.moqui.BaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;

public class RuntimeDirectoryResourceReference extends UrlResourceReference {
    private static final Logger logger = LoggerFactory.getLogger(RuntimeDirectoryResourceReference.class);
    private String strippedLocation;

    public RuntimeDirectoryResourceReference() { super(); }

    @Override public ResourceReference init(String location) {
        logger.warn("Searching for resource at '" + location + "'.");

        if (location == null || location.isEmpty()) throw new BaseException("Cannot create Runtime Directory Resource Reference with empty location");
        if (location.startsWith("rdf://")) {
            location = location.substring(6);

            String moquiRuntime = System.getProperty("moqui.runtime");
            if (moquiRuntime != null && !moquiRuntime.isEmpty()) {
                File runtimeFile = new File(moquiRuntime);
                location = runtimeFile.getAbsolutePath() + "/" + location;
            }

            try { locationUrl = new URL("file:" + location); }
            catch (MalformedURLException e) { throw new BaseException("Invalid file url for location " + location, e); }
            isFileProtocol = true;
        } else {
            try {
                locationUrl = new URL(location);
            } catch (MalformedURLException e) {
                if (logger.isTraceEnabled())
                    logger.trace("Ignoring MalformedURLException for location, trying a local file: " + e.toString());
                // special case for Windows, try going through a file:

                try { locationUrl = new URL("file:/" + location); }
                catch (MalformedURLException se) { throw new BaseException("Invalid url for location " + location, e); }
            }

            isFileProtocol = "file".equals(getUrl().getProtocol());
        }

        return this;
    }

    @Override public ResourceReference createNew(String location) {
        RuntimeDirectoryResourceReference resRef = new RuntimeDirectoryResourceReference();
        resRef.init(location);
        return resRef;
    }

    @Override public String getLocation() { return "rtf://" + strippedLocation; }
}
