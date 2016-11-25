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
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.resource.ResourceReference

@CompileStatic
abstract class WrapperResourceReference extends BaseResourceReference {
    ResourceReference rr = null

    WrapperResourceReference() { }

    @Override
    ResourceReference init(String location, ExecutionContextFactoryImpl ecf) {
        this.ecf = ecf
        return this
    }
    ResourceReference init(ResourceReference rr, ExecutionContextFactoryImpl ecf) {
        this.rr = rr
        this.ecf = ecf
        return this
    }

    @Override abstract ResourceReference createNew(String location);

    String getLocation() { return rr.getLocation() }

    InputStream openStream() { return rr.openStream() }
    OutputStream getOutputStream() { return rr.getOutputStream() }
    String getText() { return rr.getText() }

    boolean supportsAll() { return rr.supportsAll() }

    boolean supportsUrl() { return rr.supportsUrl() }
    URL getUrl() { return rr.getUrl() }

    boolean supportsDirectory() { return rr.supportsDirectory() }
    boolean isFile() { return rr.isFile() }
    boolean isDirectory() { return rr.isDirectory() }
    List<ResourceReference> getDirectoryEntries() { return rr.getDirectoryEntries() }

    boolean supportsExists() { return rr.supportsExists() }
    boolean getExists() { return rr.getExists()}

    boolean supportsLastModified() { return rr.supportsLastModified() }
    long getLastModified() { return rr.getLastModified() }

    boolean supportsSize() { return rr.supportsSize() }
    long getSize() { return rr.getSize() }

    boolean supportsWrite() { return rr.supportsWrite() }
    void putText(String text) { rr.putText(text) }
    void putStream(InputStream stream) { rr.putStream(stream) }
    void move(String newLocation) { rr.move(newLocation) }
    ResourceReference makeDirectory(String name) { return rr.makeDirectory(name) }
    ResourceReference makeFile(String name) { return rr.makeFile(name) }
    boolean delete() { return rr.delete() }

    void destroy() { rr.destroy() }
}
