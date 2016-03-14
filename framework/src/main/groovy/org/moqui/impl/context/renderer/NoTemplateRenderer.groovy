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
package org.moqui.impl.context.renderer

import groovy.transform.CompileStatic
import org.moqui.context.TemplateRenderer
import org.moqui.context.ExecutionContextFactory
import org.moqui.impl.context.ExecutionContextFactoryImpl

@CompileStatic
class NoTemplateRenderer implements TemplateRenderer {
    protected ExecutionContextFactoryImpl ecfi

    NoTemplateRenderer() { }

    TemplateRenderer init(ExecutionContextFactory ecf) {
        this.ecfi = (ExecutionContextFactoryImpl) ecf
        return this
    }

    void render(String location, Writer writer) {
        String text = ecfi.resourceFacade.getLocationText(location, true)
        if (text) writer.write(text)
    }

    String stripTemplateExtension(String fileName) { return fileName }

    void destroy() { }
}
