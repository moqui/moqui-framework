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
package org.moqui.impl.screen

import groovy.transform.CompileStatic
import org.moqui.util.MNode
import org.slf4j.LoggerFactory
import org.slf4j.Logger

@CompileStatic
class ScreenWidgets {
    protected final static Logger logger = LoggerFactory.getLogger(ScreenWidgets.class)

    protected MNode widgetsNode
    protected String location

    ScreenWidgets(MNode widgetsNode, String location) {
        this.widgetsNode = widgetsNode
        this.location = location
    }

    MNode getWidgetsNode() { return widgetsNode }
    String getLocation() { return location }

    void render(ScreenRenderImpl sri) {
        ScreenWidgetRender swr = sri.getScreenWidgetRender()
        swr.render(this, sri)
    }
}
