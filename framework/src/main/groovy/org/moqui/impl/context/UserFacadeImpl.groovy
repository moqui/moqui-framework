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
package org.moqui.impl.context

import groovy.transform.CompileStatic
import org.apache.commons.codec.binary.Base64
import org.moqui.context.NotificationMessage
import org.moqui.entity.EntityCondition
import org.moqui.impl.entity.EntityValueBase
import org.moqui.impl.util.MoquiShiroRealm
import org.moqui.util.MNode

import java.security.SecureRandom
import java.sql.Timestamp
import javax.servlet.http.Cookie
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import javax.servlet.http.HttpSession

import org.apache.shiro.authc.AuthenticationException
import org.apache.shiro.authc.UsernamePasswordToken
import org.apache.shiro.subject.Subject
import org.apache.shiro.web.subject.WebSubjectContext
import org.apache.shiro.web.subject.support.DefaultWebSubjectContext
import org.apache.shiro.web.session.HttpServletSession

import org.moqui.context.UserFacade
import org.moqui.entity.EntityValue
import org.moqui.entity.EntityList
import org.moqui.impl.entity.EntityListImpl
import org.apache.shiro.subject.support.DefaultSubjectContext

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class UserFacadeImpl implements UserFacade {
    protected final static Logger logger = LoggerFactory.getLogger(UserFacadeImpl.class)
    protected final static Set<String> allUserGroupIdOnly = new HashSet(["ALL_USERS"])

    protected ExecutionContextImpl eci
    protected Timestamp effectiveTime = (Timestamp) null

    protected UserInfo currentInfo
    protected Deque<UserInfo> userInfoStack = new LinkedList<UserInfo>()

    // there may be non-web visits, so keep a copy of the visitId here
    protected String visitId = (String) null

    // we mostly want this for the Locale default, and may be useful for other things
    protected HttpServletRequest request = (HttpServletRequest) null
    protected HttpServletResponse response = (HttpServletResponse) null

    UserFacadeImpl(ExecutionContextImpl eci) {
        this.eci = eci
        pushUser(null, eci.tenantId)
    }

    Subject makeEmptySubject() {
        if (request != null) {
            HttpSession session = request.getSession()
            WebSubjectContext wsc = new DefaultWebSubjectContext()
            wsc.setServletRequest(request); wsc.setServletResponse(response)
            wsc.setSession(new HttpServletSession(session, request.getServerName()))
            return eci.getEcfi().getSecurityManager().createSubject(wsc)
        } else {
            return eci.getEcfi().getSecurityManager().createSubject(new DefaultSubjectContext())
        }
    }

    void initFromHttpRequest(HttpServletRequest request, HttpServletResponse response) {
        this.request = request
        this.response = response
        HttpSession session = request.getSession()

        Subject webSubject = makeEmptySubject()
        if (webSubject.authenticated) {
            // effectively login the user
            pushUserSubject(webSubject, null)
            if (logger.traceEnabled) logger.trace("For new request found user [${username}] in the session")
        } else {
            if (logger.traceEnabled) logger.trace("For new request NO user authenticated in the session")
        }

        // check for HTTP Basic Authorization for Authentication purposes
        // NOTE: do this even if there is another user logged in, will go on stack
        Map secureParameters = eci.webImpl.getSecureRequestParameters()
        String authzHeader = request.getHeader("Authorization")
        if (authzHeader && authzHeader.substring(0, 6).equals("Basic ")) {
            String basicAuthEncoded = authzHeader.substring(6).trim()
            String basicAuthAsString = new String(basicAuthEncoded.decodeBase64())
            if (basicAuthAsString.indexOf(":") > 0) {
                String username = basicAuthAsString.substring(0, basicAuthAsString.indexOf(":"))
                String password = basicAuthAsString.substring(basicAuthAsString.indexOf(":") + 1)
                String tenantId = secureParameters.authTenantId
                this.loginUser(username, password, tenantId)
            } else {
                logger.warn("For HTTP Basic Authorization got bad credentials string. Base64 encoded is [${basicAuthEncoded}] and after decoding is [${basicAuthAsString}].")
            }
        } else if (request.getHeader("api_key") || request.getHeader("login_key")) {
            String loginKey = request.getHeader("api_key") ?: request.getHeader("login_key")
            String tenantId = request.getHeader("tenant_id")
            this.loginUserKey(loginKey.trim(), tenantId?.trim())
        } else if (secureParameters.api_key || secureParameters.login_key) {
            String loginKey = secureParameters.api_key ?: secureParameters.login_key
            String tenantId = secureParameters.tenant_id
            this.loginUserKey(loginKey.trim(), tenantId?.trim())
        } else if (secureParameters.authUsername) {
            // try the Moqui-specific parameters for instant login
            // if we have credentials coming in anywhere other than URL parameters, try logging in
            String authUsername = secureParameters.authUsername
            String authPassword = secureParameters.authPassword
            String authTenantId = secureParameters.authTenantId
            this.loginUser(authUsername, authPassword, authTenantId)
        }

        this.visitId = session.getAttribute("moqui.visitId")
        if (!this.visitId && !eci.getSkipStats()) {
            MNode serverStatsNode = eci.getEcfi().getServerStatsNode()

            // handle visitorId and cookie
            String cookieVisitorId = null
            if (serverStatsNode.attribute('visitor-enabled') != "false") {
                Cookie[] cookies = request.getCookies()
                if (cookies != null) {
                    for (int i = 0; i < cookies.length; i++) {
                        if (cookies[i].getName().equals("moqui.visitor")) {
                            cookieVisitorId = cookies[i].getValue()
                            break
                        }
                    }
                }
                if (cookieVisitorId) {
                    // make sure the Visitor record actually exists, if not act like we got no moqui.visitor cookie
                    EntityValue visitor = eci.entity.find("moqui.server.Visitor").condition("visitorId", cookieVisitorId).disableAuthz().one()
                    if (visitor == null) {
                        logger.info("Got invalid visitorId [${cookieVisitorId}] in moqui.visitor cookie in session [${session.id}], throwing away and making a new one")
                        cookieVisitorId = null
                    }
                }
                if (!cookieVisitorId) {
                    // NOTE: disable authz for this call, don't normally want to allow create of Visitor, but this is a special case
                    Map cvResult = eci.service.sync().name("create", "moqui.server.Visitor")
                            .parameter("createdDate", getNowTimestamp()).disableAuthz().call()
                    cookieVisitorId = (String) cvResult?.visitorId
                    logger.info("Created new Visitor with ID [${cookieVisitorId}] in session [${session.id}]")
                }
                if (cookieVisitorId) {
                    // whether it existed or not, add it again to keep it fresh; stale cookies get thrown away
                    Cookie visitorCookie = new Cookie("moqui.visitor", cookieVisitorId)
                    visitorCookie.setMaxAge(60 * 60 * 24 * 365)
                    visitorCookie.setPath("/")
                    response.addCookie(visitorCookie)
                }
            }

            if (serverStatsNode.attribute('visit-enabled') != "false") {
                // create and persist Visit
                String contextPath = session.getServletContext().getContextPath()
                String webappId = contextPath.length() > 1 ? contextPath.substring(1) : "ROOT"
                String fullUrl = eci.web.requestUrl
                fullUrl = (fullUrl.length() > 255) ? fullUrl.substring(0, 255) : fullUrl.toString()
                Map<String, Object> parameters = new HashMap<String, Object>([sessionId:session.id, webappName:webappId,
                        fromDate:new Timestamp(session.getCreationTime()),
                        initialLocale:getLocale().toString(), initialRequest:fullUrl,
                        initialReferrer:request.getHeader("Referrer")?:"",
                        initialUserAgent:request.getHeader("User-Agent")?:"",
                        clientHostName:request.getRemoteHost(), clientUser:request.getRemoteUser()])

                InetAddress address = eci.getEcfi().getLocalhostAddress()
                parameters.serverIpAddress = address?.getHostAddress() ?: "127.0.0.1"
                parameters.serverHostName = address?.getHostName() ?: "localhost"

                // handle proxy original address, if exists
                if (request.getHeader("X-Forwarded-For")) {
                    parameters.clientIpAddress = request.getHeader("X-Forwarded-For")
                } else {
                    parameters.clientIpAddress = request.getRemoteAddr()
                }
                if (cookieVisitorId) parameters.visitorId = cookieVisitorId

                // NOTE: disable authz for this call, don't normally want to allow create of Visit, but this is special case
                Map visitResult = eci.service.sync().name("create", "moqui.server.Visit").parameters(parameters)
                        .disableAuthz().call()
                // put visitId in session as "moqui.visitId"
                if (visitResult) {
                    session.setAttribute("moqui.visitId", visitResult.visitId)
                    this.visitId = visitResult.visitId
                    logger.info("Created new Visit with ID [${this.visitId}] in session [${session.id}]")
                }
            }
        }
    }

    @Override
    Locale getLocale() { return currentInfo.localeCache }

    @Override
    void setLocale(Locale locale) {
        if (currentInfo.userAccount != null) {
            eci.transaction.runUseOrBegin(null, "Error saving locale", {
                boolean alreadyDisabled = eci.getArtifactExecution().disableAuthz()
                try {
                    EntityValue userAccountClone = currentInfo.userAccount.cloneValue()
                    userAccountClone.set("locale", locale.toString())
                    userAccountClone.update()
                } finally { if (!alreadyDisabled) eci.getArtifactExecution().enableAuthz() }
            })
        }
        currentInfo.localeCache = locale
    }

    @Override
    TimeZone getTimeZone() { return currentInfo.tzCache }

    Calendar getCalendarSafe() {
        return Calendar.getInstance(currentInfo.tzCache ?: TimeZone.getDefault(),
                currentInfo.localeCache ?: (request ? request.getLocale() : Locale.getDefault()))
    }


    @Override
    void setTimeZone(TimeZone tz) {
        if (currentInfo.userAccount != null) {
            eci.transaction.runUseOrBegin(null, "Error saving timeZone", {
                boolean alreadyDisabled = eci.getArtifactExecution().disableAuthz()
                try {
                    EntityValue userAccountClone = currentInfo.userAccount.cloneValue()
                    userAccountClone.set("timeZone", tz.getID())
                    userAccountClone.update()
                } finally { if (!alreadyDisabled) eci.getArtifactExecution().enableAuthz() }
            })
        }
        currentInfo.tzCache = tz
    }

    @Override
    String getCurrencyUomId() { return currentInfo.currencyUomId }

    @Override
    void setCurrencyUomId(String uomId) {
        if (currentInfo.userAccount != null) {
            eci.transaction.runUseOrBegin(null, "Error saving currencyUomId", {
                boolean alreadyDisabled = eci.getArtifactExecution().disableAuthz()
                try {
                    EntityValue userAccountClone = currentInfo.userAccount.cloneValue()
                    userAccountClone.set("currencyUomId", uomId)
                    userAccountClone.update()
                } finally { if (!alreadyDisabled) eci.getArtifactExecution().enableAuthz() }
            })
        }
        currentInfo.currencyUomId = uomId
    }

    @Override
    String getPreference(String preferenceKey) {
        String userId = getUserId()
        if (!userId) return null
        return getPreference(preferenceKey, userId)
    }

    String getPreference(String preferenceKey, String userId) {
        EntityValue up = eci.getEntity().find("moqui.security.UserPreference").condition("userId", userId)
                .condition("preferenceKey", preferenceKey).useCache(true).disableAuthz().one()
        if (up == null) {
            // try UserGroupPreference
            EntityList ugpList = eci.getEntity().find("moqui.security.UserGroupPreference")
                    .condition("userGroupId", EntityCondition.IN, getUserGroupIdSet(userId))
                    .condition("preferenceKey", preferenceKey).useCache(true).disableAuthz().list()
            if (ugpList) up = ugpList.first
        }
        return up?.preferenceValue
    }

    @Override
    void setPreference(String preferenceKey, String preferenceValue) {
        String userId = getUserId()
        if (!userId) throw new IllegalStateException("Cannot set preference with key [${preferenceKey}], no user logged in.")
        boolean alreadyDisabled = eci.getArtifactExecution().disableAuthz()
        boolean beganTransaction = eci.transaction.begin(null)
        try {
            eci.getEntity().makeValue("moqui.security.UserPreference").set("userId", getUserId())
                    .set("preferenceKey", preferenceKey).set("preferenceValue", preferenceValue).createOrUpdate()
        } catch (Throwable t) {
            eci.transaction.rollback(beganTransaction, "Error saving UserPreference", t)
        } finally {
            if (eci.transaction.isTransactionInPlace()) eci.transaction.commit(beganTransaction)
            if (!alreadyDisabled) eci.getArtifactExecution().enableAuthz()
        }
    }

    @Override
    Map<String, Object> getContext() { return currentInfo.getUserContext() }

    @Override
    Timestamp getNowTimestamp() {
        // NOTE: review Timestamp and nowTimestamp use, have things use this by default (except audit/etc where actual date/time is needed
        return ((Object) this.effectiveTime != null) ? this.effectiveTime : new Timestamp(System.currentTimeMillis())
    }

    @Override
    Calendar getNowCalendar() {
        Calendar nowCal = getCalendarSafe()
        nowCal.setTimeInMillis(getNowTimestamp().getTime())
        return nowCal
    }

    @Override
    List<Timestamp> getPeriodRange(String period, String poffset) {
        period = (period ?: "day").toLowerCase()
        int offset = (poffset ?: "0") as int

        Calendar basisCal = getCalendarSafe()
        basisCal.set(Calendar.HOUR_OF_DAY, 0); basisCal.set(Calendar.MINUTE, 0);
        basisCal.set(Calendar.SECOND, 0); basisCal.set(Calendar.MILLISECOND, 0);
        // this doesn't seem to work to set the time to midnight: basisCal.setTime(new java.sql.Date(nowTimestamp.time))
        Calendar fromCal = basisCal
        Calendar thruCal
        if (period == "week") {
            fromCal.set(Calendar.DAY_OF_WEEK, fromCal.getFirstDayOfWeek())
            fromCal.add(Calendar.WEEK_OF_YEAR, offset)
            thruCal = (Calendar) fromCal.clone()
            thruCal.add(Calendar.WEEK_OF_YEAR, 1)
        } else if (period == "month") {
            fromCal.set(Calendar.DAY_OF_MONTH, fromCal.getActualMinimum(Calendar.DAY_OF_MONTH))
            fromCal.add(Calendar.MONTH, offset)
            thruCal = (Calendar) fromCal.clone()
            thruCal.add(Calendar.MONTH, 1)
        } else if (period == "year") {
            fromCal.set(Calendar.DAY_OF_YEAR, fromCal.getActualMinimum(Calendar.DAY_OF_YEAR))
            fromCal.add(Calendar.YEAR, offset)
            thruCal = (Calendar) fromCal.clone()
            thruCal.add(Calendar.YEAR, 1)
        } else {
            // default to day
            fromCal.add(Calendar.DAY_OF_YEAR, offset)
            thruCal = (Calendar) fromCal.clone()
            thruCal.add(Calendar.DAY_OF_YEAR, 1)
        }

        return [new Timestamp(fromCal.getTimeInMillis()), new Timestamp(thruCal.getTimeInMillis())]
    }

    @Override
    void setEffectiveTime(Timestamp effectiveTime) { this.effectiveTime = effectiveTime }

    @Override
    boolean loginUser(String username, String password, String tenantId) {
        if (!username) {
            eci.message.addError("No username specified")
            return false
        }
        if (!password) {
            eci.message.addError("No password specified")
            return false
        }
        if (!tenantId) tenantId = eci.tenantId
        if (tenantId && tenantId != eci.tenantId) {
            eci.changeTenant(tenantId)
            this.visitId = null
            if (eci.web != null) eci.web.session.removeAttribute("moqui.visitId")
        }

        UsernamePasswordToken token = new UsernamePasswordToken(username, password)
        token.rememberMe = true
        Subject loginSubject = makeEmptySubject()
        try {
            loginSubject.login(token)

            // do this first so that the rest will be done as this user
            // just in case there is already a user authenticated push onto a stack to remember
            pushUserSubject(loginSubject, tenantId)

            // after successful login trigger the after-login actions
            if (eci.getWebImpl() != null) {
                eci.getWebImpl().runAfterLoginActions()
                eci.getWebImpl().getRequest().setAttribute("moqui.request.authenticated", "true")
            }
        } catch (AuthenticationException ae) {
            // others to consider handling differently (these all inherit from AuthenticationException):
            //     UnknownAccountException, IncorrectCredentialsException, ExpiredCredentialsException,
            //     CredentialsException, LockedAccountException, DisabledAccountException, ExcessiveAttemptsException
            eci.message.addError(ae.message)
            logger.warn("Login failure: ${eci.message.errorsString}", ae)
            return false
        }

        return true
    }

    /** For internal framework use only, does a login without authc. */
    boolean internalLoginUser(String username, String tenantId) {
        if (!username) {
            eci.message.addError("No username specified")
            return false
        }
        if (!tenantId) tenantId = eci.tenantId
        if (tenantId && tenantId != eci.tenantId) {
            eci.changeTenant(tenantId)
            this.visitId = null
            if (eci.web != null) eci.web.session.removeAttribute("moqui.visitId")
        }

        // since this doesn't go through the Shiro realm and do validations, do them now
        try {
            EntityValue newUserAccount = MoquiShiroRealm.loginPrePassword(eci, username)
            MoquiShiroRealm.loginPostPassword(eci, newUserAccount)
            // don't save the history, this is used for async/scheduled service calls and often has ms time conflicts
            // also used in REST and other API calls with login key, high volume and better not to save
            // MoquiShiroRealm.loginAfterAlways(eci, (String) newUserAccount.userId, null, true)
        } catch (AuthenticationException ae) {
            // others to consider handling differently (these all inherit from AuthenticationException):
            //     UnknownAccountException, IncorrectCredentialsException, ExpiredCredentialsException,
            //     CredentialsException, LockedAccountException, DisabledAccountException, ExcessiveAttemptsException
            eci.message.addError(ae.message)
            logger.warn("Login failure: ${eci.message.errorsString}", ae)
            return false
        }

        // do this first so that the rest will be done as this user
        // just in case there is already a user authenticated push onto a stack to remember
        pushUser(username, tenantId)

        // after successful login trigger the after-login actions
        if (eci.getWebImpl() != null) eci.getWebImpl().runAfterLoginActions()

        return true
    }

    @Override
    void logoutUser() {
        // before logout trigger the before-logout actions
        if (eci.getWebImpl() != null) eci.getWebImpl().runBeforeLogoutActions()

        popUser()

        if (eci.web != null) {
            eci.web.session.removeAttribute("moqui.tenantId")
            eci.web.session.removeAttribute("moqui.visitId")
        }
    }

    @Override
    boolean loginUserKey(String loginKey, String tenantId) {
        if (!loginKey) {
            eci.message.addError("No login key specified")
            return false
        }
        // if tenantId, change before lookup
        if (tenantId) eci.changeTenant(tenantId)

        // lookup login key, by hashed key
        String hashedKey = eci.ecfi.getSimpleHash(loginKey, "", eci.ecfi.getLoginKeyHashType())
        EntityValue userLoginKey = eci.getEntity().find("moqui.security.UserLoginKey")
                .condition("loginKey", hashedKey).disableAuthz().one()

        // see if we found a record for the login key
        if (userLoginKey == null) {
            eci.message.addError("Login key not valid")
            return false
        }

        // check expire date
        Timestamp nowDate = getNowTimestamp()
        if (nowDate > userLoginKey.getTimestamp("thruDate")) {
            eci.message.addError("Login key expired")
            return false
        }

        // login user with internalLoginUser()
        EntityValue userAccount = eci.getEntity().find("moqui.security.UserAccount")
                .condition("userId", userLoginKey.userId).disableAuthz().one()
        return internalLoginUser(userAccount.getString("username"), tenantId)
    }
    @Override
    String getLoginKey() {
        String userId = getUserId()
        if (!userId) throw new IllegalStateException("No active user, cannot get login key")

        // generate login key
        SecureRandom sr = new SecureRandom()
        byte[] randomBytes = new byte[30]
        sr.nextBytes(randomBytes)
        String loginKey = Base64.encodeBase64URLSafeString(randomBytes)

        // save hashed in UserLoginKey, calc expire and set from/thru dates
        String hashedKey = eci.ecfi.getSimpleHash(loginKey, "", eci.ecfi.getLoginKeyHashType())
        int expireHours = eci.ecfi.getLoginKeyExpireHours()
        Timestamp fromDate = getNowTimestamp()
        long thruTime = fromDate.getTime() + (expireHours * 60*60*1000)
        eci.service.sync().name("create", "moqui.security.UserLoginKey")
                .parameters([loginKey:hashedKey, userId:userId, fromDate:fromDate, thruDate:new Timestamp(thruTime)])
                .disableAuthz().call()

        // clean out expired keys
        eci.entity.find("moqui.security.UserLoginKey").condition("userId", userId)
                .condition("thruDate", "less-than", fromDate).disableAuthz().deleteAll()

        return loginKey
    }

    @Override
    boolean loginAnonymousIfNoUser() {
        if (currentInfo.username == null && !currentInfo.loggedInAnonymous) {
            currentInfo.loggedInAnonymous = true
            return true
        } else {
            return false
        }
    }
    void logoutAnonymousOnly() { currentInfo.loggedInAnonymous = false }
    boolean getLoggedInAnonymous() { return currentInfo.loggedInAnonymous }

    @Override
    boolean hasPermission(String userPermissionId) { return hasPermissionById(getUserId(), userPermissionId, getNowTimestamp(), eci) }

    static boolean hasPermission(String username, String userPermissionId, Timestamp whenTimestamp, ExecutionContextImpl eci) {
        EntityValue ua = eci.getEntity().find("moqui.security.UserAccount").condition("userId", username).useCache(true).disableAuthz().one()
        if (ua == null) ua = eci.getEntity().find("moqui.security.UserAccount").condition("username", username).useCache(true).disableAuthz().one()
        if (ua == null) return false
        hasPermissionById((String) ua.userId, userPermissionId, whenTimestamp, eci)
    }
    static boolean hasPermissionById(String userId, String userPermissionId, Timestamp whenTimestamp, ExecutionContextImpl eci) {
        if (!userId) return false
        if ((Object) whenTimestamp == null) whenTimestamp = new Timestamp(System.currentTimeMillis())
        return (eci.getEntity().find("moqui.security.UserPermissionCheck")
                .condition([userId:userId, userPermissionId:userPermissionId] as Map<String, Object>).useCache(true).disableAuthz().list()
                .filterByDate("groupFromDate", "groupThruDate", whenTimestamp)
                .filterByDate("permissionFromDate", "permissionThruDate", whenTimestamp)) as boolean
    }

    @Override
    boolean isInGroup(String userGroupId) { return isInGroup(getUserId(), userGroupId, getNowTimestamp(), eci) }

    static boolean isInGroup(String username, String userGroupId, Timestamp whenTimestamp, ExecutionContextImpl eci) {
        EntityValue ua = eci.getEntity().find("moqui.security.UserAccount").condition("userId", username).useCache(true).disableAuthz().one()
        if (ua == null) ua = eci.getEntity().find("moqui.security.UserAccount").condition("username", username).useCache(true).disableAuthz().one()
        return isInGroupById((String) ua?.userId, userGroupId, whenTimestamp, eci)
    }
    static boolean isInGroupById(String userId, String userGroupId, Timestamp whenTimestamp, ExecutionContextImpl eci) {
        if (userGroupId == "ALL_USERS") return true
        if (!userId) return false
        if ((Object) whenTimestamp == null) whenTimestamp = new Timestamp(System.currentTimeMillis())
        return (eci.getEntity().find("moqui.security.UserGroupMember").condition([userId:userId, userGroupId:userGroupId] as Map<String, Object>)
                .useCache(true).disableAuthz().list().filterByDate("fromDate", "thruDate", whenTimestamp)) as boolean
    }

    @Override
    Set<String> getUserGroupIdSet() {
        // first get the groups the user is in (cached), always add the "ALL_USERS" group to it
        if (!currentInfo.userId) return allUserGroupIdOnly
        if (currentInfo.internalUserGroupIdSet == null) currentInfo.internalUserGroupIdSet = getUserGroupIdSet(currentInfo.userId)
        return currentInfo.internalUserGroupIdSet
    }

    Set<String> getUserGroupIdSet(String userId) {
        Set<String> groupIdSet = new HashSet(allUserGroupIdOnly)
        if (userId) {
            // expand the userGroupId Set with UserGroupMember
            EntityList ugmList = eci.getEntity().find("moqui.security.UserGroupMember").condition("userId", userId)
                    .useCache(true).disableAuthz().list().filterByDate(null, null, null)
            for (EntityValue userGroupMember in ugmList) groupIdSet.add((String) userGroupMember.userGroupId)
        }
        return groupIdSet
    }

    EntityList getArtifactTarpitCheckList() {
        if (currentInfo.internalArtifactTarpitCheckList == null) {
            // get the list for each group separately to increase cache hits/efficiency
            currentInfo.internalArtifactTarpitCheckList = new EntityListImpl(eci.getEcfi().getEntityFacade())
            for (String userGroupId in getUserGroupIdSet()) {
                currentInfo.internalArtifactTarpitCheckList.addAll(eci.getEntity().find("moqui.security.ArtifactTarpitCheckView")
                        .condition("userGroupId", userGroupId).useCache(true).disableAuthz().list())
            }
        }
        return currentInfo.internalArtifactTarpitCheckList
    }

    EntityList getArtifactAuthzCheckList() {
        // NOTE: even if there is no user, still consider part of the ALL_USERS group and such: if (usernameStack.size() == 0) return EntityListImpl.EMPTY
        if (currentInfo.internalArtifactAuthzCheckList == null) {
            // get the list for each group separately to increase cache hits/efficiency
            currentInfo.internalArtifactAuthzCheckList = new EntityListImpl(eci.getEcfi().getEntityFacade())
            for (String userGroupId in getUserGroupIdSet()) {
                currentInfo.internalArtifactAuthzCheckList.addAll(eci.getEntity().find("moqui.security.ArtifactAuthzCheckView")
                        .condition("userGroupId", userGroupId).useCache(true).disableAuthz().list())
            }
        }
        return currentInfo.internalArtifactAuthzCheckList
    }

    @Override
    String getUserId() { return currentInfo.userId }

    @Override
    String getUsername() { return currentInfo.username }

    @Override
    EntityValue getUserAccount() { return currentInfo.getUserAccount() }

    @Override
    String getVisitUserId() { return visitId ? getVisit().userId : null }

    @Override
    String getVisitId() { return visitId }

    @Override
    EntityValue getVisit() {
        if (!visitId) return null
        EntityValue vst = eci.getEntity().find("moqui.server.Visit").condition("visitId", visitId).useCache(true).disableAuthz().one()
        return vst
    }

    @Override
    List<NotificationMessage> getNotificationMessages(String topic) {
        if (!currentInfo.userId) return []
        return eci.getNotificationMessages(currentInfo.userId, topic)
    }

    // ========== UserInfo ==========

    UserInfo pushUserSubject(Subject subject, String tenantId) {
        UserInfo userInfo = pushUser((String) subject.getPrincipal(), tenantId)
        userInfo.subject = subject
        return userInfo
    }
    UserInfo pushUser(String username, String tenantId) {
        if (currentInfo != null && currentInfo.username == username && currentInfo.tenantId == tenantId)
            return currentInfo

        if (currentInfo == null || currentInfo.isPopulated()) {
            // logger.info("Pushing UserInfo for ${username}:${tenantId} to stack, was ${currentInfo.username}:${currentInfo.tenantId}")
            UserInfo userInfo = new UserInfo(this, username, tenantId)
            userInfoStack.addFirst(userInfo)
            currentInfo = userInfo
            return userInfo
        } else {
            currentInfo.setInfo(username, tenantId)
            return currentInfo
        }
    }
    void popUser() {
        if (currentInfo.subject != null && currentInfo.subject.isAuthenticated()) currentInfo.subject.logout()
        userInfoStack.removeFirst()

        // always leave at least an empty UserInfo on the stack
        if (userInfoStack.size() == 0) userInfoStack.addFirst(new UserInfo(this, null, null))

        UserInfo newCurInfo = userInfoStack.getFirst()
        // logger.info("Popping UserInfo ${currentInfo.username}:${currentInfo.tenantId}, new current is ${newCurInfo.username}:${newCurInfo.tenantId}")

        // whether previous user on stack or new one, set the currentInfo
        currentInfo = newCurInfo
    }

    /** Called by ExecutionContextInfo when tenant pushed (changeTenant()) */
    void pushTenant(String toTenantId) {
        UserInfo wasInfo = currentInfo
        // if there is a previous user populated and it is not in the toTenantId tenant, push an empty UserInfo
        if (currentInfo.tenantId != toTenantId) pushUser(null, toTenantId)

        // logger.info("UserFacade pushed tenant: from ${wasInfo.tenantId} to ${currentInfo.tenantId}, user was ${wasInfo.username} and is ${currentInfo.username}")
    }
    /** Called by ExecutionContextInfo when tenant popped (popTenant()) */
    void popTenant(String fromTenantId) {
        UserInfo wasInfo = currentInfo
        // pop current user (if populated effectively logs out, if not will get an empty user in current tenant, already set in eci)
        if (currentInfo.tenantId == fromTenantId) popUser()

        // logger.info("UserFacade popped tenant: ${wasInfo.tenantId} to ${currentInfo.tenantId}, user was ${wasInfo.username} and is ${currentInfo.username}")
    }

    static class UserInfo {
        UserFacadeImpl ufi
        // keep a reference to a UserAccount for performance reasons, avoid repeated cached queries
        protected EntityValueBase userAccount = (EntityValueBase) null
        protected String tenantId = (String) null
        protected String username = (String) null
        protected String userId = (String) null
        Set<String> internalUserGroupIdSet = (Set<String>) null
        // these two are used by ArtifactExecutionFacadeImpl but are maintained here to be cleared when user changes, are based on current user's groups
        EntityList internalArtifactTarpitCheckList = (EntityList) null
        EntityList internalArtifactAuthzCheckList = (EntityList) null

        Locale localeCache = (Locale) null
        TimeZone tzCache = (TimeZone) null
        String currencyUomId = (String) null

        /** The Shiro Subject (user) */
        Subject subject = (Subject) null
        /** This is set instead of adding _NA_ user as logged in to pass authc tests but not generally behave as if a user is logged in */
        boolean loggedInAnonymous = false

        protected Map<String, Object> userContext = (Map<String, Object>) null

        UserInfo(UserFacadeImpl ufi, String username, String tenantId) {
            this.ufi = ufi
            setInfo(username, tenantId)
        }

        boolean isPopulated() { return (username != null && username.length() > 0) || loggedInAnonymous }

        void setInfo(String username, String tenantId) {
            // this shouldn't happen unless there is a bug in the framework
            if (isPopulated()) throw new IllegalStateException("Cannot set user info, UserInfo already populated")

            this.username = username
            this.tenantId = tenantId ?: ufi.eci.tenantId

            EntityValueBase ua = (EntityValueBase) null
            if (username != null && username.length() > 0) {
                ua = (EntityValueBase) ufi.eci.getEntity().find("moqui.security.UserAccount")
                        .condition("username", username).useCache(true).disableAuthz().one()
            }
            if (ua != null) {
                userAccount = ua
                this.username = ua.username
                userId = ua.userId

                String localeStr = ua.locale
                if (localeStr) {
                    localeCache = localeStr.contains("_") ?
                        new Locale(localeStr.substring(0, localeStr.indexOf("_")), localeStr.substring(localeStr.indexOf("_")+1).toUpperCase()) :
                        new Locale(localeStr)
                } else {
                    localeCache = ufi.request != null ? ufi.request.getLocale() : Locale.getDefault()
                }

                String tzStr = ua.timeZone
                tzCache = tzStr ? TimeZone.getTimeZone(tzStr) : TimeZone.getDefault()

                currencyUomId = userAccount.currencyUomId
            } else {
                // set defaults if no user
                localeCache = ufi.request != null ? ufi.request.getLocale() : Locale.getDefault()
                tzCache = TimeZone.getDefault()
            }
        }

        String getUsername() { return username }
        String getUserId() { return userId }
        EntityValueBase getUserAccount() { return userAccount }

        Map<String, Object> getUserContext() {
            if (userContext == null) userContext = new HashMap<>()
            return userContext
        }
    }
}
