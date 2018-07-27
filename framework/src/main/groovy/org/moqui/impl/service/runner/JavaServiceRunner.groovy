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
package org.moqui.impl.service.runner

import groovy.transform.CompileStatic
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.util.ObjectUtilities

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.InvocationTargetException

import org.moqui.context.ExecutionContext
import org.moqui.impl.service.ServiceDefinition
import org.moqui.impl.service.ServiceFacadeImpl
import org.moqui.service.ServiceException
import org.moqui.impl.service.ServiceRunner
import org.moqui.util.ContextStack

@CompileStatic
public class JavaServiceRunner implements ServiceRunner {

    private ServiceFacadeImpl sfi = null
    private ExecutionContextFactoryImpl ecfi = null

    JavaServiceRunner() {}

    public ServiceRunner init(ServiceFacadeImpl sfi) {
        this.sfi = sfi
        ecfi = sfi.ecfi
        return this
    }

    public Map<String, Object> runService(ServiceDefinition sd, Map<String, Object> parameters) {
        if (!sd.location || !sd.method) throw new ServiceException("Service [" + sd.serviceName + "] is missing location and/or method attributes and they are required for running a java service.")

        ExecutionContextImpl ec = ecfi.getEci()
        ContextStack cs = ec.contextStack
        Map<String, Object> result = (Map<String, Object>) null

        // push the entire context to isolate the context for the service call
        cs.pushContext()
        try {
            // we have an empty context so add the ec
            cs.put("ec", ec)
            // now add the parameters to this service call; copy instead of pushing, faster with newer ContextStack
            cs.putAll(parameters)

            Class c = (Class) ObjectUtilities.getClass(sd.location)
            if (c == null) c = Thread.currentThread().getContextClassLoader().loadClass(sd.location)

            Method m = c.getMethod(sd.method, ExecutionContext.class)
            if (Modifier.isStatic(m.getModifiers())) {
                result = (Map<String, Object>) m.invoke(null, ec)
            } else {
                result = (Map<String, Object>) m.invoke(c.newInstance(), ec)
            }
        } catch (ClassNotFoundException e) {
            throw new ServiceException("Could not find class for java service [${sd.serviceName}]", e)
        } catch (NoSuchMethodException e) {
            throw new ServiceException("Java Service [${sd.serviceName}] specified method [${sd.method}] that does not exist in class [${sd.location}]", e)
        } catch (SecurityException e) {
            throw new ServiceException("Access denied in service [${sd.serviceName}]", e)
        } catch (IllegalAccessException e) {
            throw new ServiceException("Method not accessible in service [${sd.serviceName}]", e)
        } catch (IllegalArgumentException e) {
            throw new ServiceException("Invalid parameter match in service [${sd.serviceName}]", e)
        } catch (NullPointerException e) {
            throw new ServiceException("Null pointer in service [${sd.serviceName}]", e)
        } catch (ExceptionInInitializerError e) {
            throw new ServiceException("Initialization failed for service [${sd.serviceName}]", e)
        } catch (InvocationTargetException e) {
            throw new ServiceException("Java method for service [${sd.serviceName}] threw an exception", e.getTargetException())
        } catch (Throwable t) {
            throw new ServiceException("Error or unknown exception in service [${sd.serviceName}]", t)
        } finally {
            // pop the entire context to get back to where we were before isolating the context with pushContext
            cs.popContext()
        }

        return result
    }

    public void destroy() { }
}
