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
package org.moqui.context;

import org.moqui.resource.ResourceReference;

import javax.activation.DataSource;
import javax.xml.transform.stream.StreamSource;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.net.URI;
import java.util.Map;

/** For accessing resources by location string (http://, jar://, component://, content://, classpath://, etc). */
public interface ResourceFacade {
    /** Get a ResourceReference representing the Moqui location string passed.
     *
     * @param location A URL-style location string. In addition to the standard URL protocols (http, https, ftp, jar,
     * and file) can also have the special Moqui protocols of "component://" for a resource location relative to a
     * component base location, "content://" for a resource in the content repository, and "classpath://" to get a
     * resource from the Java classpath.
     */
    ResourceReference getLocationReference(String location);
    ResourceReference getUriReference(URI uri);

    /** Open an InputStream to read the contents of a file/document at a location.
     *
     * @param location A URL-style location string that also support the Moqui-specific component and content protocols.
     */
    InputStream getLocationStream(String location);

    /** Get the text at the given location, optionally from the cache (resource.text.location). */
    String getLocationText(String location, boolean cache);
    DataSource getLocationDataSource(String location);

    /** Render a template at the given location using the current context and write the output to the given writer. */
    void template(String location, Writer writer);

    /** Run a script at the given location (optionally with the given method, like in a groovy class) using the current
     * context for its variable space.
     *
     * @return The value returned by the script, if any.
     */
    Object script(String location, String method);
    Object script(String location, String method, Map additionalContext);

    /** Evaluate a Groovy expression as a condition.
     *
     * @return boolean representing the result of evaluating the expression
     */
    boolean condition(String expression, String debugLocation);
    boolean condition(String expression, String debugLocation, Map additionalContext);

    /** Evaluate a Groovy expression as a context field, or more generally as an expression that evaluates to an Object
     * reference. This can be used to get a value from an expression or to run any general expression or script.
     *
     * @return Object reference representing result of evaluating the expression
     */
    Object expression(String expr, String debugLocation);
    Object expression(String expr, String debugLocation, Map additionalContext);

    /** Evaluate a Groovy expression as a GString to be expanded/interpolated into a simple String.
     *
     * NOTE: the inputString is always run through the L10nFacade.localize() method before evaluating the
     * expression in order to implicitly internationalize string expansion.
     *
     * @return String representing localized and expanded inputString
     */
    String expand(String inputString, String debugLocation);
    String expand(String inputString, String debugLocation, Map additionalContext);
    String expand(String inputString, String debugLocation, Map additionalContext, boolean localize);
    String expandNoL10n(String inputString, String debugLocation);

    Integer xslFoTransform(StreamSource xslFoSrc, StreamSource xsltSrc, OutputStream out, String contentType);

    String getContentType(String filename);
}
