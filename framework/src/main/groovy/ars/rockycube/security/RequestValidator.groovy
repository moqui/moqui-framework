package ars.rockycube.security

import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp

class RequestValidator {
    private ExecutionContext ec
    protected final static Logger logger = LoggerFactory.getLogger(RequestValidator.class);

    // short-circuit for TEST environment - need to store flag for it
    private boolean isTest = false

    // security info
    private String tokenIdentity

    // JWT used
    private HashMap jwt

    RequestValidator(ExecutionContext ec){
        this.ec = ec

        // used for handling during tests
        this.isTest = "test".equals(System.getProperty("instance_purpose"))
        if (this.isTest) {
            this.jwt = [unique_name: 'TEST']
            this.tokenIdentity = UUID.randomUUID()
            return
        }

        // extract token info
        def resp = ec.web.response
        if (!resp) throw new Exception("No HttpServletResponse available, cannot proceed")

        // now we can, at least, return a standard response, not just an exception
        def req = ec.web.request
        assert req, "No HttpServletRequest available, cannot proceed"

        //  logger.info("Incoming request's headers: [${ec.web.request.headerNames.toList()}]")
        this.tokenIdentity = req.getHeader('X-Token-Id')
        logger.info("X-Token-Id: ${this.tokenIdentity}")
        if (!this.tokenIdentity) throw new Exception("Token identity not found in headers of the Request")
    }

    public String getUniqueName(){
        if (!this.jwt.containsKey('unique_name')) throw new Exception("No unique name in JWT")
        return this.jwt.get('unique_name')
    }

    public String getTokenIdentity(){
        return this.tokenIdentity
    }

    /**
     * Method that is at the pinnacle of checking incoming
     * request.
     */
    public boolean parse() {
        // quit early
        if (this.isTest) return true

        logger.warn("Checking request on path [${ec.web.request.pathInfo}]")
        // logger.warn("Checking token with ID [${this.tokenIdentity}]")

        // 1. search for the token
        def token = ec.entity.find("ars.rockycube.security.Token").condition([id: this.tokenIdentity]).disableAuthz().one()
        if (!token) {
            ec.web.response.sendError(400, "Unable to process incoming request - no token stored")
            return false
        }

        // 2. check expiration
        def exp = (Timestamp) token.get('expiration')

        // define actual date and compare the expiration time to it
        def cal = Calendar.getInstance()
        cal.setTime(new Date())
        def now = new Timestamp(cal.getTimeInMillis())
        if (now.after(exp)) {
            ec.web.response.sendError(412, "Token has expired")
            return false
        }

        // load JWT
        this.jwt = (HashMap) token.get('jwt')

        // DISABLED
        // ec.web.response.sendError(400, "Unable to process incoming request")
        // return false

        // parsed successfully
        return true
    }
}
