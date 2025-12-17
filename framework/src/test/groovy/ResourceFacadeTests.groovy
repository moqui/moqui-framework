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

import spock.lang.*

import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.resource.ResourceReference

class ResourceFacadeTests extends Specification {
    @Shared
    ExecutionContext ec

    def setupSpec() {
        // init the framework, get the ec
        ec = Moqui.getExecutionContext()
    }

    def cleanupSpec() {
        ec.destroy()
    }

    @Unroll
    def "get Location ResourceReference (#location)"() {
        expect:
        ResourceReference rr = ec.resource.getLocationReference(location)
        // the resolved location is different for some of these tests, so don't test for that: rr.location == location
        rr.uri.scheme == scheme
        rr.uri.host == host
        rr.fileName == fileName
        rr.contentType == contentType
        (!rr.supportsExists() || rr.exists) == exists
        (!rr.supportsDirectory() || rr.file) == isFile
        (!rr.supportsDirectory() || rr.directory) == isDirectory

        where:
        location | scheme | host | fileName | contentType | exists | isFile | isDirectory
        "component://tools/screen/Tools.xml" | "file" | null | "Tools.xml" | "text/xml" | true | true | false
        "component://tools/screen/ToolsFoo.xml" | "file" | null | "ToolsFoo.xml" | "text/xml" | false | false | false
        "classpath://entity/BasicEntities.xml" | "file" | null | "BasicEntities.xml" | "text/xml" | true | true | false
        "classpath://bitronix-default-config.properties" | "file" | null | "bitronix-default-config.properties" | "text/x-java-properties" | true | true | false
        "classpath://shiro.ini" | "file" | null | "shiro.ini" | "text/plain" | true | true | false
        "template/screen-macro/ScreenHtmlMacros.ftl" | "file" | null | "ScreenHtmlMacros.ftl" | "text/x-freemarker" | true | true | false
        "template/screen-macro" | "file" | null | "screen-macro" | "application/octet-stream" | true | false | true
    }

    @Unroll
    def "get Location Text (#location)"() {
        expect:
        String text = ec.resource.getLocationText(location, true)
        text.contains(contents)

        where:
        location | contents
        "component://tools/screen/Tools.xml" | "<subscreens default-item=\"dashboard\">"
        "classpath://shiro.ini" | "org.moqui.impl.util.MoquiShiroRealm"
    }

    // TODO: add tests for template() and script()

    @Unroll
    def "groovy evaluate Condition (#expression)"() {
        expect:
        result == ec.resource.condition(expression, "")

        where:
        expression | result
        "true" | true
        "false" | false
        "ec.context instanceof org.moqui.util.ContextStack" | true
    }

    @Unroll
    def "groovy evaluate Context Field (#expression)"() {
        expect:
        result == ec.resource.expression(expression, "")

        where:
        expression | result
        "ec.factory.moquiVersion" | ec.factory.moquiVersion
        "null" | null
        "undefinedVariable" | null
    }

    @Unroll
    def "groovy evaluate String Expand (#inputString)"() {
        expect:
        result == ec.resource.expand(inputString, "")

        where:
        inputString | result
        'Version: ${ec.factory.moquiVersion}' | "Version: ${ec.factory.moquiVersion}"
        "plain string" | "plain string"
    }
}
