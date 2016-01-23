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

import org.moqui.entity.EntityException
import org.moqui.impl.context.ExecutionContextImpl

import java.sql.Timestamp

import org.apache.shiro.authc.AuthenticationInfo
import org.apache.shiro.authc.AuthenticationToken
import org.apache.shiro.authc.AuthenticationException
import org.apache.shiro.authc.credential.CredentialsMatcher
import org.apache.shiro.authc.IncorrectCredentialsException
import org.apache.shiro.authc.SaltedAuthenticationInfo
import org.apache.shiro.authc.SimpleAuthenticationInfo
import org.apache.shiro.authc.UsernamePasswordToken
import org.apache.shiro.authc.UnknownAccountException
import org.apache.shiro.authc.ExpiredCredentialsException
import org.apache.shiro.authc.DisabledAccountException
import org.apache.shiro.authc.CredentialsException
import org.apache.shiro.authc.ExcessiveAttemptsException
import org.apache.shiro.authz.Permission
import org.apache.shiro.authz.UnauthorizedException
import org.apache.shiro.realm.Realm
import org.apache.shiro.subject.PrincipalCollection
import org.apache.shiro.util.SimpleByteSource

import org.moqui.entity.EntityValue
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.UserFacadeImpl
import org.moqui.impl.context.ArtifactExecutionFacadeImpl
import org.moqui.Moqui

