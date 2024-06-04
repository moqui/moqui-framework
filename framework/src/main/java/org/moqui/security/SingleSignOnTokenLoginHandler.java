package org.moqui.security;

import org.moqui.context.ExecutionContext;

public interface SingleSignOnTokenLoginHandler {
    public boolean handleSsoLoginToken(ExecutionContext ec, String ssoAccessToken, String ssoAuthFlowId);
}
