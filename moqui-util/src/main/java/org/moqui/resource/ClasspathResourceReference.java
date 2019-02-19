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

public class ClasspathResourceReference extends UrlResourceReference {
    private String strippedLocation;

    public ClasspathResourceReference() { super(); }

    @Override public ResourceReference init(String location) {
        strippedLocation = ResourceReference.stripLocationPrefix(location);
        // first try the current thread's context ClassLoader
        locationUrl = Thread.currentThread().getContextClassLoader().getResource(strippedLocation);
        // next try the ClassLoader that loaded this class
        if (locationUrl == null) locationUrl = this.getClass().getClassLoader().getResource(strippedLocation);
        // no luck? try the system ClassLoader
        if (locationUrl == null) locationUrl = ClassLoader.getSystemResource(strippedLocation);
        // if the URL was found this way then it exists, so remember that
        if (locationUrl != null) {
            exists = true;
            isFileProtocol = "file".equals(locationUrl.getProtocol());
        }

        return this;
    }

    @Override public ResourceReference createNew(String location) {
        ClasspathResourceReference resRef = new ClasspathResourceReference();
        resRef.init(location);
        return resRef;
    }

    @Override public String getLocation() { return "classpath://" + strippedLocation; }
}
