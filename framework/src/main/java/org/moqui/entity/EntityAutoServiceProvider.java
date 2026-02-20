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
package org.moqui.entity;

import java.util.Map;

/**
 * ARCH-005: Interface to decouple EntityFacade from ServiceFacade
 *
 * This interface allows EntityFacade to execute entity-auto service operations
 * without directly depending on ServiceFacade.
 */
public interface EntityAutoServiceProvider {
    /**
     * Execute an entity-auto service operation.
     *
     * @param operation The operation verb (create, store, update, delete)
     * @param entityName The entity name to operate on
     * @param parameters The parameters for the operation
     * @return The result map from the service call
     */
    Map<String, Object> executeEntityAutoService(String operation, String entityName, Map<String, Object> parameters);
}
