package org.moqui.security;

import org.moqui.context.ExecutionContext;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public interface SingleSignOnTokenLoginHandler {
    public boolean handleSsoLoginToken(ExecutionContext ec, HttpServletRequest request, HttpServletResponse response, String ssoAccessToken, String ssoAuthFlowId);
}
