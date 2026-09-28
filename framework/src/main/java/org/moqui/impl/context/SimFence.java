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
import org.moqui.Moqui;
import org.moqui.context.ExecutionContext;
import org.moqui.context.ExecutionContextFactory;

/** Side effects that the H2 sim overlay does not capture. */
public final class SimFence {
    private SimFence() { }

    public static boolean active() {
        ExecutionContextFactory factory = Moqui.getExecutionContextFactory();
        if (factory == null) return false;
        ExecutionContext ec = factory.getActiveExecutionContext();
        return ec != null && ec.isSimSession();
    }

    public static void refuse(String what) {
        if (active()) throw new BaseException(what + " is disabled in LLM sim session");
    }
}
