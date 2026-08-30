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
package org.moqui.llm;

public final class WindowPolicy {
    public int maxMessages = 40;
    public int maxChars = 120_000;
    public boolean keepSystemFirst = true;
    public boolean keepToolPairs = true;
    public boolean includeContext = true;
    /** Applies only to input overflow (context_length_exceeded), not output finish_reason=length. */
    public ContextLimitPolicy onInputOverflow = ContextLimitPolicy.FAIL;

    public enum ContextLimitPolicy { FAIL, TRIM_AND_RETRY_ONCE }
}
