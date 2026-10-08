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
package org.moqui.impl.util

import groovy.transform.CompileStatic
import org.apache.shiro.subject.Subject
import org.apache.shiro.web.mgt.DefaultWebSecurityManager

/** Servlet-container Shiro manager. Shiro 2.2+ rotates the HTTP session id on
 * successful login (CVE-2026-43827). Moqui already invalidates and replaces
 * the session in UserFacadeImpl.loginUser (makeNewSession) before login; a
 * second changeSessionId() leaves the authenticated Subject on a session the
 * client cookie no longer names. */
@CompileStatic
class MoquiWebSecurityManager extends DefaultWebSecurityManager {
    @Override
    protected void beforeSuccessfulLogin(Subject subject) {
        // skip HTTP session id rotation; Moqui already created a new session
    }
}
