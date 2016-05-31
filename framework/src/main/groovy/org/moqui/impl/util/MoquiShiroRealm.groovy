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
import org.apache.shiro.authc.*
import org.apache.shiro.authc.credential.CredentialsMatcher
import org.apache.shiro.authz.Permission
import org.apache.shiro.authz.UnauthorizedException
import org.apache.shiro.realm.Realm
import org.apache.shiro.subject.PrincipalCollection
import org.apache.shiro.util.SimpleByteSource

import org.moqui.Moqui
import org.moqui.entity.EntityException
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ArtifactExecutionFacadeImpl
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.context.UserFacadeImpl
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp

@CompileStatic
class MoquiShiroRealm implements Realm {
    protected final static Logger logger = LoggerFactory.getLogger(MoquiShiroRealm.class)

    protected ExecutionContextFactoryImpl ecfi
    protected String realmName = "moquiRealm"

    protected Class<? extends AuthenticationToken> authenticationTokenClass = UsernamePasswordToken.class

    MoquiShiroRealm() {
        // with this sort of init we may only be able to get ecfi through static reference
        this.ecfi = (ExecutionContextFactoryImpl) Moqui.executionContextFactory
    }

    MoquiShiroRealm(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi
    }

    void setName(String n) { realmName = n }

    @Override
    String getName() { return realmName }

    //Class getAuthenticationTokenClass() { return authenticationTokenClass }
    //void setAuthenticationTokenClass(Class<? extends AuthenticationToken> atc) { authenticationTokenClass = atc }

    @Override
    boolean supports(AuthenticationToken token) {
        return token != null && authenticationTokenClass.isAssignableFrom(token.getClass())
    }

    static EntityValue loginPrePassword(ExecutionContextImpl eci, String username) {
        EntityValue newUserAccount = eci.entity.find("moqui.security.UserAccount").condition("username", username)
                .useCache(true).disableAuthz().one()

        // no account found?
        if (newUserAccount == null) throw new UnknownAccountException(eci.resource.expand('No account found for username ${username} in tenant ${tenantId}.','',[username:username,tenantId:eci.tenantId]))

        // check for disabled account before checking password (otherwise even after disable could determine if
        //    password is correct or not
        if (newUserAccount.disabled == "Y") {
            if (newUserAccount.disabledDateTime != null) {
                // account temporarily disabled (probably due to excessive attempts
                Integer disabledMinutes = eci.ecfi.confXmlRoot.first("user-facade").first("login").attribute("disable-minutes") as Integer ?: 30I
                Timestamp reEnableTime = new Timestamp(newUserAccount.getTimestamp("disabledDateTime").getTime() + (disabledMinutes.intValue()*60I*1000I))
                if (reEnableTime > eci.user.nowTimestamp) {
                    // only blow up if the re-enable time is not passed
                    eci.service.sync().name("org.moqui.impl.UserServices.incrementUserAccountFailedLogins")
                            .parameters((Map<String, Object>) [userId:newUserAccount.userId]).requireNewTransaction(true).call()
                    throw new ExcessiveAttemptsException(eci.resource.expand('Authenticate failed for user ${newUserAccount.username} in tenant ${tenantId} because account is disabled and will not be re-enabled until ${reEnableTime} [DISTMP].',
                    '',[newUserAccount:newUserAccount,tenantId:eci.tenantId,reEnableTime:reEnableTime]))
                }
            } else {
                // account permanently disabled
                eci.service.sync().name("org.moqui.impl.UserServices.incrementUserAccountFailedLogins")
                        .parameters((Map<String, Object>) [userId:newUserAccount.userId]).requireNewTransaction(true).call()
                throw new DisabledAccountException(eci.resource.expand('Authenticate failed for user ${newUserAccount.username} in tenant ${tenantId} because account is disabled and is not schedule to be automatically re-enabled [DISPRM].',
                '',[newUserAccount:newUserAccount,tenantId:eci.tenantId]))
            }
        }

        return newUserAccount
    }

