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
package org.moqui.context;

import java.util.Deque;
import java.util.List;

/** For information about artifacts as they are being executed. */
public interface ArtifactExecutionFacade {
    /** Gets information about the current artifact being executed, and about authentication and authorization for
     * that artifact.
     *
     * @return Current (most recent) ArtifactExecutionInfo
     */
    ArtifactExecutionInfo peek();

    /** Push onto the artifact stack. This is generally called internally by the framework and does not need to be used
     * in application code. */
    void push(ArtifactExecutionInfo aei, boolean requiresAuthz);
    ArtifactExecutionInfo push(String name, ArtifactExecutionInfo.ArtifactType typeEnum, ArtifactExecutionInfo.AuthzAction actionEnum, boolean requiresAuthz);
    /** Pop from the artifact stack and verify it is the same artifact name and type. This is generally called internally
     * by the framework and does not need to be used in application code. */
    ArtifactExecutionInfo pop(ArtifactExecutionInfo aei);

    /** Gets a stack/deque/list of objects representing artifacts that have been executed to get to the current artifact.
     * The bottom artifact in the stack will generally be a screen or a service. If a service is run locally
     * this will trace back to the screen or service that called it, and if a service was called remotely it will be
     * the bottom of the stack.
     *
     * @return Actual ArtifactExecutionInfo stack/deque object
     */
    Deque<ArtifactExecutionInfo> getStack();

    List<ArtifactExecutionInfo> getHistory();
    String printHistory();

    /** Disable authorization checks for the current ExecutionContext only.
     * This should be used when the system automatically does something (possible based on a user action) that the user
     * would not generally have permission to do themselves.
     *
     * @return boolean representing previous state of disable authorization (true if was disabled, false if not). If
     *         this is true, you should not enableAuthz when you are done and instead allow whichever code first did the
     *         disable to enable it.
     */
    boolean disableAuthz();
    /** Enable authorization after a disableAuthz() call. Not that this should be done in a finally block with the code
     * following the disableAuthz() in the corresponding try block. If this is not in a finally block an exception may
     * result in authorizations being disabled for the rest of the scope of the ExecutionContext (a potential security
     * whole).
     */
    void enableAuthz();

    boolean disableTarpit();
    void enableTarpit();

    void setAnonymousAuthorizedAll();
    void setAnonymousAuthorizedView();

    /** Disable Entity Facade ECA rules (for this thread/ExecutionContext only, does not affect other things happening
     * in the system).
     * @return boolean following same pattern as disableAuthz(), and should be handled the same way.
     */
    boolean disableEntityEca();
    /** Disable Entity Facade ECA rules (for this thread/ExecutionContext only, does not affect other things happening
     * in the system).
     */
    void enableEntityEca();
}
