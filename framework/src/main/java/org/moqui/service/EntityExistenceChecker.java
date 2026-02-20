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
 * ARCH-005: Interface to decouple ServiceFacade from EntityFacade
 *
 * This interface allows ServiceFacade to check for entity existence
 * without directly depending on EntityFacade.
 */
@FunctionalInterface
public interface EntityExistenceChecker {
    /**
     * Check if an entity with the given name is defined.
     *
     * @param entityName The entity name to check
     * @return true if the entity is defined, false otherwise
     */
    boolean isEntityDefined(String entityName);
}
