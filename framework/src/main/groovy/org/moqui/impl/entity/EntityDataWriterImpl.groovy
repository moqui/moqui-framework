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
package org.moqui.impl.entity

import groovy.json.JsonBuilder
import org.moqui.entity.EntityValue

import java.sql.Timestamp

import org.moqui.context.TransactionException
import org.moqui.context.TransactionFacade
import org.moqui.entity.EntityDataWriter
import org.moqui.entity.EntityListIterator
import org.moqui.entity.EntityFind
import org.moqui.entity.EntityCondition.ComparisonOperator

import org.slf4j.LoggerFactory
import org.slf4j.Logger

class EntityDataWriterImpl implements EntityDataWriter {
    protected final static Logger logger = LoggerFactory.getLogger(EntityDataWriterImpl.class)

    protected EntityFacadeImpl efi

    protected EntityDataWriter.FileType fileType = XML
    protected int txTimeout = 3600
    protected LinkedHashSet<String> entityNames = new LinkedHashSet<>()
    protected int dependentLevels = 0
    protected String masterName = null
    protected String prefix = null
    protected Map<String, Object> filterMap = [:]
    protected List<String> orderByList = []
    protected Timestamp fromDate = null
    protected Timestamp thruDate = null

    EntityDataWriterImpl(EntityFacadeImpl efi) { this.efi = efi }

    EntityFacadeImpl getEfi() { return efi }

    EntityDataWriter fileType(EntityDataWriter.FileType ft) { fileType = ft; return this }
    EntityDataWriter entityName(String entityName) { entityNames.add(entityName);  return this }
    EntityDataWriter entityNames(List<String> enList) { entityNames.addAll(enList);  return this }
    EntityDataWriter dependentRecords(boolean dr) { if (dr) { dependentLevels = 2 } else { dependentLevels = 0 }; return this }
    EntityDataWriter dependentLevels(int levels) { dependentLevels = levels; return this }
    EntityDataWriter master(String mn) { masterName = mn; return this }
    EntityDataWriter prefix(String p) { prefix = p; return this }
    EntityDataWriter filterMap(Map<String, Object> fm) { filterMap.putAll(fm); return this }
    EntityDataWriter orderBy(List<String> obl) { orderByList.addAll(obl); return this }
    EntityDataWriter fromDate(Timestamp fd) { fromDate = fd; return this }
    EntityDataWriter thruDate(Timestamp td) { thruDate = td; return this }

    @Override
    int file(String filename) {
        File outFile = new File(filename)
        if (!outFile.createNewFile()) {
            efi.ecfi.executionContext.message.addError("File ${filename} already exists.")
            return 0
        }

        if (filename.endsWith('.json')) fileType(JSON)
        else if (filename.endsWith('.xml')) fileType(XML)

        PrintWriter pw = new PrintWriter(outFile)
        // NOTE: don't have to do anything different here for different file types, writer() method will handle that
        int valuesWritten = this.writer(pw)
        pw.close()
        efi.ecfi.executionContext.message.addMessage("Wrote ${valuesWritten} records to file ${filename}")
        return valuesWritten
    }

    @Override
    int directory(String path) {
        File outDir = new File(path)
        if (!outDir.exists()) outDir.mkdir()
        if (!outDir.isDirectory()) {
            efi.ecfi.executionContext.message.addError("Path ${path} is not a directory.")
            return 0
        }

        if (dependentLevels) efi.createAllAutoReverseManyRelationships()

        int valuesWritten = 0

        TransactionFacade tf = efi.getEcfi().getTransactionFacade()
        boolean suspendedTransaction = false
        try {
            if (tf.isTransactionInPlace()) suspendedTransaction = tf.suspend()
            boolean beganTransaction = tf.begin(txTimeout)
            try {
                if (fileType == JSON) {
                    valuesWritten = directoryJson(path)
                } else {
                    valuesWritten = directoryXml(path)
                }
            } catch (Throwable t) {
                logger.warn("Error writing data", t)
                tf.rollback(beganTransaction, "Error writing data", t)
                efi.getEcfi().getExecutionContext().getMessage().addError(t.getMessage())
            } finally {
                if (beganTransaction && tf.isTransactionInPlace()) tf.commit()
            }
        } catch (TransactionException e) {
            throw e
        } finally {
            try {
                if (suspendedTransaction) tf.resume()
            } catch (Throwable t) {
                logger.error("Error resuming parent transaction after data write", t)
            }
        }

        return valuesWritten
    }
    protected int directoryXml(String path) {
        int valuesWritten = 0

        for (String en in entityNames) {
            String filename = "${path}/${en}.xml"
            File outFile = new File(filename)
            if (outFile.exists()) {
                efi.ecfi.executionContext.message.addError("File ${filename} already exists, skipping entity ${en}.")
                continue
            }
            outFile.createNewFile()

            PrintWriter pw = new PrintWriter(outFile)
            pw.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            pw.println("<entity-facade-xml>")

            EntityDefinition ed = efi.getEntityDefinition(en)
            EntityFind ef = makeEntityFind(en)
            EntityListIterator eli = ef.iterator()

            int curValuesWritten = 0
            try {
                if (masterName != null && masterName.length() > 0 && ed.getMasterDefinition(masterName) != null) {
                    curValuesWritten = eli.writeXmlTextMaster(pw, prefix, masterName)
                } else {
                    curValuesWritten = eli.writeXmlText(pw, prefix, dependentLevels)
                }
            } finally {
                eli.close()
            }

            pw.println("</entity-facade-xml>")
            pw.close()
            efi.ecfi.executionContext.message.addMessage("Wrote ${curValuesWritten} records to file ${filename}")

            valuesWritten += curValuesWritten
        }

        return valuesWritten
    }
    protected int directoryJson(String path) {
        int valuesWritten = 0

        for (String en in entityNames) {
            String filename = "${path}/${en}.json"
            File outFile = new File(filename)
            if (outFile.exists()) {
                efi.ecfi.executionContext.message.addError("File ${filename} already exists, skipping entity ${en}.")
                continue
            }
            outFile.createNewFile()

            PrintWriter pw = new PrintWriter(outFile)
            pw.println("[")

            EntityDefinition ed = efi.getEntityDefinition(en)
            EntityFind ef = makeEntityFind(en)
            EntityListIterator eli = ef.iterator()

            int curValuesWritten = 0
            try {
                EntityValue ev
                while ((ev = eli.next()) != null) {
                    Map plainMap
                    if (masterName != null && masterName.length() > 0 && ed.getMasterDefinition(masterName) != null) {
                        plainMap = ev.getMasterValueMap(masterName)
                    } else {
                        plainMap = ev.getPlainValueMap(dependentLevels)
                    }
                    JsonBuilder jb = new JsonBuilder()
                    jb.call(plainMap)
                    String jsonStr = jb.toPrettyString()
                    pw.write(jsonStr)
                    pw.println(",")

                    // TODO: consider including dependent records in the count too... maybe write something to recursively count the nested Maps
                    curValuesWritten++
                }
            } finally {
                eli.close()
            }

            pw.println("]")
            pw.println("")

            pw.close()
            efi.ecfi.executionContext.message.addMessage("Wrote ${curValuesWritten} records to file ${filename}")

            valuesWritten += curValuesWritten
        }

        return valuesWritten
    }

