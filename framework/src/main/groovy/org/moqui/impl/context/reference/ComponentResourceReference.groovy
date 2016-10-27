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
class ComponentResourceReference extends WrapperResourceReference {

    protected String componentLocation

    ComponentResourceReference() { super() }

    ResourceReference init(String location, ExecutionContextFactory ecf) {
        this.ecf = ecf

        if (location.endsWith("/")) location = location.substring(0, location.length()-1)
        this.componentLocation = location

        String strippedLocation = ResourceFacadeImpl.stripLocationPrefix(location)

        // turn this into another URL using the component location
        StringBuffer baseLocation = new StringBuffer(strippedLocation)
        // componentName is everything before the first slash
        String componentName;
        int firstSlash = baseLocation.indexOf("/")
        if (firstSlash > 0) {
            componentName = baseLocation.substring(0, firstSlash)
            // got the componentName, now remove it from the baseLocation
            baseLocation.delete(0, firstSlash + 1)
        } else {
            componentName = baseLocation
            baseLocation.delete(0, baseLocation.length())
        }

        baseLocation.insert(0, '/')
        baseLocation.insert(0, ecf.getComponentBaseLocations().get(componentName))

        this.rr = ecf.resource.getLocationReference(baseLocation.toString())

        return this
    }

    @Override
    String getLocation() { return componentLocation?.toString() }

    @Override
    List<ResourceReference> getDirectoryEntries() {
        // a little extra work to keep the directory entries as component-based locations
        List<ResourceReference> nestedList = this.rr.getDirectoryEntries()
        List<ResourceReference> newList = new ArrayList(nestedList.size())
        for (ResourceReference entryRr in nestedList) {
            String entryLoc = entryRr.location
            if (entryLoc.endsWith("/")) entryLoc = entryLoc.substring(0, entryLoc.length()-1)
            String newLocation = this.componentLocation + "/" + entryLoc.substring(entryLoc.lastIndexOf("/")+1)
            newList.add(new ComponentResourceReference().init(newLocation, ecf))
        }
        return newList
    }
}
