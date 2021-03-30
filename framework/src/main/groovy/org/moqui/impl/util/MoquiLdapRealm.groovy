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
import org.apache.shiro.authc.credential.AllowAllCredentialsMatcher;
import org.apache.shiro.authz.AuthorizationException;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.ldap.UnsupportedAuthenticationMechanismException;
import org.apache.shiro.realm.AuthorizingRealm
import org.apache.shiro.realm.ldap.JndiLdapContextFactory
import org.apache.shiro.util.StringUtils;

import org.apache.shiro.authc.*
import org.apache.shiro.authc.credential.CredentialsMatcher
import org.apache.shiro.authz.Authorizer
import org.apache.shiro.authz.Permission
import org.apache.shiro.authz.UnauthorizedException
import org.apache.shiro.realm.Realm
import org.apache.shiro.realm.ldap.LdapUtils
import org.apache.shiro.realm.ldap.LdapContextFactory

import org.apache.shiro.subject.PrincipalCollection
import org.apache.shiro.util.SimpleByteSource
import org.moqui.BaseArtifactException
import org.moqui.Moqui
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityException
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ArtifactExecutionFacadeImpl
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.context.UserFacadeImpl
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.naming.AuthenticationNotSupportedException;
import javax.naming.NamingException;
import javax.naming.NamingEnumeration;
import javax.naming.directory.SearchResult;
import javax.naming.directory.SearchControls;
import javax.naming.directory.Attributes;
import javax.naming.ldap.LdapContext
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp

//exception classes
public class MoquiPreLoginException extends AuthenticationException{
    public MoquiPreLoginException(String errorMessage){
        super(errorMessage);
    }

    public MoquiPreLoginException(String errorMessage, Throwable err) {
        super(errorMessage, err)
    }
}
public class MoquiAfterLoginException extends AuthenticationException{
    public MoquiAfterLoginException(String errorMessage){
        super(errorMessage);
    }

    public MoquiAfterLoginException(String errorMessage, Throwable err) {
        super(errorMessage, err);
    }
}

@CompileStatic
class MoquiLdapRealm extends AuthorizingRealm implements Realm, Authorizer {
    protected final static Logger logger = LoggerFactory.getLogger(MoquiLdapRealm.class)
    private JndiLdapContextFactory contextFactory;

    protected ExecutionContextFactoryImpl ecfi
    private String realmName = "moquiLdapRealm"

    private static final String USERDN_SUBSTITUTION_TOKEN = "{0}";

    private String userDnPrefix;
    private String userDnSuffix;

    //Attribute names
    private static final String AD_ATTR_NAME_UID_NUMBER = "uidNumber";
    private static final String AD_ATTR_NAME_UID = "uid";
    private static final String AD_ATTR_NAME_USER_EMAIL = "mail";
    private static final String AD_ATTR_NAME_USER_GIVEN_NAME = "givenName";
    private static final String AD_ATTR_NAME_USER_SURNAME = "sn";

    //query filters and queries
    private String ldapSearchBaseQueryFilter;
    private String ldapSearchBaseQuery;
    private String ldapSearchUserQueryFilter;
    private String ldapSearchUserQuery;

    protected Class<? extends AuthenticationToken> authenticationTokenClass = UsernamePasswordToken.class

    MoquiLdapRealm() {
        //logger.info("Initializing MoquiLdapRealm.")

        // with this sort of init we may only be able to get ecfi through static reference
        this.ecfi = (ExecutionContextFactoryImpl) Moqui.executionContextFactory

        //Credentials Matching is not necessary - the LDAP directory will do it automatically:
        setCredentialsMatcher(new AllowAllCredentialsMatcher())
        //Any Object principal and Object credentials may be passed to the LDAP provider, so accept any token:
        setAuthenticationTokenClass(AuthenticationToken.class);
        this.contextFactory = new JndiLdapContextFactory();

        //configure system user and password
        this.configureSystemConnection()
    }

