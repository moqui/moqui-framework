/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 */
package org.moqui.impl.util

import groovy.transform.CompileStatic
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.util.SystemBinding
import org.moqui.util.WebUtilities

@CompileStatic
class UserSecurityChecks {
    static final String DEFAULT_PRIVILEGED_GROUPS = "ADMIN,ADMIN_ADV"
    static final String DEFAULT_SEALED_PERMISSIONS =
            "GROOVY_SHELL_WEB,SQL_RUNNER_WEB,SERVICE_LOAD_RUNNER,ADMIN_LOGIN_AS,ADMIN_PASSWORD,ElasticRemote,KibanaRemote,REST_SCHEMA"

    static Set<String> privilegedGroups() {
        String raw = SystemBinding.getPropOrEnv("user_privileged_groups")
        if (raw == null || raw.isEmpty()) raw = DEFAULT_PRIVILEGED_GROUPS
        return WebUtilities.csvIdSet(raw)
    }

    static Set<String> sealedPermissions() {
        String raw = SystemBinding.getPropOrEnv("user_sealed_permissions")
        if (raw == null || raw.isEmpty()) raw = DEFAULT_SEALED_PERMISSIONS
        return WebUtilities.csvIdSet(raw)
    }

    static void assertPrivilegedGroupMembership(ExecutionContext ec, String userGroupId) {
        if (userGroupId == null || userGroupId.isEmpty()) return
        ExecutionContextImpl eci = (ExecutionContextImpl) ec
        if (eci.artifactExecutionFacade.authzDisabled) return
        if (!privilegedGroups().contains(userGroupId)) return
        if (eci.userFacade.isInGroup(userGroupId)) return
        throw new ArtifactAuthorizationException(
                "User is not authorized to change membership of group ${userGroupId}")
    }

    static void assertSealedPermissionGrant(ExecutionContext ec, String userPermissionId) {
        if (userPermissionId == null || userPermissionId.isEmpty()) return
        ExecutionContextImpl eci = (ExecutionContextImpl) ec
        if (eci.artifactExecutionFacade.authzDisabled) return
        if (!sealedPermissions().contains(userPermissionId)) return
        if (eci.userFacade.hasPermission(userPermissionId)) return
        throw new ArtifactAuthorizationException(
                "User is not authorized to grant permission ${userPermissionId}")
    }
}
