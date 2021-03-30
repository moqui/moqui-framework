package org.moqui.impl.util

import groovy.transform.CompileStatic
import org.apache.shiro.authc.UsernamePasswordToken

class ForceLoginToken extends UsernamePasswordToken {
    ForceLoginToken(final String username, final boolean rememberMe) {
        super (username, 'force', rememberMe)
    }
}
