/*
 * This Work is in the public domain and is provided on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied,
 * including, without limitation, any warranties or conditions of TITLE,
 * NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A PARTICULAR PURPOSE.
 * You are solely responsible for determining the appropriateness of using
 * this Work and assume any risks associated with your use of this Work.
 *
 * This Work includes contributions authored by David E. Jones, not as a
 * "work for hire", who hereby disclaims any copyright to the same.
 */
package org.moqui.impl.context

import java.sql.Timestamp
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import javax.servlet.http.Cookie

import org.apache.shiro.subject.Subject
import org.apache.shiro.web.subject.WebSubjectContext
import org.apache.shiro.web.subject.support.DefaultWebSubjectContext
import org.apache.shiro.authc.ExcessiveAttemptsException
import org.apache.shiro.authc.LockedAccountException
import org.apache.shiro.authc.IncorrectCredentialsException
import org.apache.shiro.authc.AuthenticationException
import org.apache.shiro.authc.UnknownAccountException
import org.apache.shiro.authc.UsernamePasswordToken

import org.moqui.context.UserFacade
import org.moqui.entity.EntityValue
import org.apache.shiro.web.session.HttpServletSession
import org.apache.shiro.authc.ExpiredCredentialsException

class UserFacadeImpl implements UserFacade {
    protected final static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(UserFacadeImpl.class)

    protected ExecutionContextImpl eci
    protected Timestamp effectiveTime = null

    // just keep the userId, always get the UserAccount value from the entity cache
    protected Deque<String> usernameStack = new LinkedList()
    /** The Shiro Subject (user) */
    protected Subject currentUser = null

    // there may be non-web visits, so keep a copy of the visitId here
    protected String visitId = null

    // we mostly want this for the Locale default, and may be useful for other things
    protected HttpServletRequest request = null

    UserFacadeImpl(ExecutionContextImpl eci) {
        this.eci = eci
    }

    void initFromHttpRequest(HttpServletRequest request, HttpServletResponse response) {
        this.request = request

        WebSubjectContext wsc = new DefaultWebSubjectContext()
        wsc.setServletRequest(request); wsc.setServletResponse(response)
        wsc.setSession(new HttpServletSession(request.getSession(), request.getServerName()))
        currentUser = eci.ecfi.securityManager.createSubject(wsc)

        if (currentUser.authenticated) {
            // effectively login the user
            String userId = (String) currentUser.principal
            // better not to do this, if there was a user before this init leave it for history/debug: if (this.userIdStack) this.userIdStack.pop()
            if (this.usernameStack.size() == 0 || this.usernameStack.peekFirst() != userId) this.usernameStack.addFirst(userId)
            if (logger.traceEnabled) logger.trace("For new request found user [${userId}] in the session; userIdStack is [${this.usernameStack}]")
        } else {
            if (logger.traceEnabled) logger.trace("For new request NO user authenticated in the session; userIdStack is [${this.usernameStack}]")
        }
        if (request.session.getAttribute("moqui.visitId")) {
            this.visitId = (String) request.session.getAttribute("moqui.visitId")

            // handle visitorId and cookie
            String cookieVisitorId = null
            if (eci.ecfi.confXmlRoot."server-stats"[0]."@visitor-enabled" != "false") {
                Cookie[] cookies = request.getCookies()
                if (cookies != null) {
                    for (int i = 0; i < cookies.length; i++) {
                        if (cookies[i].getName().equals("moqui.visitor")) {
                            cookieVisitorId = cookies[i].getValue()
                            break
                        }
                    }
                }
                if (!cookieVisitorId) {
                    // NOTE: disable authz for this call, don't normally want to allow create of Visitor, but this is a special case
                    boolean alreadyDisabled = eci.artifactExecution.disableAuthz()
                    try {
                        Map cvResult = eci.service.sync().name("create", "Visitor").parameter("createdDate", getNowTimestamp()).call()
                        cookieVisitorId = cvResult.visitorId
                        logger.info("Created new visitor with ID [${cookieVisitorId}] in visit [${this.visitId}]")
                    } finally {
                        if (!alreadyDisabled) eci.artifactExecution.enableAuthz()
                    }
                }
                // whether it existed or not, add it again to keep it fresh; stale cookies get thrown away
                Cookie visitorCookie = new Cookie("moqui.visitor", cookieVisitorId)
                visitorCookie.setMaxAge(60 * 60 * 24 * 365)
                visitorCookie.setPath("/")
                response.addCookie(visitorCookie)
            }

            EntityValue visit = getVisit()
            if (!visit?.initialLocale) {
                String fullUrl = eci.web.requestUrl
                fullUrl = (fullUrl.length() > 255) ? fullUrl.substring(0, 255) : fullUrl.toString()

                Map<String, Object> uvParms = (Map<String, Object>) [visitId:visit.visitId, initialLocale:getLocale().toString(),
                            initialRequest:fullUrl, initialReferrer:request.getHeader("Referrer")?:"",
                            initialUserAgent:request.getHeader("User-Agent")?:"",
                            clientHostName:request.getRemoteHost(), clientUser:request.getRemoteUser()]
                // handle proxy original address, if exists
                if (request.getHeader("X-Forwarded-For")) {
                    uvParms.clientIpAddress = request.getHeader("X-Forwarded-For")
                } else {
                    uvParms.clientIpAddress = request.getRemoteAddr()
                }
                if (cookieVisitorId) uvParms.visitorId = cookieVisitorId

                // NOTE: disable authz for this call, don't normally want to allow update of Visit, but this is special case
                boolean alreadyDisabled = eci.artifactExecution.disableAuthz()
                try {
                    // called this sync so it is ready next time referred to, like on next request
                    eci.service.sync().name("update", "Visit").parameters(uvParms).call()
                } finally {
                    if (!alreadyDisabled) eci.artifactExecution.enableAuthz()
                }

                // consider this the first hit in the visit, so trigger the actions
                eci.web.runFirstHitInVisitActions()
            }
        }
    }

