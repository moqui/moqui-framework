package org.moqui.jwt;

import org.apache.shiro.web.filter.authc.BasicHttpAuthenticationFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class MoquiJwtFilter extends BasicHttpAuthenticationFilter {
    private static final Logger logger = LoggerFactory.getLogger(MoquiJwtFilter.class);
    
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String TOKEN_PREFIX = "Bearer ";

    /**
     * 检查是否包含认证信息
     */
    @Override
    protected boolean isLoginAttempt(ServletRequest request, ServletResponse response) {
        HttpServletRequest req = (HttpServletRequest) request;
        String header = req.getHeader(AUTHORIZATION_HEADER);
        return header != null && header.startsWith(TOKEN_PREFIX);
    }

    /**
     * 执行登录操作
     */
    @Override
    protected boolean executeLogin(ServletRequest request, ServletResponse response) throws Exception {
        HttpServletRequest httpServletRequest = (HttpServletRequest) request;
        String header = httpServletRequest.getHeader(AUTHORIZATION_HEADER);
        
        if (header == null || !header.startsWith(TOKEN_PREFIX)) {
            return false;
        }
        
        String token = header.substring(TOKEN_PREFIX.length()).trim();
        JwtToken jwtToken = new JwtToken(token);
        
        try {
            // 提交给Realm进行登录
            getSubject(request, response).login(jwtToken);
            return true;
        } catch (Exception e) {
            logger.error("JWT登录失败", e);
            return false;
        }
    }

    /**
     * 是否允许访问
     */
    @Override
    protected boolean isAccessAllowed(ServletRequest request, ServletResponse response, Object mappedValue) {
        // 检查是否是登录尝试
        if (isLoginAttempt(request, response)) {
            try {
                return executeLogin(request, response);
            } catch (Exception e) {
                logger.error("执行登录时出错", e);
                return false;
            }
        }
        
        // 对于没有认证信息的请求，允许访问（可能是一些公开接口）
        return true;
    }

    /**
     * 拒绝访问时的处理
     */
    @Override
    protected boolean onAccessDenied(ServletRequest request, ServletResponse response) throws Exception {
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        httpResponse.getWriter().write("Unauthorized");
        return false;
    }
}