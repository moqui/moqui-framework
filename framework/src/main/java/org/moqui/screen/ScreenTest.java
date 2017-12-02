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
package org.moqui.screen;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** A test harness for screen rendering. Does internal rendering without HTTP request/response */
@SuppressWarnings("unused")
public interface ScreenTest {
    /** Location of the root XML Screen file to render */
    ScreenTest rootScreen(String screenLocation);
    /** A screen path prepended to the screenPath used for all subsequent render() calls */
    ScreenTest baseScreenPath(String screenPath);

    /** @see ScreenRender#renderMode(String) */
    ScreenTest renderMode(String outputType);
    /** @see ScreenRender#encoding(String) */
    ScreenTest encoding(String characterEncoding);

    /** @see ScreenRender#macroTemplate(String) */
    ScreenTest macroTemplate(String macroTemplateLocation);

    /** @see ScreenRender#baseLinkUrl(String) */
    ScreenTest baseLinkUrl(String baseLinkUrl);
    /** @see ScreenRender#servletContextPath(String) */
    ScreenTest servletContextPath(String scp);
    /** @see ScreenRender#webappName(String) */
    ScreenTest webappName(String wan);

    /** Calls to WebFacade.sendJsonResponse will not be serialized, use along with ScreenTestRender.getJsonObject() */
    ScreenTest skipJsonSerialize(boolean skip);

    /** Get screen name paths to all screens with no required parameters under the rootScreen and (if specified) baseScreenPath */
    List<String> getNoRequiredParameterPaths(Set<String> screensToSkip);

    /** Test render a screen.
     * @param screenPath Path from rootScreen in the sub-screen hierarchy
     * @param parameters Map with name/value pairs to use as if they were URL or body parameters
     * @param requestMethod The HTTP request method to use when selecting a transition (defaults to get)
     * @return ScreenTestRender object with the render result
     */
    ScreenTestRender render(String screenPath, Map<String, Object> parameters, String requestMethod);

    long getRenderCount();
    long getErrorCount();
    long getRenderTotalChars();
    long getStartTime();

    interface ScreenTestRender {
        ScreenRender getScreenRender();
        String getOutput();
        Object getJsonObject();
        long getRenderTime();
        Map getPostRenderContext();
        List<String> getErrorMessages();
        boolean assertContains(String text);
        boolean assertNotContains(String text);
        boolean assertRegex(String regex);
    }
}
