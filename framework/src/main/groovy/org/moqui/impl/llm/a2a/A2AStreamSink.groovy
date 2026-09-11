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
package org.moqui.impl.llm.a2a

/** Transport-neutral receiver for A2A StreamResponse objects ({task}|{message}|{statusUpdate}|{artifactUpdate}). */
interface A2AStreamSink {
    /** @return false when the receiver is gone; the producer stops emitting but the task keeps running. */
    boolean emit(Map<String, Object> streamResponse)
    /** Keep-alive while no events are produced. @return false when the receiver is gone. */
    boolean ping()
}
