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
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.entity.EntityCondition
import org.moqui.impl.context.ArtifactExecutionInfoImpl.ArtifactAuthzCheck
import org.moqui.impl.entity.EntityValueBase
import org.moqui.impl.screen.ScreenUrlInfo
import org.moqui.impl.util.MoquiShiroRealm
import org.moqui.util.MNode
import org.moqui.util.StringUtilities
import org.moqui.util.WebUtilities

import javax.websocket.server.HandshakeRequest
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
    protected EntityValue visitInternal = (EntityValue) null
    protected String visitorIdInternal = (String) null

    // we mostly want this for the Locale default, and may be useful for other things
    protected HttpServletRequest request = (HttpServletRequest) null
    protected HttpServletResponse response = (HttpServletResponse) null
    protected HttpSession session = (HttpSession) null

    UserFacadeImpl(ExecutionContextImpl eci) {
        this.eci = eci
        pushUser(null)
    }

    Subject makeEmptySubject() {
        if (session != null) {
            WebSubjectContext wsc = new DefaultWebSubjectContext()
            if (request != null) wsc.setServletRequest(request)
            if (response != null) wsc.setServletResponse(response)
            wsc.setSession(new HttpServletSession(session, request?.getServerName()))
            return eci.ecfi.getSecurityManager().createSubject(wsc)
        } else {
            return eci.ecfi.getSecurityManager().createSubject(new DefaultSubjectContext())
        }
    }

    void initFromHttpRequest(HttpServletRequest request, HttpServletResponse response) {
        this.request = request
        this.response = response
        this.session = request.getSession()

        Subject webSubject = makeEmptySubject()
        if (webSubject.authenticated) {
            // effectively login the user
            pushUserSubject(webSubject)
            if (logger.traceEnabled) logger.trace("For new request found user [${username}] in the session")
        } else {
            if (logger.traceEnabled) logger.trace("For new request NO user authenticated in the session")
        }

        // check for HTTP Basic Authorization for Authentication purposes
        // NOTE: do this even if there is another user logged in, will go on stack
        Map secureParameters = eci.webImpl != null ? eci.webImpl.getSecureRequestParameters() :
                WebUtilities.simplifyRequestParameters(request, true)
        String authzHeader = request.getHeader("Authorization")
        if (authzHeader != null && authzHeader.length() > 6 && authzHeader.startsWith("Basic ")) {
            String basicAuthEncoded = authzHeader.substring(6).trim()
            String basicAuthAsString = new String(basicAuthEncoded.decodeBase64())
            int indexOfColon = basicAuthAsString.indexOf(":")
            if (indexOfColon > 0) {
                String username = basicAuthAsString.substring(0, indexOfColon)
                String password = basicAuthAsString.substring(indexOfColon + 1)
                this.loginUser(username, password)
            } else {
                logger.warn("For HTTP Basic Authorization got bad credentials string. Base64 encoded is [${basicAuthEncoded}] and after decoding is [${basicAuthAsString}].")
            }
        } else if (request.getHeader("api_key") || request.getHeader("login_key")) {
            String loginKey = request.getHeader("api_key") ?: request.getHeader("login_key")
            this.loginUserKey(loginKey.trim())
        } else if (secureParameters.api_key || secureParameters.login_key) {
            String loginKey = secureParameters.api_key ?: secureParameters.login_key
            this.loginUserKey(loginKey.trim())
        } else if (secureParameters.authUsername) {
            // try the Moqui-specific parameters for instant login
            // if we have credentials coming in anywhere other than URL parameters, try logging in
            String authUsername = secureParameters.authUsername
            String authPassword = secureParameters.authPassword
            this.loginUser(authUsername, authPassword)
        }
        if (eci.messageFacade.hasError()) request.setAttribute("moqui.login.error", "true")

        this.visitId = session.getAttribute("moqui.visitId")
        // NOTE: only tracking Visitor and Visit if there is a WebFacadeImpl in place
        if (eci.webImpl != null && !this.visitId && !eci.getSkipStats()) {
            MNode serverStatsNode = eci.ecfi.getServerStatsNode()
            ScreenUrlInfo sui = ScreenUrlInfo.getScreenUrlInfo(eci.screenFacade, request)
            // before doing anything with the visit, etc make sure exists
            sui.checkExists()
            boolean isJustContent = sui.fileResourceRef != null

            // handle visitorId and cookie
            String cookieVisitorId = (String) null
            if (!isJustContent && !"false".equals(serverStatsNode.attribute('visitor-enabled'))) {
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
            visitorIdInternal = cookieVisitorId

            if (!isJustContent && !"false".equals(serverStatsNode.attribute('visit-enabled'))) {
                // create and persist Visit
                String contextPath = session.getServletContext().getContextPath()
                String webappId = contextPath.length() > 1 ? contextPath.substring(1) : "ROOT"
                String fullUrl = eci.webImpl.requestUrl
                fullUrl = (fullUrl.length() > 255) ? fullUrl.substring(0, 255) : fullUrl.toString()
                Map<String, Object> parameters = new HashMap<String, Object>([sessionId:session.id, webappName:webappId,
                        fromDate:new Timestamp(session.getCreationTime()),
                        initialLocale:getLocale().toString(), initialRequest:fullUrl,
                        initialReferrer:request.getHeader("Referrer")?:"",
                        initialUserAgent:request.getHeader("User-Agent")?:"",
                        clientHostName:request.getRemoteHost(), clientUser:request.getRemoteUser()])

                InetAddress address = eci.ecfi.getLocalhostAddress()
                parameters.serverIpAddress = address?.getHostAddress() ?: "127.0.0.1"
                parameters.serverHostName = address?.getHostName() ?: "localhost"

                // handle proxy original address, if exists
                String forwardedFor = request.getHeader("X-Forwarded-For")
                if (forwardedFor != null && !forwardedFor.isEmpty()) {
                    parameters.clientIpAddress = forwardedFor.split(",")[0].trim()
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
    void initFromHandshakeRequest(HandshakeRequest request) {
        this.session = (HttpSession) request.getHttpSession()

        // WebSocket handshake request is the HTTP upgrade request so this will be the original session
        // login user from value in session
        Subject webSubject = makeEmptySubject()
        if (webSubject.authenticated) {
            // effectively login the user
            pushUserSubject(webSubject)
            if (logger.traceEnabled) logger.trace("For new request found user [${username}] in the session")
        } else {
            if (logger.traceEnabled) logger.trace("For new request NO user authenticated in the session")
        }

        Map<String, List<String>> headers = request.getHeaders()
        Map<String, List<String>> parameters = request.getParameterMap()
        String authzHeader = headers.get("Authorization") ? headers.get("Authorization").get(0) : null
        if (authzHeader != null && authzHeader.length() > 6 && authzHeader.substring(0, 6).equals("Basic ")) {
            String basicAuthEncoded = authzHeader.substring(6).trim()
            String basicAuthAsString = new String(basicAuthEncoded.decodeBase64())
            if (basicAuthAsString.indexOf(":") > 0) {
                String username = basicAuthAsString.substring(0, basicAuthAsString.indexOf(":"))
                String password = basicAuthAsString.substring(basicAuthAsString.indexOf(":") + 1)
                this.loginUser(username, password)
            } else {
                logger.warn("For HTTP Basic Authorization got bad credentials string. Base64 encoded is [${basicAuthEncoded}] and after decoding is [${basicAuthAsString}].")
            }
        } else if (headers.api_key || headers.login_key) {
            String loginKey = headers.api_key ? headers.api_key.get(0) : (headers.login_key ? headers.login_key.get(0) : null)
            if (loginKey) this.loginUserKey(loginKey.trim())
        } else if (parameters.api_key || parameters.login_key) {
            String loginKey = parameters.api_key ? parameters.api_key.get(0) : (parameters.login_key ? parameters.login_key.get(0) : null)
            if (loginKey) this.loginUserKey(loginKey.trim())
        } else if (parameters.authUsername) {
            // try the Moqui-specific parameters for instant login
            // if we have credentials coming in anywhere other than URL parameters, try logging in
            String authUsername = parameters.authUsername.get(0)
            String authPassword = parameters.authPassword ? parameters.authPassword.get(0) : null
            this.loginUser(authUsername, authPassword)
        }
    }
    void initFromHttpSession(HttpSession session) {
        this.session = session
        Subject webSubject = makeEmptySubject()
        if (webSubject.authenticated) {
            // effectively login the user
            pushUserSubject(webSubject)
            if (logger.traceEnabled) logger.trace("For new request found user [${username}] in the session")
        } else {
            if (logger.traceEnabled) logger.trace("For new request NO user authenticated in the session")
        }
    }


    @Override Locale getLocale() { return currentInfo.localeCache }
    @Override void setLocale(Locale locale) {
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

    @Override TimeZone getTimeZone() { return currentInfo.tzCache }
    Calendar getCalendarSafe() {
        return Calendar.getInstance(currentInfo.tzCache != null ? currentInfo.tzCache : TimeZone.getDefault(),
                currentInfo.localeCache != null ? currentInfo.localeCache :
                        (request != null ? request.getLocale() : Locale.getDefault()))
    }
    @Override void setTimeZone(TimeZone tz) {
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

    @Override String getCurrencyUomId() { return currentInfo.currencyUomId }
    @Override void setCurrencyUomId(String uomId) {
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

    @Override String getPreference(String preferenceKey) {
        String userId = getUserId()
        return getPreference(preferenceKey, userId)
    }
    String getPreference(String preferenceKey, String userId) {
        EntityValue up = userId != null ? eci.entityFacade.fastFindOne("moqui.security.UserPreference", true, true, userId, preferenceKey) : null
        if (up == null) {
            // try UserGroupPreference
            EntityList ugpList = eci.getEntity().find("moqui.security.UserGroupPreference")
                    .condition("userGroupId", EntityCondition.IN, getUserGroupIdSet(userId))
                    .condition("preferenceKey", preferenceKey).useCache(true).disableAuthz().list()
            if (ugpList != null && ugpList.size() > 0) up = ugpList.get(0)
        }
        return up?.preferenceValue
    }

    @Override void setPreference(String preferenceKey, String preferenceValue) {
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

    @Override Map<String, Object> getContext() { return currentInfo.getUserContext() }

    @Override Timestamp getNowTimestamp() {
        // NOTE: review Timestamp and nowTimestamp use, have things use this by default (except audit/etc where actual date/time is needed
        return ((Object) this.effectiveTime != null) ? this.effectiveTime : new Timestamp(System.currentTimeMillis())
    }

    @Override Calendar getNowCalendar() {
        Calendar nowCal = getCalendarSafe()
        nowCal.setTimeInMillis(getNowTimestamp().getTime())
        return nowCal
    }

    @Override ArrayList<Timestamp> getPeriodRange(String period, String poffset) { return getPeriodRange(period, poffset, null) }
    @Override ArrayList<Timestamp> getPeriodRange(String period, String poffset, String pdate) {
        int offset = (poffset ?: "0") as int
        java.sql.Date sqlDate = (pdate != null && !pdate.isEmpty()) ? eci.l10nFacade.parseDate(pdate, null) : null
        return getPeriodRange(period, offset, sqlDate)
    }
    @Override ArrayList<Timestamp> getPeriodRange(String period, int offset, java.sql.Date sqlDate) {
        period = (period ?: "day").toLowerCase()
        boolean perIsNumber = Character.isDigit(period.charAt(0))

        Calendar basisCal = getCalendarSafe()
        if (sqlDate != null) basisCal.setTimeInMillis(sqlDate.getTime())
        basisCal.set(Calendar.HOUR_OF_DAY, 0); basisCal.set(Calendar.MINUTE, 0)
        basisCal.set(Calendar.SECOND, 0); basisCal.set(Calendar.MILLISECOND, 0)
        // this doesn't seem to work to set the time to midnight: basisCal.setTime(new java.sql.Date(nowTimestamp.time))
        Calendar fromCal = (Calendar) basisCal.clone()
        Calendar thruCal
        if (perIsNumber && period.endsWith("d")) {
            int days = Integer.parseInt(period.substring(0, period.length() - 1))
            if (offset < 0) {
                fromCal.add(Calendar.DAY_OF_YEAR, offset * days)
                thruCal = (Calendar) basisCal.clone()
                // also include today (or anchor date in pdate)
                thruCal.add(Calendar.DAY_OF_YEAR, 1)
            } else {
                // fromCal already set to basisCal, just set thruCal
                thruCal = (Calendar) basisCal.clone()
                thruCal.add(Calendar.DAY_OF_YEAR, (offset + 1) * days)
            }
        } else if (perIsNumber && period.endsWith("r")) {
            int days = Integer.parseInt(period.substring(0, period.length() - 1))
            if (offset < 0) offset = -offset
            fromCal.add(Calendar.DAY_OF_YEAR, -offset * days)
            thruCal = (Calendar) basisCal.clone()
            thruCal.add(Calendar.DAY_OF_YEAR, offset * days)
        } else if (period == "week") {
            fromCal.set(Calendar.DAY_OF_WEEK, fromCal.getFirstDayOfWeek())
            fromCal.add(Calendar.WEEK_OF_YEAR, offset)
            thruCal = (Calendar) fromCal.clone()
            thruCal.add(Calendar.WEEK_OF_YEAR, 1)
        } else if (period == "month") {
            fromCal.set(Calendar.DAY_OF_MONTH, fromCal.getActualMinimum(Calendar.DAY_OF_MONTH))
            fromCal.add(Calendar.MONTH, offset)
            thruCal = (Calendar) fromCal.clone()
            thruCal.add(Calendar.MONTH, 1)
        } else if (period == "quarter") {
            fromCal.set(Calendar.DAY_OF_MONTH, fromCal.getActualMinimum(Calendar.DAY_OF_MONTH))
            int quarterNumber = (fromCal.get(Calendar.MONTH) / 3) as int
            fromCal.set(Calendar.MONTH, (quarterNumber * 3))
            fromCal.add(Calendar.MONTH, (offset * 3))
            thruCal = (Calendar) fromCal.clone()
            thruCal.add(Calendar.MONTH, 3)
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

        ArrayList<Timestamp> rangeList = new ArrayList<>(2)
        rangeList.add(new Timestamp(fromCal.getTimeInMillis()))
        rangeList.add(new Timestamp(thruCal.getTimeInMillis()))
        return rangeList
    }

    @Override String getPeriodDescription(String period, String poffset, String pdate) {
        ArrayList<Timestamp> rangeList = getPeriodRange(period, poffset, pdate)
        StringBuilder desc = new StringBuilder()
        if (poffset == "0") desc.append(eci.getL10n().localize("This"))
        else if (poffset == "-1") desc.append(eci.getL10n().localize("Last"))
        else if (poffset == "1") desc.append(eci.getL10n().localize("Next"))
        else desc.append(poffset)
        desc.append(' ')

        if (period == "day") desc.append(eci.getL10n().localize("Day"))
        else if (period == "7d") desc.append('7 ').append(eci.getL10n().localize("Days"))
        else if (period == "30d") desc.append('30 ').append(eci.getL10n().localize("Days"))
        else if (period == "week") desc.append(eci.getL10n().localize("Week"))
        else if (period == "month") desc.append(eci.getL10n().localize("Month"))
        else if (period == "quarter") desc.append(eci.getL10n().localize("Quarter"))
        else if (period == "year") desc.append(eci.getL10n().localize("Year"))
        else if (period == "7r") desc.append("+/-7d")
        else if (period == "30r") desc.append("+/-30d")

        if (pdate) desc.append(" ").append(eci.getL10n().localize("from##period")).append(" ").append(pdate)

        desc.append(" (").append(eci.l10n.format(rangeList[0], 'yyyy-MM-dd')).append(' ')
                .append(eci.getL10n().localize("to##period")).append(' ')
                .append(eci.l10n.format(rangeList[1] - 1, 'yyyy-MM-dd')).append(')')

        return desc.toString()
    }

    @Override void setEffectiveTime(Timestamp effectiveTime) { this.effectiveTime = effectiveTime }

    @Override boolean loginUser(String username, String password) {
        if (username == null || username.isEmpty()) {
            eci.messageFacade.addError(eci.l10n.localize("No username specified"))
            return false
        }
        if (password == null || password.isEmpty()) {
            eci.messageFacade.addError(eci.l10n.localize("No password specified"))
            return false
        }

        UsernamePasswordToken token = new UsernamePasswordToken(username, password, true)
        Subject loginSubject = makeEmptySubject()
        try {
            loginSubject.login(token)

            // do this first so that the rest will be done as this user
            // just in case there is already a user authenticated push onto a stack to remember
            pushUserSubject(loginSubject)

            // after successful login trigger the after-login actions
            if (eci.getWebImpl() != null) {
                eci.getWebImpl().runAfterLoginActions()
                eci.getWebImpl().getRequest().setAttribute("moqui.request.authenticated", "true")
            }
        } catch (AuthenticationException ae) {
            // others to consider handling differently (these all inherit from AuthenticationException):
            //     UnknownAccountException, IncorrectCredentialsException, ExpiredCredentialsException,
            //     CredentialsException, LockedAccountException, DisabledAccountException, ExcessiveAttemptsException
            eci.messageFacade.addError(ae.message)
            logger.warn("Login failure: ${eci.message.errorsString}", ae)
            return false
        }

        return true
    }

    /** For internal framework use only, does a login without authc. */
    boolean internalLoginUser(String username) {
        if (username == null || username.isEmpty()) {
            eci.message.addError(eci.l10n.localize("No username specified"))
            return false
        }

        UsernamePasswordToken token = new MoquiShiroRealm.ForceLoginToken(username, true)
        Subject loginSubject = makeEmptySubject()
        try {
            loginSubject.login(token)

            // do this first so that the rest will be done as this user
            // just in case there is already a user authenticated push onto a stack to remember
            pushUserSubject(loginSubject)

            // after successful login trigger the after-login actions
            if (eci.getWebImpl() != null) {
                eci.getWebImpl().runAfterLoginActions()
                eci.getWebImpl().getRequest().setAttribute("moqui.request.authenticated", "true")
            }
        } catch (AuthenticationException ae) {
            eci.messageFacade.addError(ae.message)
            logger.warn("Login failure: ${eci.message.errorsString}", ae)
            return false
        }

        return true
    }

    @Override void logoutUser() {
        // before logout trigger the before-logout actions
        if (eci.getWebImpl() != null) eci.getWebImpl().runBeforeLogoutActions()

        popUser()
    }

    @Override boolean loginUserKey(String loginKey) {
        if (!loginKey) {
            eci.message.addError(eci.l10n.localize("No login key specified"))
            return false
        }

        // lookup login key, by hashed key
        String hashedKey = eci.ecfi.getSimpleHash(loginKey, "", eci.ecfi.getLoginKeyHashType(), false)
        EntityValue userLoginKey = eci.getEntity().find("moqui.security.UserLoginKey")
                .condition("loginKey", hashedKey).disableAuthz().one()

        // see if we found a record for the login key
        if (userLoginKey == null) {
            eci.message.addError(eci.l10n.localize("Login key not valid"))
            return false
        }

        // check expire date
        Timestamp nowDate = getNowTimestamp()
        if (nowDate > userLoginKey.getTimestamp("thruDate")) {
            eci.message.addError(eci.l10n.localize("Login key expired"))
            return false
        }

        // login user with internalLoginUser()
        EntityValue userAccount = eci.getEntity().find("moqui.security.UserAccount")
                .condition("userId", userLoginKey.userId).disableAuthz().one()
        return internalLoginUser(userAccount.getString("username"))
    }
    @Override String getLoginKey() {
        String userId = getUserId()
        if (!userId) throw new IllegalStateException("No active user, cannot get login key")

        // generate login key
        String loginKey = StringUtilities.getRandomString(40)

        // save hashed in UserLoginKey, calc expire and set from/thru dates
        String hashedKey = eci.ecfi.getSimpleHash(loginKey, "", eci.ecfi.getLoginKeyHashType(), false)
        int expireHours = eci.ecfi.getLoginKeyExpireHours()
        Timestamp fromDate = getNowTimestamp()
        long thruTime = fromDate.getTime() + (expireHours * 60*60*1000)
        eci.serviceFacade.sync().name("create", "moqui.security.UserLoginKey")
                .parameters([loginKey:hashedKey, userId:userId, fromDate:fromDate, thruDate:new Timestamp(thruTime)])
                .disableAuthz().requireNewTransaction(true).call()

        // clean out expired keys
        eci.entity.find("moqui.security.UserLoginKey").condition("userId", userId)
                .condition("thruDate", EntityCondition.LESS_THAN, fromDate).disableAuthz().deleteAll()

        return loginKey
    }

    @Override boolean loginAnonymousIfNoUser() {
        if (currentInfo.username == null && !currentInfo.loggedInAnonymous) {
            currentInfo.loggedInAnonymous = true
            return true
        } else {
            return false
        }
    }
    void logoutAnonymousOnly() { currentInfo.loggedInAnonymous = false }
    boolean getLoggedInAnonymous() { return currentInfo.loggedInAnonymous }

    @Override boolean hasPermission(String userPermissionId) {
        return hasPermissionById(getUserId(), userPermissionId, getNowTimestamp(), eci) }

    static boolean hasPermission(String username, String userPermissionId, Timestamp whenTimestamp, ExecutionContextImpl eci) {
        EntityValue ua = eci.entityFacade.fastFindOne("moqui.security.UserAccount", true, true, username)
        if (ua == null) ua = eci.entityFacade.find("moqui.security.UserAccount").condition("username", username).useCache(true).disableAuthz().one()
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

    @Override boolean isInGroup(String userGroupId) { return isInGroup(getUserId(), userGroupId, getNowTimestamp(), eci) }

    static boolean isInGroup(String username, String userGroupId, Timestamp whenTimestamp, ExecutionContextImpl eci) {
        EntityValue ua = eci.entityFacade.fastFindOne("moqui.security.UserAccount", true, true, username)
        if (ua == null) ua = eci.entityFacade.find("moqui.security.UserAccount").condition("username", username).useCache(true).disableAuthz().one()
        return isInGroupById((String) ua?.userId, userGroupId, whenTimestamp, eci)
    }
    static boolean isInGroupById(String userId, String userGroupId, Timestamp whenTimestamp, ExecutionContextImpl eci) {
        if (userGroupId == "ALL_USERS") return true
        if (!userId) return false
        if ((Object) whenTimestamp == null) whenTimestamp = new Timestamp(System.currentTimeMillis())
        return (eci.getEntity().find("moqui.security.UserGroupMember").condition("userId", userId).condition("userGroupId", userGroupId)
                .useCache(true).disableAuthz().list().filterByDate("fromDate", "thruDate", whenTimestamp)) as boolean
    }

    @Override Set<String> getUserGroupIdSet() {
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

    ArrayList<Map<String, Object>> getArtifactTarpitCheckList(ArtifactExecutionInfo.ArtifactType artifactTypeEnum) {
        ArrayList<Map<String, Object>> checkList = (ArrayList<Map<String, Object>>) currentInfo.internalArtifactTarpitCheckListMap.get(artifactTypeEnum)
        if (checkList == null) {
            // get the list for each group separately to increase cache hits/efficiency
            checkList = new ArrayList<>()
            for (String userGroupId in getUserGroupIdSet()) {
                EntityList atcvList = eci.getEntity().find("moqui.security.ArtifactTarpitCheckView")
                        .condition("userGroupId", userGroupId).condition("artifactTypeEnumId", artifactTypeEnum.name())
                        .useCache(true).disableAuthz().list()
                int atcvListSize = atcvList.size()
                for (int i = 0; i < atcvListSize; i++) checkList.add(((EntityValueBase) atcvList.get(i)).getValueMap())
            }
            currentInfo.internalArtifactTarpitCheckListMap.put(artifactTypeEnum, checkList)
        }
        return checkList
    }

    ArrayList<ArtifactAuthzCheck> getArtifactAuthzCheckList() {
        // NOTE: even if there is no user, still consider part of the ALL_USERS group and such: if (usernameStack.size() == 0) return EntityListImpl.EMPTY
        if (currentInfo.internalArtifactAuthzCheckList == null) {
            // get the list for each group separately to increase cache hits/efficiency
            ArrayList<ArtifactAuthzCheck> newList = new ArrayList<>()
            for (String userGroupId in getUserGroupIdSet()) {
                EntityList aacvList = eci.getEntity().find("moqui.security.ArtifactAuthzCheckView")
                        .condition("userGroupId", userGroupId).useCache(true).disableAuthz().list()
                int aacvListSize = aacvList.size()
                for (int i = 0; i < aacvListSize; i++) newList.add(new ArtifactAuthzCheck((EntityValueBase) aacvList.get(i)))
            }
            currentInfo.internalArtifactAuthzCheckList = newList
        }
        return currentInfo.internalArtifactAuthzCheckList
    }

    @Override String getUserId() { return currentInfo.userId }
    @Override String getUsername() { return currentInfo.username }
    @Override EntityValue getUserAccount() { return currentInfo.getUserAccount() }

    @Override String getVisitUserId() { return visitId ? getVisit().userId : null }
    @Override String getVisitId() { return visitId }
    @Override EntityValue getVisit() {
        if (visitInternal != null) return visitInternal
        if (visitId == null || visitId.isEmpty()) return null
        visitInternal = eci.entityFacade.fastFindOne("moqui.server.Visit", false, true, visitId)
        return visitInternal
    }
    @Override String getVisitorId() {
        if (visitorIdInternal != null) return visitorIdInternal
        EntityValue visitLocal = getVisit()
        visitorIdInternal = visitLocal != null ? visitLocal.getNoCheckSimple("visitorId") : null
        return visitorIdInternal
    }

    // ========== UserInfo ==========

    UserInfo pushUserSubject(Subject subject) {
        UserInfo userInfo = pushUser((String) subject.getPrincipal())
        userInfo.subject = subject
        return userInfo
    }
    UserInfo pushUser(String username) {
        if (currentInfo != null && currentInfo.username == username)
            return currentInfo

        if (currentInfo == null || currentInfo.isPopulated()) {
            // logger.info("Pushing UserInfo for ${username} to stack, was ${currentInfo.username}")
            UserInfo userInfo = new UserInfo(this, username)
            userInfoStack.addFirst(userInfo)
            currentInfo = userInfo
            return userInfo
        } else {
            currentInfo.setInfo(username)
            return currentInfo
        }
    }
    void popUser() {
        if (currentInfo.subject != null && currentInfo.subject.isAuthenticated()) currentInfo.subject.logout()
        userInfoStack.removeFirst()

        // always leave at least an empty UserInfo on the stack
        if (userInfoStack.size() == 0) userInfoStack.addFirst(new UserInfo(this, null))

        UserInfo newCurInfo = userInfoStack.getFirst()
        // logger.info("Popping UserInfo ${currentInfo.username}, new current is ${newCurInfo.username}")

        // whether previous user on stack or new one, set the currentInfo
        currentInfo = newCurInfo
    }

    static class UserInfo {
        final UserFacadeImpl ufi
        // keep a reference to a UserAccount for performance reasons, avoid repeated cached queries
        protected EntityValueBase userAccount = (EntityValueBase) null
        protected String username = (String) null
        protected String userId = (String) null
        Set<String> internalUserGroupIdSet = (Set<String>) null
        // these two are used by ArtifactExecutionFacadeImpl but are maintained here to be cleared when user changes, are based on current user's groups
        final EnumMap<ArtifactExecutionInfo.ArtifactType, ArrayList<Map<String, Object>>> internalArtifactTarpitCheckListMap =
                new EnumMap<>(ArtifactExecutionInfo.ArtifactType.class)
        ArrayList<ArtifactAuthzCheck> internalArtifactAuthzCheckList = (ArrayList<ArtifactAuthzCheck>) null

        Locale localeCache = (Locale) null
        TimeZone tzCache = (TimeZone) null
        String currencyUomId = (String) null

        /** The Shiro Subject (user) */
        Subject subject = (Subject) null
        /** This is set instead of adding _NA_ user as logged in to pass authc tests but not generally behave as if a user is logged in */
        boolean loggedInAnonymous = false

        protected Map<String, Object> userContext = (Map<String, Object>) null

        UserInfo(UserFacadeImpl ufi, String username) {
            this.ufi = ufi
            setInfo(username)
        }

        boolean isPopulated() { return (username != null && username.length() > 0) || loggedInAnonymous }

        void setInfo(String username) {
            // this shouldn't happen unless there is a bug in the framework
            if (isPopulated()) throw new IllegalStateException("Cannot set user info, UserInfo already populated")

            this.username = username

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
                if (localeStr != null && localeStr.length() > 0) {
                    int usIdx = localeStr.indexOf("_")
                    localeCache = usIdx < 0 ? new Locale(localeStr) :
                            new Locale(localeStr.substring(0, usIdx), localeStr.substring(usIdx+1).toUpperCase())
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

            internalUserGroupIdSet = (Set<String>) null
            internalArtifactTarpitCheckListMap.clear()
            internalArtifactAuthzCheckList = (ArrayList<ArtifactAuthzCheck>) null
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
