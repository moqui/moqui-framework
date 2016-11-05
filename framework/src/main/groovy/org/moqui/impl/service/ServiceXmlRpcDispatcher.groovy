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
package org.moqui.impl.service

import groovy.transform.CompileStatic

import org.apache.xmlrpc.XmlRpcException
import org.apache.xmlrpc.XmlRpcHandler
import org.apache.xmlrpc.XmlRpcRequest
import org.apache.xmlrpc.common.ServerStreamConnection
import org.apache.xmlrpc.common.XmlRpcHttpRequestConfig
import org.apache.xmlrpc.common.XmlRpcHttpRequestConfigImpl
import org.apache.xmlrpc.server.AbstractReflectiveHandlerMapping
import org.apache.xmlrpc.server.XmlRpcHttpServer
import org.apache.xmlrpc.server.XmlRpcHttpServerConfig
import org.apache.xmlrpc.server.XmlRpcNoSuchHandlerException
import org.apache.xmlrpc.util.HttpUtil

import org.moqui.impl.context.ExecutionContextImpl

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
public class ServiceXmlRpcDispatcher extends XmlRpcHttpServer {
    protected final static Logger logger = LoggerFactory.getLogger(ServiceXmlRpcDispatcher.class)

    protected ExecutionContextImpl eci

    public ServiceXmlRpcDispatcher(ExecutionContextImpl eci) {
        this.eci = eci
        this.setHandlerMapping(new ServiceRpcHandler())
    }

    public void dispatch(HttpServletRequest request, HttpServletResponse response) throws XmlRpcException {
        this.execute(this.getXmlRpcConfig(request), new HttpStreamConnection(request, response))
    }


    @Override
    protected void setResponseHeader(ServerStreamConnection con, String header, String value) {
        ((HttpStreamConnection) con).getResponse().setHeader(header, value)
    }

    protected XmlRpcHttpRequestConfig getXmlRpcConfig(HttpServletRequest request) {
        XmlRpcHttpRequestConfigImpl result = new XmlRpcHttpRequestConfigImpl()
        XmlRpcHttpServerConfig serverConfig = (XmlRpcHttpServerConfig) getConfig()

        result.setBasicEncoding(serverConfig.getBasicEncoding())
        result.setContentLengthOptional(serverConfig.isContentLengthOptional())
        result.setEnabledForExtensions(serverConfig.isEnabledForExtensions())
        result.setGzipCompressing(HttpUtil.isUsingGzipEncoding(request.getHeader("Content-Encoding")))
        result.setGzipRequesting(HttpUtil.isUsingGzipEncoding(request.getHeaders("Accept-Encoding")))
        result.setEncoding(request.getCharacterEncoding())
        // result.setEnabledForExceptions(serverConfig.isEnabledForExceptions())
        HttpUtil.parseAuthorization(result, request.getHeader("Authorization"))

        // result.setEnabledForExtensions(true)
        // result.setEnabledForExceptions(true)

        return result
    }

    class MoquiXmlRpcAuthHandler implements AbstractReflectiveHandlerMapping.AuthenticationHandler {
        public boolean isAuthorized(XmlRpcRequest xmlRpcRequest) throws XmlRpcException {
            XmlRpcHttpRequestConfig config = (XmlRpcHttpRequestConfig) xmlRpcRequest.getConfig()

            ServiceDefinition sd = eci.serviceFacade.getServiceDefinition(xmlRpcRequest.getMethodName())
            if (sd != null && "true".equals(sd.authenticate)) {
                return eci.user.loginUser(config.getBasicUserName(), config.getBasicPassword())
            } else {
                return true
            }
        }
    }

    class ServiceRpcHandler extends AbstractReflectiveHandlerMapping implements XmlRpcHandler {

        public ServiceRpcHandler() {
            this.setAuthenticationHandler(new MoquiXmlRpcAuthHandler())
        }

        @Override
        public XmlRpcHandler getHandler(String method) throws XmlRpcNoSuchHandlerException, XmlRpcException {
            ServiceDefinition sd = eci.serviceFacade.getServiceDefinition(method)
            if (sd == null) throw new XmlRpcNoSuchHandlerException("Service not found: [" + method + "]")
            return this
        }

        public Object execute(XmlRpcRequest xmlRpcReq) throws XmlRpcException {
            String methodName = xmlRpcReq.getMethodName()

            ServiceDefinition sd = eci.serviceFacade.getServiceDefinition(methodName)
            if (sd == null) throw new XmlRpcException("Received XML-RPC service call for unknown service [${methodName}]")
            if (!sd.allowRemote) throw new XmlRpcException("Received XML-RPC service call to service [${sd.serviceName}] that does not allow remote calls.")

            Map params = this.getParameters(xmlRpcReq, methodName)

            XmlRpcHttpRequestConfig config = (XmlRpcHttpRequestConfig) xmlRpcReq.getConfig()
            if (config.getBasicUserName() && eci.user.userId != config.getBasicUserName()) {
                params.put("authUsername", config.getBasicUserName())
                params.put("authPassword", config.getBasicPassword())
            }

            Map result = eci.service.sync().name(sd.serviceName).parameters(params).call()
            if (eci.getMessage().hasError()) {
                throw new XmlRpcException(eci.message.errorsString)
            }

            return result
        }

        protected Map getParameters(XmlRpcRequest xmlRpcRequest, String methodName) throws XmlRpcException {
            ServiceDefinition sd = eci.serviceFacade.getServiceDefinition(methodName)

            Map parameters = new HashMap()
            int parameterCount = xmlRpcRequest.getParameterCount()

            if (parameterCount > 1) {
                // assume parameters are in the order they are found in the service definition
                int x = 0
                for (String name in sd.getInParameterNames()) {
                    parameters.put(name, xmlRpcRequest.getParameter(x))
                    x++

                    if (x == parameterCount) break
                }
            } else if (parameterCount == 1) {
                // hopefully it's a Map or the service takes just one parameter...
                Object paramZero = xmlRpcRequest.getParameter(0)
                if (paramZero instanceof Map) {
                    parameters = (Map) paramZero
                } else {
                    parameters.put(sd.getInParameterNames().iterator().next(), paramZero)
                }
            }

            parameters = sd.convertValidateCleanParameters(parameters, eci)
            return parameters
        }
    }

    class HttpStreamConnection implements ServerStreamConnection {

        protected HttpServletRequest request
        protected HttpServletResponse response

        protected HttpStreamConnection(HttpServletRequest req, HttpServletResponse res) {
            this.request = req
            this.response = res
        }

        public HttpServletRequest getRequest() { return request }
        public HttpServletResponse getResponse() { return response }
        public InputStream newInputStream() throws IOException { return request.getInputStream() }

        public OutputStream newOutputStream() throws IOException {
            response.setContentType("text/xml")
            return response.getOutputStream()
        }

        public void close() throws IOException { response.getOutputStream().close() }
    }
}
