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

import groovy.util.Node;
import javax.sql.DataSource;

public interface EntityDatasourceFactory {
    public EntityDatasourceFactory init(EntityFacade ef, Node datasourceNode, String tenantId);
    public void destroy();
    public void checkAndAddTable(String entityName);
    public EntityValue makeEntityValue(String entityName);
    public EntityFind makeEntityFind(String entityName);

    /** Return the JDBC DataSource, if applicable. Return null if no JDBC DataSource exists for this Entity Datasource. */
    public DataSource getDataSource();
}