    static void loginPostPassword(ExecutionContextImpl eci, EntityValue newUserAccount) {
        // the password did match, but check a few additional things
        if (newUserAccount.requirePasswordChange == "Y") {
            // NOTE: don't call incrementUserAccountFailedLogins here (don't need compounding reasons to stop access)
            throw new CredentialsException(eci.resource.expand('Authenticate failed for user [${newUserAccount.username}] because account requires password change [PWDCHG].','',[newUserAccount:newUserAccount]))
        }
        // check time since password was last changed, if it has been too long (user-facade.password.@change-weeks default 12) then fail
        if (newUserAccount.passwordSetDate) {
            int changeWeeks = (eci.ecfi.confXmlRoot.first("user-facade").first("password").attribute("change-weeks") ?: 12) as int
            if (changeWeeks > 0) {
                int wksSinceChange = ((eci.user.nowTimestamp.time - newUserAccount.getTimestamp("passwordSetDate").time) / (7*24*60*60*1000)).intValue()
                if (wksSinceChange > changeWeeks) {
                    // NOTE: don't call incrementUserAccountFailedLogins here (don't need compounding reasons to stop access)
                    throw new ExpiredCredentialsException(eci.resource.expand('Authenticate failed for user ${newUserAccount.username} in tenant ${tenantId} because password was changed ${wksSinceChange} weeks ago and must be changed every ${changeWeeks} weeks [PWDTIM].',
                    '',[newUserAccount:newUserAccount,tenantId:eci.tenantId,wksSinceChange:wksSinceChange,changeWeeks:changeWeeks]))
                }
            }
        }

        // no more auth failures? record the various account state updates, hasLoggedOut=N
        if (newUserAccount.successiveFailedLogins != 0 || newUserAccount.disabled != "N" ||
                newUserAccount.disabledDateTime != null || newUserAccount.hasLoggedOut != "N") {
            Map<String, Object> uaParameters = [userId:newUserAccount.userId, successiveFailedLogins:0,
                    disabled:"N", disabledDateTime:null, hasLoggedOut:"N"]
            eci.service.sync().name("update", "moqui.security.UserAccount").parameters(uaParameters).disableAuthz().call()
        }

        // update visit if no user in visit yet
        EntityValue visit = eci.user.visit
        if (visit) {
            if (!visit.userId) {
                eci.service.sync().name("update", "moqui.server.Visit")
                        .parameters((Map<String, Object>) [visitId:visit.visitId, userId:newUserAccount.userId]).disableAuthz().call()
            }
            if (!visit.clientIpCountryGeoId && !visit.clientIpTimeZone) {
                MNode ssNode = eci.ecfi.confXmlRoot.first("server-stats")
                if (ssNode.attribute("visit-ip-info-on-login") != "false") {
                    eci.service.async().name("org.moqui.impl.ServerServices.get#VisitClientIpData")
                            .parameter("visitId", visit.visitId).call()
                }
            }
        }
    }

    static void loginAfterAlways(ExecutionContextImpl eci, String userId, String passwordUsed, boolean successful) {
        // track the UserLoginHistory, whether the above succeeded or failed (ie even if an exception was thrown)
        if (!eci.getSkipStats()) {
            MNode loginNode = eci.ecfi.confXmlRoot.first("user-facade").first("login")
            if (userId != null && loginNode.attribute("history-store") != "false") {
                Timestamp fromDate = eci.getUser().getNowTimestamp()
                EntityValue curUlh = eci.entity.find("moqui.security.UserLoginHistory")
                        .condition([userId:userId, fromDate:fromDate] as Map<String, Object>).disableAuthz().one()
                if (curUlh == null) {
                    Map<String, Object> ulhContext = [userId:userId, fromDate:fromDate,
                            visitId:eci.user.visitId, successfulLogin:(successful?"Y":"N")] as Map<String, Object>
                    if (!successful && loginNode.attribute("history-incorrect-password") != "false") ulhContext.passwordUsed = passwordUsed
                    try {
                        eci.service.sync().name("create", "moqui.security.UserLoginHistory").parameters(ulhContext)
                                .requireNewTransaction(true).disableAuthz().call()
                        // we want to ignore errors from this, may happen in high-volume inserts where we don't care about the records so much anyway
                        eci.getMessage().clearErrors()
                    } catch (EntityException ee) {
                        // this blows up on MySQL, may in other cases, and is only so important so log a warning but don't rethrow
                        logger.warn("UserLoginHistory create failed: ${ee.toString()}")
                    }
                } else {
                    logger.warn("Not creating UserLoginHistory, found existing record for userId [${userId}] and fromDate [${fromDate}]")
                }
            }
        }
    }

    @Override
    AuthenticationInfo getAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
        ExecutionContextImpl eci = ecfi.getEci()
        String username = token.principal as String
        String userId = null
        boolean successful = false

        SaltedAuthenticationInfo info = null
        try {
            EntityValue newUserAccount = loginPrePassword(eci, username)
            userId = newUserAccount.getString("userId")

            // create the salted SimpleAuthenticationInfo object
            info = new SimpleAuthenticationInfo(username, newUserAccount.currentPassword,
                    newUserAccount.passwordSalt ? new SimpleByteSource((String) newUserAccount.passwordSalt) : null,
                    realmName)
            // check the password (credentials for this case)
            CredentialsMatcher cm = ecfi.getCredentialsMatcher((String) newUserAccount.passwordHashType)
            if (!cm.doCredentialsMatch(token, info)) {
                // if failed on password, increment in new transaction to make sure it sticks
                ecfi.serviceFacade.sync().name("org.moqui.impl.UserServices.increment#UserAccountFailedLogins")
                        .parameters((Map<String, Object>) [userId:newUserAccount.userId]).requireNewTransaction(true).call()
                throw new IncorrectCredentialsException(ecfi.resource.expand('Username ${username} and/or password incorrect in tenant ${tenantId}.','',[username:username,tenantId:eci.tenantId]))
            }

            loginPostPassword(eci, newUserAccount)

            // at this point the user is successfully authenticated
            successful = true
        } finally {
            loginAfterAlways(eci, userId, token.credentials as String, successful)
        }

