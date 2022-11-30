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
package org.moqui.impl.entity.elastic;

import org.moqui.entity.EntityException;
import org.moqui.entity.EntityValue;
import org.moqui.impl.entity.EntityDefinition;
import org.moqui.impl.entity.EntityFacadeImpl;
import org.moqui.impl.entity.EntityValueBase;
import org.moqui.impl.entity.FieldInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.sql.Connection;
import java.util.Map;

public class ElasticEntityValue extends EntityValueBase {
    protected static final Logger logger = LoggerFactory.getLogger(ElasticEntityValue.class);
    private ElasticDatasourceFactory edfInternal;

    /** Default constructor for deserialization ONLY. */
    public ElasticEntityValue() { }

    public ElasticEntityValue(EntityDefinition ed, EntityFacadeImpl efip, ElasticDatasourceFactory edf) {
        super(ed, efip);
        this.edfInternal = edf;
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        super.writeExternal(out);
    }

    @Override
    public void readExternal(ObjectInput objectInput) throws IOException, ClassNotFoundException {
        super.readExternal(objectInput);
    }

    public ElasticDatasourceFactory getEdf() {
        if (edfInternal == null) {
            // TODO!
        }

        return edfInternal;
    }

    @Override
    public EntityValue cloneValue() {
        ElasticEntityValue newObj = new ElasticEntityValue(getEntityDefinition(), getEntityFacadeImpl(), edfInternal);
        newObj.valueMapInternal.putAll(this.valueMapInternal);
        if (this.dbValueMap != null) newObj.setDbValueMap(this.dbValueMap);
        // don't set mutable (default to mutable even if original was not) or modified (start out not modified)
        return newObj;
    }

    @Override
    public EntityValue cloneDbValue(boolean getOld) {
        ElasticEntityValue newObj = new ElasticEntityValue(getEntityDefinition(), getEntityFacadeImpl(), edfInternal);
        newObj.valueMapInternal.putAll(this.valueMapInternal);
        for (FieldInfo fieldInfo : getEntityDefinition().entityInfo.allFieldInfoArray)
            newObj.putKnownField(fieldInfo, getOld ? getOldDbValue(fieldInfo.name) : getOriginalDbValue(fieldInfo.name));
        newObj.setSyncedWithDb();
        return newObj;
    }

    @Override
    public void createExtended(FieldInfo[] fieldInfoArray, Connection con) {
        EntityDefinition ed = getEntityDefinition();
        if (ed.isViewEntity) throw new EntityException("Create not yet implemented for view-entity");

        getEdf().checkCreateDocumentIndex(ed);

        Map<String, Object> localMap = getValueMap();
        FieldInfo[] fieldInfos = ed.entityInfo.allFieldInfoArray;

        // TODO
    }

    @Override
    public void updateExtended(FieldInfo[] pkFieldArray, FieldInfo[] nonPkFieldArray, Connection con) {
        EntityDefinition ed = getEntityDefinition();
        if (ed.isViewEntity) throw new EntityException("Update not yet implemented for view-entity");

        getEdf().checkCreateDocumentIndex(ed);

        // TODO
        // TODO NOTE: use ElasticClient.update() method, supports partial doc update, see https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-update.html
    }

    @Override
    public void deleteExtended(Connection con) {
        EntityDefinition ed = getEntityDefinition();
        if (ed.isViewEntity) throw new EntityException("Delete not yet implemented for view-entity");

        getEdf().checkCreateDocumentIndex(ed);

        // TODO
    }

    @Override
    public boolean refreshExtended() {
        EntityDefinition ed = getEntityDefinition();

        getEdf().checkCreateDocumentIndex(ed);

        // TODO
        return true;
    }
}
