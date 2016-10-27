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
package org.moqui.impl.context.reference

import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContextFactory
import org.moqui.util.ResourceReference
import org.moqui.impl.context.ResourceFacadeImpl

@CompileStatic
class ClasspathResourceReference extends UrlResourceReference {

    protected String strippedLocation

    ClasspathResourceReference() { super() }

    ResourceReference init(String location, ExecutionContextFactory ecf) {
        this.ecf = ecf
        strippedLocation = ResourceFacadeImpl.stripLocationPrefix(location)
        // first try the current thread's context ClassLoader
        locationUrl = Thread.currentThread().getContextClassLoader().getResource(strippedLocation)
        // next try the ClassLoader that loaded this class
        if (locationUrl == null) locationUrl = this.getClass().getClassLoader().getResource(strippedLocation)
        // no luck? try the system ClassLoader
        if (locationUrl == null) locationUrl = ClassLoader.getSystemResource(strippedLocation)
        // if the URL was found this way then it exists, so remember that
        if (locationUrl != null) {
            exists = true
            isFileProtocol = (locationUrl?.protocol == "file")
        } else {
            logger.warn("Could not find location [${strippedLocation}] on the classpath")
        }

        return this
    }

    @Override
    String getLocation() { return "classpath://" + strippedLocation }
}
