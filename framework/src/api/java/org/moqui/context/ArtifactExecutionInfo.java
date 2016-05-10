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

import java.io.Writer;
import java.math.BigDecimal;
import java.util.List;

/**
 * Information about execution of an artifact as the system is running
 */
public interface ArtifactExecutionInfo {
    String getName();
    String getTypeEnumId();
    String getActionEnumId();

    String getAuthorizedUserId();
    String getAuthorizedAuthzTypeId();
    String getAuthorizedActionEnumId();
    boolean isAuthorizationInheritable();
    Boolean getAuthorizationWasRequired();
    Boolean getAuthorizationWasGranted();

    long getRunningTime();
    BigDecimal getRunningTimeMillis();
    long getThisRunningTime();
    BigDecimal getThisRunningTimeMillis();
    long getChildrenRunningTime();
    BigDecimal getChildrenRunningTimeMillis();
    List<ArtifactExecutionInfo> getChildList();
    ArtifactExecutionInfo getParent();
    BigDecimal getPercentOfParentTime();

    void print(Writer writer, int level, boolean children);
}
