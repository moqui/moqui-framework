package dtq.synchro

import org.moqui.BaseException

/**
 * SynchroException
 *
 */
public class SynchroException extends BaseException {

    public SynchroException(String str) {
        super(str);
    }

    public SynchroException(String str, Throwable nested) {
        super(str, nested);
    }
}