        return info;
    }

    // ========== Authorization Methods ==========

    /**
     * @param principalCollection The principal (user)
     * @param resourceAccess Formatted as: "${typeEnumId}:${actionEnumId}:${name}"
     * @return boolean true if principal is permitted to access the resource, false otherwise.
     */
    boolean isPermitted(PrincipalCollection principalCollection, String resourceAccess) {
        // String username = (String) principalCollection.primaryPrincipal
        // TODO: if we want to support other users than the current need to look them up here
        return ArtifactExecutionFacadeImpl.isPermitted(resourceAccess, ecfi.eci)
    }

    boolean[] isPermitted(PrincipalCollection principalCollection, String... resourceAccesses) {
        boolean[] resultArray = new boolean[resourceAccesses.size()]
        int i = 0
        for (String resourceAccess in resourceAccesses) {
            resultArray[i] = this.isPermitted(principalCollection, resourceAccess)
            i++
        }
        return resultArray
    }

    boolean isPermittedAll(PrincipalCollection principalCollection, String... resourceAccesses) {
        for (String resourceAccess in resourceAccesses)
            if (!this.isPermitted(principalCollection, resourceAccess)) return false
        return true
    }

    boolean isPermitted(PrincipalCollection principalCollection, Permission permission) {
        throw new IllegalArgumentException("Authorization of Permission through Shiro not yet supported")
    }

    boolean[] isPermitted(PrincipalCollection principalCollection, List<Permission> permissions) {
        throw new IllegalArgumentException("Authorization of Permission through Shiro not yet supported")
    }

    boolean isPermittedAll(PrincipalCollection principalCollection, Collection<Permission> permissions) {
        throw new IllegalArgumentException("Authorization of Permission through Shiro not yet supported")
    }

    void checkPermission(PrincipalCollection principalCollection, Permission permission) {
        // TODO how to handle the permission interface?
        // see: http://www.jarvana.com/jarvana/view/org/apache/shiro/shiro-core/1.1.0/shiro-core-1.1.0-javadoc.jar!/org/apache/shiro/authz/Permission.html
        // also look at DomainPermission, can extend for Moqui artifacts
        // this.checkPermission(principalCollection, permission.?)
        throw new IllegalArgumentException("Authorization of Permission through Shiro not yet supported")
    }

    void checkPermission(PrincipalCollection principalCollection, String permission) {
        String username = (String) principalCollection.primaryPrincipal
        if (UserFacadeImpl.hasPermission(username, permission, null, ecfi.eci)) {
            throw new UnauthorizedException(ecfi.resource.expand('User ${username} does not have permission ${permission}','',[username:username,permission:permission]))
        }
    }

    void checkPermissions(PrincipalCollection principalCollection, String... strings) {
        for (String permission in strings) checkPermission(principalCollection, permission)
    }

    void checkPermissions(PrincipalCollection principalCollection, Collection<Permission> permissions) {
        for (Permission permission in permissions) checkPermission(principalCollection, permission)
    }

    boolean hasRole(PrincipalCollection principalCollection, String roleName) {
        String username = (String) principalCollection.primaryPrincipal
        return UserFacadeImpl.isInGroup(username, roleName, null, ecfi.eci)
    }

    boolean[] hasRoles(PrincipalCollection principalCollection, List<String> roleNames) {
        boolean[] resultArray = new boolean[roleNames.size()]
        int i = 0
        for (String roleName in roleNames) { resultArray[i] = this.hasRole(principalCollection, roleName); i++ }
        return resultArray
    }

    boolean hasAllRoles(PrincipalCollection principalCollection, Collection<String> roleNames) {
        for (String roleName in roleNames) { if (!this.hasRole(principalCollection, roleName)) return false }
        return true
    }

    void checkRole(PrincipalCollection principalCollection, String roleName) {
        if (!this.hasRole(principalCollection, roleName))
            throw new UnauthorizedException(ecfi.resource.expand('User ${principalCollection.primaryPrincipal} is not in role ${roleName}','',[principalCollection:principalCollection,roleName:roleName]))
    }

    void checkRoles(PrincipalCollection principalCollection, Collection<String> roleNames) {
        for (String roleName in roleNames) {
            if (!this.hasRole(principalCollection, roleName))
                throw new UnauthorizedException(ecfi.resource.expand('User ${principalCollection.primaryPrincipal} is not in role ${roleName}','',[principalCollection:principalCollection,roleName:roleName]))
        }
    }

    void checkRoles(PrincipalCollection principalCollection, String... roleNames) {
        for (String roleName in roleNames) {
            if (!this.hasRole(principalCollection, roleName))
                throw new UnauthorizedException(ecfi.resource.expand('User ${principalCollection.primaryPrincipal} is not in role ${roleName}','',[principalCollection:principalCollection,roleName:roleName]))
        }
    }
}