import org.slf4j.Logger
import org.slf4j.LoggerFactory

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

    @Override
    AuthenticationInfo getAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
        String username = token.principal
        String userId = null
        boolean successful = false

        EntityValue newUserAccount = null
        SaltedAuthenticationInfo info = null
        try {
            boolean alreadyDisabled = ecfi.executionContext.artifactExecution.disableAuthz()
            try {
                newUserAccount = ecfi.entityFacade.find("moqui.security.UserAccount").condition("username", username).useCache(true).one()
            } finally {
                if (!alreadyDisabled) ecfi.executionContext.artifactExecution.enableAuthz()
            }

            // no account found?
            if (newUserAccount == null) throw new UnknownAccountException("Username [${username}] and/or password incorrect.")

            userId = newUserAccount.userId

            // check for disabled account before checking password (otherwise even after disable could determine if
            //    password is correct or not
            if (newUserAccount.disabled == "Y") {
                if (newUserAccount.disabledDateTime != null) {
                    // account temporarily disabled (probably due to excessive attempts
                    Integer disabledMinutes = ecfi.confXmlRoot."user-facade"[0]."login"[0]."@disable-minutes" as Integer ?: 30
                    Timestamp reEnableTime = new Timestamp(newUserAccount.getTimestamp("disabledDateTime").getTime() + (disabledMinutes*60*1000))
                    if (reEnableTime > ecfi.executionContext.user.nowTimestamp) {
                        // only blow up if the re-enable time is not passed
                        ecfi.serviceFacade.sync().name("org.moqui.impl.UserServices.incrementUserAccountFailedLogins")
                                .parameters((Map<String, Object>) [userId:newUserAccount.userId]).requireNewTransaction(true).call()
                        throw new ExcessiveAttemptsException("Authenticate failed for user [${username}] because account is disabled and will not be re-enabled until [${reEnableTime}] [DISTMP].")
                    }
                } else {
                    // account permanently disabled
                    ecfi.serviceFacade.sync().name("org.moqui.impl.UserServices.incrementUserAccountFailedLogins")
                            .parameters((Map<String, Object>) [userId:newUserAccount.userId]).requireNewTransaction(true).call()
                    throw new DisabledAccountException("Authenticate failed for user [${username}] because account is disabled and is not schedule to be automatically re-enabled [DISPRM].")
                }
            }

            // create the SaltedAuthenticationInfo object
            info = new SimpleAuthenticationInfo(username, newUserAccount.currentPassword,
                    newUserAccount.passwordSalt ? new SimpleByteSource((String) newUserAccount.passwordSalt) : null,
                    realmName)
            // check the password (credentials for this case)
            CredentialsMatcher cm = ecfi.getCredentialsMatcher((String) newUserAccount.passwordHashType)
            if (!cm.doCredentialsMatch(token, info)) {
                // if failed on password, increment in new transaction to make sure it sticks
                ecfi.serviceFacade.sync().name("org.moqui.impl.UserServices.incrementUserAccountFailedLogins")
                        .parameters((Map<String, Object>) [userId:newUserAccount.userId]).requireNewTransaction(true).call()
                throw new IncorrectCredentialsException("Username [${username}] and/or password incorrect.")
            }

            // the password did match, but check a few additional things
            if (newUserAccount.requirePasswordChange == "Y") {
                // NOTE: don't call incrementUserAccountFailedLogins here (don't need compounding reasons to stop access)
                throw new CredentialsException("Authenticate failed for user [${username}] because account requires password change [PWDCHG].")
            }
            // check time since password was last changed, if it has been too long (user-facade.password.@change-weeks default 12) then fail
            if (newUserAccount.passwordSetDate) {
                int changeWeeks = (ecfi.confXmlRoot."user-facade"[0]."password"[0]."@change-weeks" ?: 12) as int
                if (changeWeeks > 0) {
                    int wksSinceChange = (ecfi.executionContext.user.nowTimestamp.time - newUserAccount.passwordSetDate.time) / (7*24*60*60*1000)
                    if (wksSinceChange > changeWeeks) {
                        // NOTE: don't call incrementUserAccountFailedLogins here (don't need compounding reasons to stop access)
                        throw new ExpiredCredentialsException("Authenticate failed for user [${username}] because password was changed [${wksSinceChange}] weeks ago and must be changed every [${changeWeeks}] weeks [PWDTIM].")
                    }
                }
            }

            // at this point the user is successfully authenticated
            successful = true

            // NOTE: special case, for this thread only and for the section of code below need to turn off artifact
            //     authz since normally the user above would have authorized with something higher up, but that can't
            //     be done at this point
            alreadyDisabled = ecfi.executionContext.artifactExecution.disableAuthz()
            try {
                // no more auth failures? record the various account state updates, hasLoggedOut=N
                if (newUserAccount.successiveFailedLogins != 0 || newUserAccount.disabled != "N" ||
                        newUserAccount.disabledDateTime != null || newUserAccount.hasLoggedOut != "N") {
                    Map<String, Object> uaParameters = [userId:userId, successiveFailedLogins:0,
                            disabled:"N", disabledDateTime:null, hasLoggedOut:"N"]
                    ecfi.serviceFacade.sync().name("update", "moqui.security.UserAccount").parameters(uaParameters).call()
                }

                // update visit if no user in visit yet
                EntityValue visit = ecfi.executionContext.user.visit
                if (visit) {
                    if (!visit.userId) {
                        ecfi.serviceFacade.sync().name("update", "moqui.server.Visit")
                                .parameters((Map<String, Object>) [visitId:visit.visitId, userId:userId]).call()
                    }
                    if (!visit.clientIpCountryGeoId && !visit.clientIpTimeZone) {
                        Node ssNode = (Node) ecfi.confXmlRoot."server-stats"[0]
                        if (ssNode.attribute("visit-ip-info-on-login") != "false") {
                            ecfi.serviceFacade.async().name("org.moqui.impl.ServerServices.get#VisitClientIpData")
                                    .parameter("visitId", visit.visitId).call()
                        }
                    }
                }
            } finally {
                if (!alreadyDisabled) ecfi.executionContext.artifactExecution.enableAuthz()
            }
        } finally {
            // track the UserLoginHistory, whether the above succeeded or failed (ie even if an exception was thrown)
            ExecutionContextImpl eci = ecfi.getEci()
            if (!eci.getSkipStats()) {
                Node loginNode = (Node) ecfi.confXmlRoot."user-facade"[0]."login"[0]
                if (userId != null && loginNode."@history-store" != "false") {
                    Timestamp fromDate = eci.getUser().getNowTimestamp()
                    EntityValue curUlh = ecfi.getEntityFacade().find("moqui.security.UserLoginHistory")
                            .condition([userId:userId, fromDate:fromDate]).disableAuthz().one()
                    if (curUlh == null) {
                        Map<String, Object> ulhContext = [userId:userId, fromDate:fromDate,
                                visitId:ecfi.executionContext.user.visitId, successfulLogin:(successful?"Y":"N")]
                        if (!successful && loginNode."@history-incorrect-password" != "false") ulhContext.passwordUsed = token.credentials
                        try {
                            ecfi.getServiceFacade().sync().name("create", "moqui.security.UserLoginHistory").parameters(ulhContext)
                                    .requireNewTransaction(true).disableAuthz().call()
                            // we want to ignore errors from this, may happen in high-volume inserts where we don't care about the records so much anyway
                            ecfi.getExecutionContext().getMessage().clearErrors()
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

        return info;
    }

    // ========== Authorization Methods ==========

    /**
     * @param principalCollection The principal (user)
     * @param resourceAccess Formatted as: "${typeEnumId}:${actionEnumId}:${name}"
     * @return boolean true if principal is permitted to access the resource, false otherwise.
     */
    boolean isPermitted(PrincipalCollection principalCollection, String resourceAccess) {
        String username = (String) principalCollection.primaryPrincipal
        return ArtifactExecutionFacadeImpl.isPermitted(username, resourceAccess, null, ecfi.eci)
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
            throw new UnauthorizedException("User ${username} does not have permission ${permission}")
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
            throw new UnauthorizedException("User ${principalCollection.primaryPrincipal} is not in role ${roleName}")
    }

    void checkRoles(PrincipalCollection principalCollection, Collection<String> roleNames) {
        for (String roleName in roleNames) {
            if (!this.hasRole(principalCollection, roleName))
                throw new UnauthorizedException("User ${principalCollection.primaryPrincipal} is not in role ${roleName}")
        }
    }

    void checkRoles(PrincipalCollection principalCollection, String... roleNames) {
        for (String roleName in roleNames) {
            if (!this.hasRole(principalCollection, roleName))
                throw new UnauthorizedException("User ${principalCollection.primaryPrincipal} is not in role ${roleName}")
        }
    }
}
