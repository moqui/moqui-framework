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

import org.moqui.util.MNode;

import javax.sql.DataSource;

public interface EntityDatasourceFactory {
    EntityDatasourceFactory init(EntityFacade ef, MNode datasourceNode);
    void destroy();
    boolean checkTableExists(String entityName);
    boolean checkAndAddTable(String entityName);
    EntityValue makeEntityValue(String entityName);
    EntityFind makeEntityFind(String entityName);

    /** Return the JDBC DataSource, if applicable. Return null if no JDBC DataSource exists for this Entity Datasource. */
    DataSource getDataSource();
}