    void configureSystemConnection() {
        try {
            /*set system connection parameters*/
            MNode ldapParams = this.ecfi.getLdapParamsNode();

            if (logger.isDebugEnabled()) logger.debug("Creating LDAP connection using config: ${ldapParams}")
            String ldapPath = ldapParams.attribute('ldap-path')
            String ldapSystemUsername = ldapParams.attribute('system-user')
            String ldapSystemUserPassword = ldapParams.attribute('system-user-password')
            String ldapUserDnTemplate = ldapParams.attribute('user-dn-template')
            String ldapSearchBaseQueryFilter = ldapParams.attribute('search-base-query-filter')
            String ldapSearchUserQueryFilter = ldapParams.attribute('search-user-query-filter')

            this.contextFactory.url = ldapPath
            this.contextFactory.systemPassword = ldapSystemUserPassword
            this.contextFactory.systemUsername = ldapSystemUsername

            this.userDnTemplate = ldapUserDnTemplate
            this.ldapSearchBaseQueryFilter = ldapSearchBaseQueryFilter
            this.ldapSearchUserQueryFilter = ldapSearchUserQueryFilter
        } catch (Exception e) {
            logger.error("Error setting up LDAP connection. ${e.message}")
        }
    }

    void setName(String n) { realmName = n }

    @Override
    String getName() { return realmName }

    @SuppressWarnings("UnusedDeclaration")
    public void setContextFactory(JndiLdapContextFactory contextFactory) {
        this.contextFactory = contextFactory;
    }

    /**
     * Returns the LdapContextFactory instance used to acquire connections to the LDAP directory during authentication
     * attempts and authorization queries.  Unless specified otherwise, the default is a {@link JndiLdapContextFactory}
     * instance.
     *
     * @return the LdapContextFactory instance used to acquire connections to the LDAP directory during
     *         authentication attempts and authorization queries
     */
    public JndiLdapContextFactory getContextFactory() {
        return this.contextFactory;
    }

    public void setUserDnTemplate(String template) throws IllegalArgumentException {
        if (!StringUtils.hasText(template)) {
            String msg = "User DN template cannot be null or empty.";
            throw new IllegalArgumentException(msg);
        }
        int index = template.indexOf(USERDN_SUBSTITUTION_TOKEN);
        if (index < 0) {
            String msg = "User DN template must contain the '" +
                    USERDN_SUBSTITUTION_TOKEN + "' replacement token to understand where to " +
                    "insert the runtime authentication principal.";
            throw new IllegalArgumentException(msg);
        }
        String prefix = template.substring(0, index);
        String suffix = template.substring(prefix.length() + USERDN_SUBSTITUTION_TOKEN.length());
        if (logger.isDebugEnabled()) {
            logger.debug("Determined user DN prefix [{}] and suffix [{}]", prefix, suffix);
        }
        this.userDnPrefix = prefix;
        this.userDnSuffix = suffix;
    }

    public void setLdapSearchBaseQueryFilter(String value) {
        if (logger.isDebugEnabled()) logger.debug("Running setLdapSearchBaseQueryFilter with value '${value}'.")
        ldapSearchBaseQueryFilter = value
    }

    public String getLdapSearchBaseQueryFilter() {
        return ldapSearchBaseQueryFilter
    }

    public void setLdapSearchUserQueryFilter(String value) {
        if (logger.isDebugEnabled()) logger.debug("Running setLdapSearchUserQueryFilter with value '${value}'.")
        ldapSearchUserQueryFilter = value
    }

    public String getLdapSearchUserQueryFilter() {
        return ldapSearchUserQueryFilter
    }

    public void setLdapSearchUserQuery(String value) {
        if (logger.isDebugEnabled()) logger.debug("Running setLdapSearchUserQuery with value '${value}'.")
        ldapSearchUserQuery = value
    }

    public String getLdapSearchUserQuery() {
        return ldapSearchUserQuery
    }

    public void setLdapSearchBaseQuery(String value) {
        if (logger.isDebugEnabled()) logger.debug("Running setLdapSearchBaseQuery with value '${value}'.")
        ldapSearchBaseQuery = value
    }

    public String getLdapSearchBaseQuery() {
        return ldapSearchBaseQuery
    }

    /**
     * Returns the User Distinguished Name (DN) template to use when creating User DNs at runtime - see the
     * {@link #setUserDnTemplate(String) setUserDnTemplate} JavaDoc for a full explanation.
     *
     * @return the User Distinguished Name (DN) template to use when creating User DNs at runtime.
     */
    public String getUserDnTemplate() {
        return getUserDn(USERDN_SUBSTITUTION_TOKEN);
    }

