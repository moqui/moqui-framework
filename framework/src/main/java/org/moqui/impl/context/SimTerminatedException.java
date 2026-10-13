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
package org.moqui.impl.context;

import org.moqui.BaseException;

/**
 * Thrown when a sim overlay can no longer be trusted, for example after a transaction that wrote to it was rolled back.
 * The overlay does not undo writes, so the sim ends and a new one must be started.
 */
public class SimTerminatedException extends BaseException {
    public SimTerminatedException(String message) { super(message); }
}
