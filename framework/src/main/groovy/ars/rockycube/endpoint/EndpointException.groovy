package ars.rockycube.endpoint

import org.moqui.BaseException

/**
 * EndpointException
 *
 */
public class EndpointException extends BaseException {

    public EndpointException(String str) {
        super(str);
    }

    public EndpointException(String str, Throwable nested) {
        super(str, nested);
    }
}
