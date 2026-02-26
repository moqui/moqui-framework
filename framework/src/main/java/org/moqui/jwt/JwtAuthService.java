package org.moqui.jwt;

import org.moqui.Moqui;
import org.moqui.entity.EntityValue;
import org.moqui.context.ExecutionContext;

public class JwtAuthService {
    
    /**
     * 用户登录并生成JWT token
     * @param username 用户名
     * @param password 密码
     * @return token字符串
     */
    public static String login(String username, String password) {
        ExecutionContext ec = Moqui.getExecutionContext();
        
        try {
            // 使用Moqui内置的登录方法
            boolean loggedIn = ec.getUser().loginUser(username, password);
            
            if (loggedIn) {
                // 登录成功，生成JWT token
                String userId = ec.getUser().getUserId();
                return JwtUtil.sign(userId);
            } else {
                // 登录失败
                return null;
            }
        } finally {
            if (ec != null) ec.destroy();
        }
    }
    
    /**
     * 验证JWT token
     * @param token JWT token
     * @return 用户ID
     */
    public static String verifyToken(String token) {
        if (JwtUtil.verify(token) && !JwtUtil.isTokenExpired(token)) {
            return JwtUtil.getUserId(token);
        }
        return null;
    }
    
    /**
     * 根据用户ID获取用户信息
     * @param userId 用户ID
     * @return 用户信息
     */
    public static EntityValue getUserInfo(String userId) {
        ExecutionContext ec = Moqui.getExecutionContext();
        
        try {
            return ec.getEntity().find("moqui.security.UserAccount")
                    .condition("userId", userId).disableAuthz().one();
        } finally {
            if (ec != null) ec.destroy();
        }
    }
}