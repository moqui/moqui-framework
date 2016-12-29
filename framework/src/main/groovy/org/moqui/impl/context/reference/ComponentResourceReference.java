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
package org.moqui.impl.context.reference;

import org.moqui.impl.context.ExecutionContextFactoryImpl;
import org.moqui.resource.ResourceReference;

import java.util.ArrayList;
import java.util.List;

public class ComponentResourceReference extends WrapperResourceReference {
    private String componentLocation;

    public ComponentResourceReference() { super(); }

    public ResourceReference init(String location, ExecutionContextFactoryImpl ecf) {
        this.ecf = ecf;

        if (location.endsWith("/")) location = location.substring(0, location.length() - 1);
        this.componentLocation = location;

        String strippedLocation = ResourceReference.stripLocationPrefix(location);

        // turn this into another URL using the component location
        StringBuilder baseLocation = new StringBuilder(strippedLocation);
        // componentName is everything before the first slash
        String componentName;
        int firstSlash = baseLocation.indexOf("/");
        if (firstSlash > 0) {
            componentName = baseLocation.substring(0, firstSlash);
            // got the componentName, now remove it from the baseLocation
            baseLocation.delete(0, firstSlash + 1);
        } else {
            componentName = baseLocation.toString();
            baseLocation.delete(0, baseLocation.length());
        }

        baseLocation.insert(0, "/");
        baseLocation.insert(0, ecf.getComponentBaseLocations().get(componentName));

        setRr(ecf.getResource().getLocationReference(baseLocation.toString()));
        return this;
    }

    @Override
    public ResourceReference createNew(String location) {
        ComponentResourceReference resRef = new ComponentResourceReference();
        resRef.init(location, ecf);
        return resRef;
    }

    @Override
    public String getLocation() { return componentLocation; }

    @Override
    public List<ResourceReference> getDirectoryEntries() {
        // a little extra work to keep the directory entries as component-based locations
        List<ResourceReference> nestedList = this.getRr().getDirectoryEntries();
        List<ResourceReference> newList = new ArrayList<>(nestedList.size());
        for (ResourceReference entryRr : nestedList) {
            String entryLoc = entryRr.getLocation();
            if (entryLoc.endsWith("/")) entryLoc = entryLoc.substring(0, entryLoc.length() - 1);
            String newLocation = this.componentLocation + "/" + entryLoc.substring(entryLoc.lastIndexOf("/") + 1);
            newList.add(new ComponentResourceReference().init(newLocation, ecf));
        }

        return newList;
    }
}