    /** @see org.moqui.context.UserFacade#getLocale() */
    Locale getLocale() {
        Locale locale = null
        if (this.username) {
            String localeStr = this.userAccount.locale
            if (localeStr) locale = new Locale(localeStr)
        }
        return (locale ?: (request ? request.getLocale() : Locale.getDefault()))
    }

    /** @see org.moqui.context.UserFacade#setLocale(Locale) */
    void setLocale(Locale locale) {
        if (this.username) {
            eci.service.sync().name("update", "UserAccount")
                    .parameters((Map<String, Object>) [userId:getUserId(), locale:locale.toString()]).call()
        } else {
            throw new IllegalStateException("No user logged in, can't set Locale")
        }
    }

    /** @see org.moqui.context.UserFacade#getTimeZone() */
    TimeZone getTimeZone() {
        TimeZone tz = null
        if (this.username) {
            String tzStr = this.userAccount.timeZone
            if (tzStr) tz = TimeZone.getTimeZone(tzStr)
        }
        return tz ?: TimeZone.getDefault()
    }

    /** @see org.moqui.context.UserFacade#setTimeZone(TimeZone) */
    void setTimeZone(TimeZone tz) {
        if (this.username) {
            eci.service.sync().name("update", "UserAccount")
                    .parameters((Map<String, Object>) [userId:getUserId(), timeZone:tz.getID()]).call()
        } else {
            throw new IllegalStateException("No user logged in, can't set Time Zone")
        }
    }

    /** @see org.moqui.context.UserFacade#getCurrencyUomId() */
    String getCurrencyUomId() { return this.username ? this.userAccount.currencyUomId : null }

    /** @see org.moqui.context.UserFacade#setCurrencyUomId(String) */
    void setCurrencyUomId(String uomId) {
        if (this.username) {
            eci.service.sync().name("update", "UserAccount")
                    .parameters((Map<String, Object>) [userId:getUserId(), currencyUomId:uomId]).call()
        } else {
            throw new IllegalStateException("No user logged in, can't set Currency")
        }
    }

