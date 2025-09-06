package org.moqui.jwt;

import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;

import javax.servlet.Filter;
import java.util.HashMap;
import java.util.Map;

public class JwtShiroConfig {

    public JwtRealm jwtRealm() {
        return new JwtRealm();
    }

    public SecurityManager securityManager() {
        DefaultWebSecurityManager securityManager = new DefaultWebSecurityManager();
        securityManager.setRealm(jwtRealm());
        
        // 关闭Shiro自带的session
        securityManager.setSubjectFactory(new JwtSubjectFactory());
        
        return securityManager;
    }
}