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

import org.moqui.BaseArtifactException;

/** Thrown when artifact tarpit is hit, too many uses of artifact. */
public class ArtifactTarpitException extends BaseArtifactException {

    private Integer retryAfterSeconds = null;

    public ArtifactTarpitException(String str) { super(str); }
    public ArtifactTarpitException(String str, Throwable nested) { super(str, nested); }
    public ArtifactTarpitException(String str, Integer retryAfterSeconds) {
        super(str);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Integer getRetryAfterSeconds() { return retryAfterSeconds; }
}