    /*
     * Returns the User DN prefix to use when building a runtime User DN value or {@code null} if no
     * {@link #getUserDnTemplate() userDnTemplate} has been configured.  If configured, this value is the text that
     * occurs before the {@link #USERDN_SUBSTITUTION_TOKEN} in the {@link #getUserDnTemplate() userDnTemplate} value.
     *
     * @return the the User DN prefix to use when building a runtime User DN value or {@code null} if no
     *         {@link #getUserDnTemplate() userDnTemplate} has been configured.
     */
    protected String getUserDnPrefix() {
        return userDnPrefix;
    }

    /*
     * Returns the User DN suffix to use when building a runtime User DN value.  or {@code null} if no
     * {@link #getUserDnTemplate() userDnTemplate} has been configured.  If configured, this value is the text that
     * occurs after the {@link #USERDN_SUBSTITUTION_TOKEN} in the {@link #getUserDnTemplate() userDnTemplate} value.
     *
     * @return the User DN suffix to use when building a runtime User DN value or {@code null} if no
     *         {@link #getUserDnTemplate() userDnTemplate} has been configured.
     */
    protected String getUserDnSuffix() {
        return userDnSuffix;
    }

    /*protected String getUserDn(String principal) throws IllegalArgumentException, IllegalStateException {
        if (!StringUtils.hasText(principal)) {
            throw new IllegalArgumentException("User principal cannot be null or empty for User DN construction.");
        }
        String prefix = getUserDnPrefix();
        String suffix = getUserDnSuffix();
        if (prefix == null && suffix == null) {
            logger.debug("userDnTemplate property has not been configured, indicating the submitted " +
                    "AuthenticationToken's principal is the same as the User DN.  Returning the method argument " +
                    "as is.");
            return principal;
        }

        int prefixLength = prefix != null ? prefix.length() : 0;
        int suffixLength = suffix != null ? suffix.length() : 0;
        StringBuilder sb = new StringBuilder(prefixLength + principal.length() + suffixLength);
        if (prefixLength > 0) {
            sb.append(prefix);
        }
        sb.append(principal);
        if (suffixLength > 0) {
            sb.append(suffix);
        }
        return sb.toString();
    }*/

    protected String getUserDn( final String principal ) throws IllegalArgumentException, IllegalStateException {

        if (!StringUtils.hasText(principal)) {
            throw new IllegalArgumentException("User principal cannot be null or empty for User DN construction.");
        }
        String prefix = getUserDnPrefix();
        String suffix = getUserDnSuffix();
        if (prefix == null && suffix == null) {
            logger.debug("userDnTemplate property has not been configured, indicating the submitted " +
                    "AuthenticationToken's principal is the same as the User DN.  Returning the method argument " +
                    "as is.");
            return principal;
        }

        int prefixLength = prefix != null ? prefix.length() : 0;
        int suffixLength = suffix != null ? suffix.length() : 0;
        StringBuilder sb = new StringBuilder(prefixLength + principal.length() + suffixLength);
        if (prefixLength > 0) {
            sb.append(prefix);
        }

        /*############################################################################################
        * ADDITION TO STANDARD SHIRO CODE
        * User logs in with his {@link principal} (username) but login needs to be performed with his cn instead
        *    => translate username to cn
        */
        LdapContext ctx = this.getContextFactory().getSystemLdapContext();
        String user_uid = "";

        SearchControls constraints = new SearchControls();
        constraints.setSearchScope(SearchControls.SUBTREE_SCOPE);
        String attrIDs = "cn";
        constraints.setReturningAttributes(attrIDs);
        NamingEnumeration answer = null;

        //answer = ctx.search(this.ldapSearchUserQueryFilter, "(&(objectClass=inetOrgPerson)(x-service=accountActive)(uid=" + principal + "))", constraints);
        answer = ctx.search(this.ldapSearchUserQueryFilter, "(&(objectClass=inetOrgPerson)(cn=${principal}))", constraints);
        if (answer.hasMore()) {
            Attributes attrs = ((SearchResult) answer.next()).getAttributes();
            user_uid =  attrs.get("cn").toString().substring(attrIDs[0].length() + 2).trim()
        } else {
            logger.error("Invalid user")
        }

        try {
            ctx.close();
        } catch (NamingException ex) {
            logger.error("getUserDn(); Unknown error: ", ex);
        }
        //############################################################################################

        //############################################################################################
        // Shiro Standard Code -> add principal to String
        //sb.append(principal);
        /*############################################################################################

        /*############################################################################################
        * ALTERED CODE
        * Instead -> Add user's cn to String
        */
        sb.append(user_uid);
        //############################################################################################

        if (suffixLength > 0) {
            sb.append(suffix);
        }
        return sb.toString();
    }

