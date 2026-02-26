package org.moqui.jwt;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;
import org.moqui.Moqui;
import org.moqui.context.ExecutionContext;
import org.moqui.entity.EntityValue;

public class MoquiJwtRealm extends AuthorizingRealm {

    @Override
    public boolean supports(AuthenticationToken token) {
        return token instanceof JwtToken;
    }

    /**
     * 认证信息
     */
    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken auth) throws AuthenticationException {
        String token = (String) auth.getCredentials();
        
        // 验证token有效性
        if (!JwtUtil.verify(token)) {
            throw new AuthenticationException("Token验证失败");
        }
        
        // 检查token是否过期
        if (JwtUtil.isTokenExpired(token)) {
            throw new AuthenticationException("Token已过期");
        }
        
        // 从token中获取用户ID
        String userId = JwtUtil.getUserId(token);
        if (userId == null) {
            throw new AuthenticationException("Token中用户ID无效");
        }
        
        // 验证用户是否存在
        ExecutionContext ec = Moqui.getExecutionContext();
        try {
            EntityValue userAccount = ec.getEntity().find("moqui.security.UserAccount")
                    .condition("userId", userId).disableAuthz().one();
            
            if (userAccount == null) {
                throw new AuthenticationException("用户不存在");
            }
            
            // 返回认证信息
            return new SimpleAuthenticationInfo(userId, token, getName());
        } finally {
            if (ec != null) ec.destroy();
        }
    }

    /**
     * 授权信息
     */
    @Override
    protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
        String userId = (String) principals.getPrimaryPrincipal();
        
        SimpleAuthorizationInfo authorizationInfo = new SimpleAuthorizationInfo();
        
        ExecutionContext ec = Moqui.getExecutionContext();
        try {
            // 获取用户角色
            ec.getEntity().find("moqui.security.UserGroupMember")
                    .condition("userId", userId)
                    .disableAuthz()
                    .list()
                    .forEach(ugm -> {
                        authorizationInfo.addRole((String) ugm.get("userGroupId"));
                    });
            
            // 获取用户权限
            ec.getEntity().find("moqui.security.UserPermissionCheck")
                    .condition("userId", userId)
                    .useCache(true)
                    .disableAuthz()
                    .list()
                    .forEach(upc -> {
                        authorizationInfo.addStringPermission((String) upc.get("userPermissionId"));
                    });
        } finally {
            if (ec != null) ec.destroy();
        }
        
        return authorizationInfo;
    }
}