    @Override
    int writer(Writer writer) {
        if (dependentLevels) efi.createAllAutoReverseManyRelationships()

        TransactionFacade tf = efi.getEcfi().getTransactionFacade()
        boolean suspendedTransaction = false
        int valuesWritten = 0
        try {
            if (tf.isTransactionInPlace()) suspendedTransaction = tf.suspend()
            boolean beganTransaction = tf.begin(txTimeout)
            try {
                if (fileType == JSON) {
                    valuesWritten = writerJson(writer)
                } else {
                    valuesWritten = writerXml(writer)
                }
            } catch (Throwable t) {
                logger.warn("Error writing data: " + t.toString(), t)
                tf.rollback(beganTransaction, "Error writing data", t)
                efi.getEcfi().getExecutionContext().getMessage().addError(t.getMessage())
            } finally {
                if (beganTransaction && tf.isTransactionInPlace()) tf.commit()
            }
        } catch (TransactionException e) {
            throw e
        } finally {
            try {
                if (suspendedTransaction) tf.resume()
            } catch (Throwable t) {
                logger.error("Error resuming parent transaction after data write", t)
            }
        }

        return valuesWritten
    }
    protected int writerXml(Writer writer) {
        int valuesWritten = 0

        writer.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        writer.println("<entity-facade-xml>")

        for (String en in entityNames) {
            EntityDefinition ed = efi.getEntityDefinition(en)
            EntityFind ef = makeEntityFind(en)

            /* leaving commented as might be useful for future con pool debugging:
            try {
                def dataSource = efi.getDatasourceFactory(ed.getEntityGroupName()).getDataSource()
                logger.warn("=========== edwi pool available size: ${dataSource.poolAvailableSize()}/${dataSource.poolTotalSize()}; ${dataSource.getMinPoolSize()}-${dataSource.getMaxPoolSize()}")
            } catch (Throwable t) {
                logger.warn("========= pool size error ${t.toString()}")
            }
            */

            EntityListIterator eli = ef.iterator()
            try {
                if (masterName != null && masterName.length() > 0 && ed.getMasterDefinition(masterName) != null) {
                    valuesWritten += eli.writeXmlTextMaster(writer, prefix, masterName)
                } else {
                    valuesWritten += eli.writeXmlText(writer, prefix, dependentLevels)
                }
            } finally {
                eli.close()
            }
        }

        writer.println("</entity-facade-xml>")
        writer.println("")

        return valuesWritten
    }

    protected int writerJson(Writer writer) {
        int valuesWritten = 0

        writer.println("[")

        for (String en in entityNames) {
            EntityDefinition ed = efi.getEntityDefinition(en)
            EntityFind ef = makeEntityFind(en)
            EntityListIterator eli = ef.iterator()
            try {
                EntityValue ev
                while ((ev = eli.next()) != null) {
                    Map plainMap
                    if (masterName != null && masterName.length() > 0 && ed.getMasterDefinition(masterName) != null) {
                        plainMap = ev.getMasterValueMap(masterName)
                    } else {
                        plainMap = ev.getPlainValueMap(dependentLevels)
                    }
                    JsonBuilder jb = new JsonBuilder()
                    jb.call(plainMap)
                    String jsonStr = jb.toPrettyString()
                    writer.write(jsonStr)
                    writer.println(",")

                    // TODO: consider including dependent records in the count too... maybe write something to recursively count the nested Maps
                    valuesWritten++
                }
            } finally {
                eli.close()
            }
        }

        writer.println("]")
        writer.println("")

        return valuesWritten
    }

    protected EntityFind makeEntityFind(String en) {
        EntityFind ef = efi.find(en).condition(filterMap).orderBy(orderByList)
        EntityDefinition ed = efi.getEntityDefinition(en)
        if (ed.isField("lastUpdatedStamp")) {
            if (fromDate) ef.condition("lastUpdatedStamp", ComparisonOperator.GREATER_THAN_EQUAL_TO, fromDate)
            if (thruDate) ef.condition("lastUpdatedStamp", ComparisonOperator.LESS_THAN, thruDate)
        }
        return ef
    }
}
