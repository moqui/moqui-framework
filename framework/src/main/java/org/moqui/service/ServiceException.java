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
package org.moqui.service;

/**
 * ServiceFacade Exception
 */
public class ServiceException extends org.moqui.BaseException {

    public ServiceException(String str) {
        super(str);
    }

    public ServiceException(String str, Throwable nested) {
        super(str, nested);
    }
}