    String getPreference(String preferenceKey) {
        EntityValue up = eci.entity.makeFind("UserPreference").condition("userId", getUserId())
                .condition("preferenceKey", preferenceKey).useCache(true).one()
        return up ? up.preferenceValue : null
    }

    void setPreference(String preferenceKey, String preferenceValue) {
        eci.entity.makeValue("UserPreference").set("userId", getUserId())
                .set("preferenceKey", preferenceKey).set("preferenceValue", preferenceValue).createOrUpdate()
    }

    /** @see org.moqui.context.UserFacade#getNowTimestamp() */
    Timestamp getNowTimestamp() {
        // NOTE: review Timestamp and nowTimestamp use, have things use this by default (except audit/etc where actual date/time is needed
        return this.effectiveTime ? this.effectiveTime : new Timestamp(System.currentTimeMillis())
    }

    /** @see org.moqui.context.UserFacade#setEffectiveTime(Timestamp) */
    void setEffectiveTime(Timestamp effectiveTime) { this.effectiveTime = effectiveTime }

    boolean loginUser(String username, String password, String tenantId) {
        if (tenantId) {
            eci.changeTenant(tenantId)
            this.visitId = null
            if (eci.web != null) eci.web.session.removeAttribute("moqui.visitId")
        }

        UsernamePasswordToken token = new UsernamePasswordToken(username, password)
        token.rememberMe = true
        try {
            currentUser.login(token)

            // do this first so that the rest will be done as this user
            // just in case there is already a user authenticated push onto a stack to remember
            this.usernameStack.addFirst(username)

            // after successful login trigger the after-login actions
            if (eci.web != null) eci.web.runAfterLoginActions()
        } catch (AuthenticationException ae) {
            // others to consider handling differently (these all inherit from AuthenticationException):
            //     UnknownAccountException, IncorrectCredentialsException, ExpiredCredentialsException,
            //     CredentialsException, LockedAccountException, DisabledAccountException, ExcessiveAttemptsException
            eci.message.addError(ae.message)
            logger.warn("Login failure: ${eci.message.errors}", ae)
            return false
        }

        return true
    }

    void logoutUser() {
        // before logout trigger the before-logout actions
        if (eci.web != null) eci.web.runBeforeLogoutActions()

        if (usernameStack) usernameStack.removeFirst()

        if (eci.web != null) {
            eci.web.session.removeAttribute("moqui.tenantId")
            eci.web.session.removeAttribute("moqui.visitId")
        }
        currentUser.logout()
    }

    /* @see org.moqui.context.UserFacade#getUsername() */
    String getUserId() { return userAccount?.userId }

    /* @see org.moqui.context.UserFacade#getUsername() */
    String getUsername() { return this.usernameStack ? this.usernameStack.peekFirst() : null }

    /* @see org.moqui.context.UserFacade#getUserAccount() */
    EntityValue getUserAccount() {
        if (!usernameStack) {
            // logger.info("Getting UserAccount no userIdStack", new Exception("Trace"))
            return null
        }
        EntityValue ua = eci.entity.makeFind("UserAccount").condition("username", this.username).useCache(true).one()
        // logger.info("Got UserAccount [${ua}] with userIdStack [${userIdStack}]")
        return ua
    }

    /** @see org.moqui.context.UserFacade#getVisitUserId() */
    String getVisitUserId() { return visitId ? getVisit().userId : null }

    /** @see org.moqui.context.UserFacade#getVisitId() */
    String getVisitId() { return visitId }

    /** @see org.moqui.context.UserFacade#getVisit() */
    EntityValue getVisit() {
        if (!visitId) return null

        EntityValue vst
        boolean alreadyDisabled = eci.artifactExecution.disableAuthz()
        try {
            vst = eci.entity.makeFind("Visit").condition("visitId", visitId).useCache(true).one()
        } finally {
            if (!alreadyDisabled) eci.artifactExecution.enableAuthz()
        }
        return vst
    }
}