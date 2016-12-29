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
package org.moqui.impl.webapp

import groovy.transform.CompileStatic
import org.moqui.impl.screen.ScreenDefinition

@CompileStatic
class ScreenResourceNotFoundException extends RuntimeException {
    ScreenDefinition rootSd
    List<String> fullPathNameList
    ScreenDefinition lastSd
    String pathFromLastScreen
    String resourceLocation
    ScreenResourceNotFoundException(ScreenDefinition rootSd, List<String> fullPathNameList,
                                           ScreenDefinition lastSd, String pathFromLastScreen, String resourceLocation,
                                           Exception cause) {
        super("Could not find subscreen or transition or file/content [" + pathFromLastScreen +
                (resourceLocation ? ":" + resourceLocation : "") + "] under screen [" +
                lastSd?.getLocation() + "] while finding url for path " + fullPathNameList + " under from screen [" +
                rootSd?.getLocation() + "]", cause)
        this.rootSd = rootSd
        this.fullPathNameList = fullPathNameList
        this.lastSd = lastSd
        this.pathFromLastScreen = pathFromLastScreen
        this.resourceLocation = resourceLocation
    }
}