    public static HashMap<String, String> getUserLdapData(LdapContext ctx, String searchBase, String domainWithUser) {
        HashMap<String, String> userDataMap = new HashMap<>()
        String userName = domainWithUser.substring(domainWithUser.indexOf('\\') +1 );
        try
        {
            NamingEnumeration<SearchResult> userData = queryLdapData(ctx, searchBase, userName);
            userDataMap = extractUserLdapData( userData );
        }
        catch(Exception e)
        {
            //throw new RuntimeException(e)
        }

        return userDataMap
    }

    private static NamingEnumeration<SearchResult> queryLdapData(LdapContext ctx, String searchBase, String username) throws Exception  {
        String filter = "(&(objectClass=inetOrgPerson)(x-service=accountActive)(uid=" + username + "))";
        SearchControls searchCtls = new SearchControls();
        searchCtls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        NamingEnumeration<SearchResult> answer = null;
        try
        {
            answer = ctx.search(searchBase, filter, searchCtls);
        }
        catch (Exception e)
        {
            logger.error("Error searching LDAP for " + filter);
            throw e;
        }

        return answer;
    }

    private static HashMap<String, String> extractUserLdapData( NamingEnumeration<SearchResult> userData ) throws Exception  {
        HashMap<String, String> contactData = new HashMap<>()

        try  {
            // getting only the first result if we have more than one
            if (userData.hasMoreElements())
            {
                SearchResult sr = userData.nextElement();
                Attributes attributes = sr.getAttributes();

                contactData.put("emailAddress", attributes.get(AD_ATTR_NAME_USER_EMAIL).get().toString());
                contactData.put("externalUserId", attributes.get(AD_ATTR_NAME_UID_NUMBER).get().toString());
                contactData.put("ldapUid", attributes.get(AD_ATTR_NAME_UID).get().toString());
                contactData.put("ldapFullName",
                        attributes.get(AD_ATTR_NAME_USER_GIVEN_NAME).get().toString() + ' ' +
                        attributes.get(AD_ATTR_NAME_USER_SURNAME).get().toString()
                );
            }
        }
        catch (Exception e)
        {
            logger.error("Error fetching data on LDAP contact. ${e.message}");
        }

        return contactData;
    }

    //Class getAuthenticationTokenClass() { return authenticationTokenClass }
    //void setAuthenticationTokenClass(Class<? extends AuthenticationToken> atc) { authenticationTokenClass = atc }

    @Override
    boolean supports(AuthenticationToken token) {
        return token != null && authenticationTokenClass.isAssignableFrom(token.getClass())
    }

