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

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;

public abstract class ResourceReference implements Serializable {
    public abstract void init(String location);

    public abstract ResourceReference createNew(String location);

    public abstract String getLocation();
    public abstract InputStream openStream();
    public abstract OutputStream getOutputStream();
    public abstract String getText();

    public abstract boolean supportsAll();
    public abstract boolean supportsUrl();
    public abstract URL getUrl();

    public abstract boolean supportsDirectory();
    public abstract boolean isFile();
    public abstract boolean isDirectory();

    public abstract boolean supportsExists();
    public abstract boolean getExists();

    public abstract boolean supportsLastModified();
    public abstract long getLastModified();

    public abstract boolean supportsSize();
    public abstract long getSize();

    public abstract boolean supportsWrite();
    public abstract void putText(String text);
    public abstract void putStream(InputStream stream);
    public abstract void move(String newLocation);
    public abstract ResourceReference makeDirectory(String name);
    public abstract ResourceReference makeFile(String name);
    public abstract boolean delete();

    /** Get the entries of a directory */
    public abstract List<ResourceReference> getDirectoryEntries();

    public URI getUri() {
        try {
            if (supportsUrl()) {
                URL locUrl = getUrl();
                if (locUrl == null) return null;
                // use the multi-argument constructor to have it do character encoding and avoid an exception
                // WARNING: a String from this URI may not equal the String from the URL (ie if characters are encoded)
                // NOTE: this doesn't seem to work on Windows for local files: when protocol is plain "file" and path starts
                //     with a drive letter like "C:\moqui\..." it produces a parse error showing the URI as "file://C:/..."
                return new URI(locUrl.getProtocol(), locUrl.getUserInfo(), locUrl.getHost(),
                        locUrl.getPort(), locUrl.getPath(), locUrl.getQuery(), locUrl.getRef());
            } else {
                String loc = getLocation();
                if (loc == null || loc.isEmpty()) return null;
                return new URI(loc);
            }
        } catch (URISyntaxException e) {
            throw new BaseException("Error creating URI", e);
        }
    }

    /** One part of the URI not easy to get from the URI object, basically the last part of the path. */
    public String getFileName() {
        String loc = getLocation();
        if (loc == null || loc.length() == 0) return null;
        int slashIndex = loc.lastIndexOf("/");
        return slashIndex >= 0 ? loc.substring(slashIndex + 1) : loc;
    }


    /** The content (MIME) type for this content, if known or can be determined. */
    public abstract String getContentType();

    /** Get the parent directory, null if it is the root (no parent). */
    public abstract ResourceReference getParent();

    /** Find the directory with a name that matches the current filename (minus the extension) */
    public abstract ResourceReference findMatchingDirectory();
    /** Get a reference to the child of this directory or this file in the matching directory */
    public abstract ResourceReference getChild(String name);
    /** Get a list of references to all files in this directory or for a file in the matching directory */
    public abstract List<ResourceReference> getChildren();
    /** Find a file by path (can be single name) in the matching directory and child matching directories */
    public abstract ResourceReference findChildFile(String relativePath);
    /** Find a directory by path (can be single name) in the matching directory and child matching directories */
    public abstract ResourceReference findChildDirectory(String relativePath);

    public void destroy() { }
}
