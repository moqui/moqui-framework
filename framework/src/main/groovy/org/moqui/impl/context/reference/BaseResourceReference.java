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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.*;

public abstract class BaseResourceReference extends ResourceReference {
    protected static final Logger logger = LoggerFactory.getLogger(BaseResourceReference.class);
    protected ExecutionContextFactoryImpl ecf = (ExecutionContextFactoryImpl) null;

    public BaseResourceReference() { }

    @Override
    public ResourceReference init(String location) { return init(location, null); }
    public abstract ResourceReference init(String location, ExecutionContextFactoryImpl ecf);

    @Override public abstract ResourceReference createNew(String location);
    @Override public abstract String getLocation();
    @Override public abstract InputStream openStream();
    @Override public abstract OutputStream getOutputStream();
    @Override public abstract String getText();

    @Override public abstract boolean supportsAll();
    @Override public abstract boolean supportsUrl();
    @Override public abstract URL getUrl();
    @Override public abstract boolean supportsDirectory();
    @Override public abstract boolean isFile();
    @Override public abstract boolean isDirectory();
    @Override public abstract List<ResourceReference> getDirectoryEntries();

    @Override public abstract boolean supportsExists();
    @Override public abstract boolean getExists();
    @Override public abstract boolean supportsLastModified();
    @Override public abstract long getLastModified();
    @Override public abstract boolean supportsSize();
    @Override public abstract long getSize();

    @Override public abstract boolean supportsWrite();
    @Override public abstract void putText(String text);
    @Override public abstract void putStream(InputStream stream);
    @Override public abstract void move(String newLocation);

    @Override public abstract ResourceReference makeDirectory(String name);
    @Override public abstract ResourceReference makeFile(String name);
    @Override public abstract boolean delete();
}