    private EntityValue loginPrePassword(ExecutionContextImpl eci, String username) throws MoquiPreLoginException {
        EntityValue newUserAccount = eci.entity.find("moqui.security.UserAccount").condition("username", username)
                .useCache(true).disableAuthz().one()

        // no account found? try to find the username using ldap uid
        if (newUserAccount == null) {
            newUserAccount = eci.entity.find("moqui.security.UserAccount").condition("ldapUid", username)
                    .useCache(true).disableAuthz().one()
        }

        // still no account found?
        if (newUserAccount == null) throw new UnknownAccountException(eci.resource.expand('No account found for username ${username}', '', [username: username]))

        // check for disabled account before checking password (otherwise even after disable could determine if
        //    password is correct or not
        if ("Y".equals(newUserAccount.getNoCheckSimple("disabled"))) {
            if (newUserAccount.getNoCheckSimple("disabledDateTime") != null) {
                // account temporarily disabled (probably due to excessive attempts
                Integer disabledMinutes = eci.ecfi.confXmlRoot.first("user-facade").first("login").attribute("disable-minutes") as Integer ?: 30I
                Timestamp reEnableTime = new Timestamp(newUserAccount.getTimestamp("disabledDateTime").getTime() + (disabledMinutes.intValue() * 60I * 1000I))
                if (reEnableTime > eci.user.nowTimestamp) {
                    // only blow up if the re-enable time is not passed
                    eci.service.sync().name("org.moqui.impl.UserServices.incrementUserAccountFailedLogins")
                            .parameter("userId", newUserAccount.userId).requireNewTransaction(true).call()
                    throw new ExcessiveAttemptsException(eci.resource.expand('Authenticate failed for user ${newUserAccount.username} because account is disabled and will not be re-enabled until ${reEnableTime} [DISTMP].',
                            '', [newUserAccount: newUserAccount, reEnableTime: reEnableTime]))
                }
            } else {
                // account permanently disabled
                eci.service.sync().name("org.moqui.impl.UserServices.incrementUserAccountFailedLogins")
                        .parameters((Map<String, Object>) [userId: newUserAccount.userId]).requireNewTransaction(true).call()
                throw new DisabledAccountException(eci.resource.expand('Authenticate failed for user ${newUserAccount.username} because account is disabled and is not schedule to be automatically re-enabled [DISPRM].',
                        '', [newUserAccount: newUserAccount]))
            }
        }

        //check information on the account and possibly update with data in LDAP
        if (!newUserAccount.externalUserId) {
            logger.warn("External ID not defined, shall set contact data from LDAP.")

            try {
                LdapContext ctx = this.contextFactory.getSystemLdapContext()

                //get user detail
                HashMap<String, String> userLdapData = getUserLdapData(ctx, this.ldapSearchUserQueryFilter, username)

                //append user it into the map
                userLdapData.put("userId", (String) newUserAccount.userId)

                //run update service if map's size is greater than 1
                if (userLdapData.size() > 1) {
                    this.ecfi.serviceFacade.sync().name("update", "moqui.security.UserAccount")
                            .parameters(userLdapData).disableAuthz().call()

                    logger.warn("Updated UserAccount record: ${newUserAccount.userId}")
                }

            } catch (Exception e) {
                //nothing to do
            }
        }

        return newUserAccount
    }

    static void loginPostPassword(ExecutionContextImpl eci, EntityValue newUserAccount) throws MoquiAfterLoginException {

        // no more auth failures? record the various account state updates, hasLoggedOut=N
        if (newUserAccount.getNoCheckSimple("successiveFailedLogins") || "Y".equals(newUserAccount.getNoCheckSimple("disabled")) ||
                newUserAccount.getNoCheckSimple("disabledDateTime") != null || "Y".equals(newUserAccount.getNoCheckSimple("hasLoggedOut"))) {
            boolean enableAuthz = !eci.artifactExecutionFacade.disableAuthz()
            try {
                EntityValue nuaClone = newUserAccount.cloneValue()
                nuaClone.set("successiveFailedLogins", 0)
                nuaClone.set("disabled", "N")
                nuaClone.set("disabledDateTime", null)
                nuaClone.set("hasLoggedOut", "N")
                nuaClone.update()
            } catch (Exception e) {
                logger.warn("Error resetting UserAccount login status", e)
            } finally {
                if (enableAuthz) eci.artifactExecutionFacade.enableAuthz()
            }
        }

        // update visit if no user in visit yet
        String visitId = eci.userFacade.getVisitId()
        EntityValue visit = eci.entityFacade.find("moqui.server.Visit").condition("visitId", visitId).disableAuthz().one()
        if (visit != null) {
            if (!visit.getNoCheckSimple("userId")) {
                eci.service.sync().name("update", "moqui.server.Visit").parameter("visitId", visit.visitId)
                        .parameter("userId", newUserAccount.userId).disableAuthz().call()
            }
            if (!visit.getNoCheckSimple("clientIpCountryGeoId") && !visit.getNoCheckSimple("clientIpTimeZone")) {
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
                // look for login history in the last minute, if any found don't create UserLoginHistory
                Timestamp recentDate = new Timestamp(fromDate.getTime() - 60000)
                long recentUlh = eci.entity.find("moqui.security.UserLoginHistory").condition("userId", userId)
                        .condition("fromDate", EntityCondition.GREATER_THAN, recentDate).disableAuthz().count()
                if (recentUlh == 0) {
                    Map<String, Object> ulhContext = [userId : userId, fromDate: fromDate,
                                                      visitId: eci.user.visitId, successfulLogin: (successful ? "Y" : "N")] as Map<String, Object>
                    if (!successful && loginNode.attribute("history-incorrect-password") != "false") ulhContext.passwordUsed = passwordUsed
                    ExecutionContextFactoryImpl ecfi = eci.ecfi
                    eci.runInWorkerThread({
                        try {
                            ecfi.serviceFacade.sync().name("create", "moqui.security.UserLoginHistory")
                                    .parameters(ulhContext).disableAuthz().call()
                        } catch (EntityException ee) {
                            // this blows up sometimes on MySQL, may in other cases, and is only so important so log a warning but don't rethrow
                            logger.warn("UserLoginHistory create failed: ${ee.toString()}")
                        }
                    })
                } else {
                    if (logger.isDebugEnabled()) logger.debug("Not creating UserLoginHistory, found existing record for userId ${userId} and more recent than ${recentDate}")
                }
            }
        }
    }

    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
        AuthenticationInfo info;
        ExecutionContextImpl eci = ecfi.getEci();
        EntityValue newUserAccount;
        Boolean successful = false;
        String userId = null;

