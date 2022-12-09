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

import org.moqui.Moqui;
import org.moqui.context.ElasticFacade;
import org.moqui.entity.EntityException;
import org.moqui.entity.EntityFacade;
import org.moqui.entity.EntityValue;
import org.moqui.impl.entity.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.DatatypeConverter;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.sql.Connection;
import java.sql.Timestamp;
import java.util.Calendar;
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
            // not much option other than static access via Moqui object
            EntityFacade ef = Moqui.getExecutionContextFactory().getEntity();
            edfInternal = (ElasticDatasourceFactory) ef.getDatasourceFactory(ef.getEntityGroupName(getEntityName()));
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
        if (ed.isViewEntity) throw new EntityException("View entities are not supported, Elastic/OpenSearch does not support joins");
        ElasticDatasourceFactory edf = getEdf();

        edf.checkCreateDocumentIndex(ed);
        ElasticFacade.ElasticClient elasticClient = edf.getElasticClient();

        String combinedId = getPrimaryKeysString();
        // logger.warn("create elastic combinedId " + combinedId + " valueMapInternal " + valueMapInternal);
        elasticClient.index(edf.getIndexName(ed), combinedId, valueMapInternal);
        setSyncedWithDb();
    }

    @Override
    public void updateExtended(FieldInfo[] pkFieldArray, FieldInfo[] nonPkFieldArray, Connection con) {
        EntityDefinition ed = getEntityDefinition();
        if (ed.isViewEntity) throw new EntityException("View entities are not supported, Elastic/OpenSearch does not support joins");
        ElasticDatasourceFactory edf = getEdf();

        edf.checkCreateDocumentIndex(ed);
        ElasticFacade.ElasticClient elasticClient = edf.getElasticClient();

        String combinedId = getPrimaryKeysString();
        // use ElasticClient.update() method, supports partial doc update, see https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-update.html
        elasticClient.update(edf.getIndexName(ed), combinedId, valueMapInternal);
        setSyncedWithDb();
    }

    @Override
    public void deleteExtended(Connection con) {
        EntityDefinition ed = getEntityDefinition();
        if (ed.isViewEntity) throw new EntityException("View entities are not supported, Elastic/OpenSearch does not support joins");
        ElasticDatasourceFactory edf = getEdf();

        edf.checkCreateDocumentIndex(ed);
        ElasticFacade.ElasticClient elasticClient = edf.getElasticClient();

        String combinedId = getPrimaryKeysString();
        elasticClient.delete(edf.getIndexName(ed), combinedId);
    }

    @Override
    public boolean refreshExtended() {
        EntityDefinition ed = getEntityDefinition();
        if (ed.isViewEntity) throw new EntityException("View entities are not supported, Elastic/OpenSearch does not support joins");
        ElasticDatasourceFactory edf = getEdf();

        edf.checkCreateDocumentIndex(ed);
        ElasticFacade.ElasticClient elasticClient = edf.getElasticClient();

        String combinedId = getPrimaryKeysString();
        Map getResponse = elasticClient.get(edf.getIndexName(ed), combinedId);
        if (getResponse == null) return false;
        Map dbValue = (Map) getResponse.get("_source");
        if (dbValue == null) return false;

        FieldInfo[] allFieldArray = ed.entityInfo.allFieldInfoArray;
        for (int j = 0; j < allFieldArray.length; j++) {
            FieldInfo fi = allFieldArray[j];
            Object fValue = ElasticDatasourceFactory.convertFieldValue(fi, dbValue.get(fi.name));
            valueMapInternal.putByIString(fi.name, fValue, fi.index);
        }

        setSyncedWithDb();
        return true;
    }
}