        try {
            //pre-login operations - fetch account data, do not continue when not defined
            newUserAccount = loginPrePassword(eci, (String) token.principal);
            userId = newUserAccount.getString("userId")

            //LDAP
            // check the password (credentials for this case)
            info = queryForAuthenticationInfo(token, this.getContextFactory());

            //post-login operations - mostly logs
            loginPostPassword(eci, newUserAccount)

            successful = true;

        } catch (AuthenticationNotSupportedException e) {
            String msg = "Unsupported configured authentication mechanism. ${e.message}";
            throw new UnsupportedAuthenticationMechanismException(msg, e);
        } catch (javax.naming.AuthenticationException e) {
            throw new AuthenticationException("LDAP authentication failed. ${e.message}", e);
        } catch (NamingException e) {
            String msg = "LDAP naming error while attempting to authenticate user. ${e.message}";
            throw new AuthenticationException(msg, e);
        } catch (MoquiPreLoginException e) {
            throw new AuthenticationException("Unable to retrieve account information, not proceeding to LDAP authentication.", e)
        } catch (MoquiAfterLoginException e) {
            throw new AuthenticationException("Unable to perform post-login operations.", e)
        } finally {
            loginAfterAlways(eci, userId, token.credentials as String, successful)
        }

        return info;
    }

    protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
        AuthorizationInfo info;
        try {
            info = queryForAuthorizationInfo(principals, getContextFactory());
        } catch (NamingException e) {
            String msg = "LDAP naming error while attempting to retrieve authorization for user [" + principals + "].";
            throw new AuthorizationException(msg, e);
        }

        return info;
    }

    protected Object getLdapPrincipal(AuthenticationToken token) {
        Object principal = token.getPrincipal();
        if (principal instanceof String) {
            String sPrincipal = (String) principal;
            return getUserDn(sPrincipal);
        }
        return principal;
    }

    protected AuthenticationInfo queryForAuthenticationInfo(AuthenticationToken token, LdapContextFactory ldapContextFactory) throws NamingException {
        Object principal = token.getPrincipal();
        Object credentials = token.getCredentials();

        logger.debug("Authenticating user '{}' through LDAP", principal);
        principal = getLdapPrincipal(token);

        LdapContext ctx = null;
        try {
            if (!(token instanceof ForceLoginToken)) {
                ctx = ldapContextFactory.getLdapContext(principal, credentials);

                //context was opened successfully, which means their credentials were valid.  Return the AuthenticationInfo:
                return createAuthenticationInfo((AuthenticationToken) token, principal, credentials, ctx);
            } else {
                return new SimpleAuthenticationInfo(
                        (String) token.principal,
                        "",
                        new SimpleByteSource((String) ""),
                        realmName)
            }
        } finally {
            LdapUtils.closeContext(ctx);
        }
    }

    @SuppressWarnings("UnusedDeclaration")
    protected AuthenticationInfo createAuthenticationInfo(AuthenticationToken token, Object ldapPrincipal, Object ldapCredentials, LdapContext ldapContext) throws NamingException {
        return new SimpleAuthenticationInfo(token.getPrincipal(), token.getCredentials(), getName());
    }

    static boolean checkCredentials(String username, String password, ExecutionContextFactoryImpl ecfi) {
        EntityValue newUserAccount = ecfi.entity.find("moqui.security.UserAccount").condition("username", username)
                .useCache(true).disableAuthz().one()

        SimpleAuthenticationInfo info = new SimpleAuthenticationInfo(username, newUserAccount.currentPassword,
                newUserAccount.passwordSalt ? new SimpleByteSource((String) newUserAccount.passwordSalt) : null, "moquiRealm")

        CredentialsMatcher cm = ecfi.getCredentialsMatcher((String) newUserAccount.passwordHashType, "Y".equals(newUserAccount.passwordBase64))
        UsernamePasswordToken token = new UsernamePasswordToken(username, password)
        return cm.doCredentialsMatch(token, info)
    }

    // ========== Authorization Methods ==========

    /**
     * Method that should be implemented by subclasses to build an
     * {@link AuthorizationInfo} object by querying the LDAP context for the
     * specified principal.</p>
     *
     * @param principals the principals of the Subject whose AuthenticationInfo should be queried from the LDAP server.
     * @param ldapContextFactory factory used to retrieve LDAP connections.
     * @return an{@link AuthorizationInfo} instance containing information retrieved from the LDAP server.
     * @throws NamingException if any LDAP errors occur during the search.
     */
    protected AuthorizationInfo queryForAuthorizationInfo(PrincipalCollection principals, LdapContextFactory ldapContextFactory) throws NamingException {
        return null;
    }

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
        throw new BaseArtifactException("Authorization of Permission through Shiro not yet supported")
    }

    boolean[] isPermitted(PrincipalCollection principalCollection, List<Permission> permissions) {
        throw new BaseArtifactException("Authorization of Permission through Shiro not yet supported")
    }

    boolean isPermittedAll(PrincipalCollection principalCollection, Collection<Permission> permissions) {
        throw new BaseArtifactException("Authorization of Permission through Shiro not yet supported")
    }

    void checkPermission(PrincipalCollection principalCollection, Permission permission) {
        // TODO how to handle the permission interface?
        // see: http://www.jarvana.com/jarvana/view/org/apache/shiro/shiro-core/1.1.0/shiro-core-1.1.0-javadoc.jar!/org/apache/shiro/authz/Permission.html
        // also look at DomainPermission, can extend for Moqui artifacts
        // this.checkPermission(principalCollection, permission.?)
        throw new BaseArtifactException("Authorization of Permission through Shiro not yet supported")
    }

    void checkPermission(PrincipalCollection principalCollection, String permission) {
        String username = (String) principalCollection.primaryPrincipal
        if (UserFacadeImpl.hasPermission(username, permission, null, ecfi.eci)) {
            throw new UnauthorizedException(ecfi.resource.expand('User ${username} does not have permission ${permission}', '', [username: username, permission: permission]))
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
        for (String roleName in roleNames) {
            resultArray[i] = this.hasRole(principalCollection, roleName); i++
        }
        return resultArray
    }

    boolean hasAllRoles(PrincipalCollection principalCollection, Collection<String> roleNames) {
        for (String roleName in roleNames) {
            if (!this.hasRole(principalCollection, roleName)) return false
        }
        return true
    }

    void checkRole(PrincipalCollection principalCollection, String roleName) {
        if (!this.hasRole(principalCollection, roleName))
            throw new UnauthorizedException(ecfi.resource.expand('User ${principalCollection.primaryPrincipal} is not in role ${roleName}', '', [principalCollection: principalCollection, roleName: roleName]))
    }

    void checkRoles(PrincipalCollection principalCollection, Collection<String> roleNames) {
        for (String roleName in roleNames) {
            if (!this.hasRole(principalCollection, roleName))
                throw new UnauthorizedException(ecfi.resource.expand('User ${principalCollection.primaryPrincipal} is not in role ${roleName}', '', [principalCollection: principalCollection, roleName: roleName]))
        }
    }

    void checkRoles(PrincipalCollection principalCollection, String... roleNames) {
        for (String roleName in roleNames) {
            if (!this.hasRole(principalCollection, roleName))
                throw new UnauthorizedException(ecfi.resource.expand('User ${principalCollection.primaryPrincipal} is not in role ${roleName}', '', [principalCollection: principalCollection, roleName: roleName]))
        }
    }
}