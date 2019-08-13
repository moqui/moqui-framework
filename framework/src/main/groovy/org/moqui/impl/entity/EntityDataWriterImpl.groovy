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

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EntityDataWriterImpl implements EntityDataWriter {
    private final static Logger logger = LoggerFactory.getLogger(EntityDataWriterImpl.class)

    private EntityFacadeImpl efi

    private EntityDataWriter.FileType fileType = XML
    private int txTimeout = 3600
    private LinkedHashSet<String> entityNames = new LinkedHashSet<>()
    private boolean allEntities = false

    private int dependentLevels = 0
    private String masterName = null
    private String prefix = null
    private Map<String, Object> filterMap = [:]
    private List<String> orderByList = []
    private Timestamp fromDate = null
    private Timestamp thruDate = null

    EntityDataWriterImpl(EntityFacadeImpl efi) { this.efi = efi }

    EntityFacadeImpl getEfi() { return efi }

    EntityDataWriter fileType(EntityDataWriter.FileType ft) { fileType = ft; return this }
    EntityDataWriter entityName(String entityName) { entityNames.add(entityName);  return this }
    EntityDataWriter entityNames(List<String> enList) { entityNames.addAll(enList);  return this }
    EntityDataWriter allEntities() { allEntities = true; return this }

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
            efi.ecfi.executionContext.message.addError(efi.ecfi.resource.expand('File ${filename} already exists.','',[filename:filename]))
            return 0
        }

        if (filename.endsWith('.json')) fileType(JSON)
        else if (filename.endsWith('.xml')) fileType(XML)

        PrintWriter pw = new PrintWriter(outFile)
        // NOTE: don't have to do anything different here for different file types, writer() method will handle that
        int valuesWritten = this.writer(pw)
        pw.close()
        efi.ecfi.executionContext.message.addMessage(efi.ecfi.resource.expand('Wrote ${valuesWritten} records to file ${filename}', '', [valuesWritten:valuesWritten, filename:filename]))
        return valuesWritten
    }

    @Override
    int zipFile(String filenameWithinZip, String zipFilename) {
        File zipFile = new File(zipFilename)
        if (!zipFile.parentFile.exists()) zipFile.parentFile.mkdirs()
        if (!zipFile.createNewFile()) {
            efi.ecfi.executionContext.message.addError(efi.ecfi.resource.expand('File ${filename} already exists.', '', [filename:zipFilename]))
            return 0
        }

        if (filenameWithinZip.endsWith('.json')) fileType(JSON)
        else if (filenameWithinZip.endsWith('.xml')) fileType(XML)

        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zipFile))
        try {
            PrintWriter pw = new PrintWriter(out)
            ZipEntry e = new ZipEntry(filenameWithinZip)
            out.putNextEntry(e)
            try {
                int valuesWritten = this.writer(pw)
                pw.flush()
                efi.ecfi.executionContext.message.addMessage(efi.ecfi.resource.expand('Wrote ${valuesWritten} records to file ${filename}', '', [valuesWritten:valuesWritten, filename:zipFilename]))
                return valuesWritten
            } finally {
                out.closeEntry()
            }
        } finally {
            out.close()
        }
    }

    @Override
    int directory(String path) {
        File outDir = new File(path)
        if (!outDir.exists()) outDir.mkdir()
        if (!outDir.isDirectory()) {
            efi.ecfi.executionContext.message.addError(efi.ecfi.resource.expand('Path ${path} is not a directory.','',[path:path]))
            return 0
        }

        checkAllEntities()
        if (dependentLevels > 0) efi.createAllAutoReverseManyRelationships()

        int valuesWritten = 0

        TransactionFacade tf = efi.ecfi.transactionFacade
        boolean suspendedTransaction = false
        try {
            if (tf.isTransactionInPlace()) suspendedTransaction = tf.suspend()
            boolean beganTransaction = tf.begin(txTimeout)
            try {
                for (String en in entityNames) {
                    EntityDefinition ed = efi.getEntityDefinition(en)
                    boolean useMaster = masterName != null && masterName.length() > 0 && ed.getMasterDefinition(masterName) != null
                    EntityFind ef = makeEntityFind(en)
                    EntityListIterator eli = ef.iterator()

                    try {
                        if (!eli.hasNext()) continue

                        String filename = path + '/' + en + (JSON.is(fileType) ? ".json" : ".xml")
                        File outFile = new File(filename)
                        if (outFile.exists()) {
                            efi.ecfi.getEci().message.addError(efi.ecfi.resource.expand('File ${filename} already exists, skipping entity ${en}.','',[filename:filename,en:en]))
                            continue
                        }
                        outFile.createNewFile()

                        PrintWriter pw = new PrintWriter(outFile)
                        try {
                            startFile(pw)

                            int curValuesWritten = 0
                            EntityValue ev
                            while ((ev = eli.next()) != null) {
                                curValuesWritten += writeValue(ev, pw, useMaster)
                            }

                            endFile(pw)

                            efi.ecfi.getEci().message.addMessage(efi.ecfi.resource.expand('Wrote ${curValuesWritten} records to file ${filename}','',[curValuesWritten:curValuesWritten,filename:filename]))
                            valuesWritten += curValuesWritten
                        } finally {
                            pw.close()
                        }
                    } finally {
                        eli.close()
                    }
                }
            } catch (Throwable t) {
                logger.warn("Error writing data", t)
                tf.rollback(beganTransaction, "Error writing data", t)
                efi.ecfi.getEci().messageFacade.addError(t.getMessage())
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
    @Override
    int zipDirectory(String pathWithinZip, String zipFilename) {
        File zipFile = new File(zipFilename)
        if (!zipFile.parentFile.exists()) zipFile.parentFile.mkdirs()
        if (!zipFile.createNewFile()) {
            efi.ecfi.executionContext.message.addError(efi.ecfi.resource.expand('File ${filename} already exists.', '', [filename:zipFilename]))
            return 0
        }

        checkAllEntities()
        if (dependentLevels > 0) efi.createAllAutoReverseManyRelationships()

        int valuesWritten = 0
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zipFile))
        try {
            PrintWriter pw = new PrintWriter(out)
            for (String en in entityNames) {
                EntityDefinition ed = efi.getEntityDefinition(en)
                boolean useMaster = masterName != null && masterName.length() > 0 && ed.getMasterDefinition(masterName) != null
                EntityFind ef = makeEntityFind(en)
                EntityListIterator eli = ef.iterator()
                try {
                    if (!eli.hasNext()) continue

                    String filenameWithinZip = pathWithinZip + '/' + en + (JSON.is(fileType) ? ".json" : ".xml")
                    ZipEntry e = new ZipEntry(filenameWithinZip)
                    out.putNextEntry(e)
                    try {
                        startFile(pw)

                        int curValuesWritten = 0
                        EntityValue ev
                        while ((ev = eli.next()) != null) {
                            curValuesWritten += writeValue(ev, pw, useMaster)
                        }

                        endFile(pw)

                        pw.flush()
                        efi.ecfi.getEci().message.addMessage(efi.ecfi.resource.expand('Wrote ${curValuesWritten} records to ${filename}','',[curValuesWritten:curValuesWritten,filename:filenameWithinZip]))

                        valuesWritten += curValuesWritten
                    } finally {
                        out.closeEntry()
                    }
                } finally {
                    eli.close()
                }
            }
        } finally {
            out.close()
        }
        return valuesWritten
    }


    @Override
    int writer(Writer writer) {
        checkAllEntities()
        if (dependentLevels > 0) efi.createAllAutoReverseManyRelationships()

        TransactionFacade tf = efi.ecfi.transactionFacade
        boolean suspendedTransaction = false
        int valuesWritten = 0
        try {
            if (tf.isTransactionInPlace()) suspendedTransaction = tf.suspend()
            boolean beganTransaction = tf.begin(txTimeout)
            try {
                startFile(writer)

                for (String en in entityNames) {
                    EntityDefinition ed = efi.getEntityDefinition(en)
                    boolean useMaster = masterName != null && masterName.length() > 0 && ed.getMasterDefinition(masterName) != null
                    EntityFind ef = makeEntityFind(en)
                    EntityListIterator eli = ef.iterator()
                    try {
                        EntityValue ev
                        while ((ev = eli.next()) != null) {
                            valuesWritten+= writeValue(ev, writer, useMaster)
                        }
                    } finally {
                        eli.close()
                    }
                }

                endFile(writer)
            } catch (Throwable t) {
                logger.warn("Error writing data: " + t.toString(), t)
                tf.rollback(beganTransaction, "Error writing data", t)
                efi.ecfi.getEci().messageFacade.addError(t.getMessage())
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

    private void startFile(Writer writer) {
        if (JSON.is(fileType)) {
            writer.println("[")
        } else {
            writer.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            writer.println("<entity-facade-xml>")
        }
    }
    private void endFile(Writer writer) {
        if (JSON.is(fileType)) {
            writer.println("]")
            writer.println("")
        } else {
            writer.println("</entity-facade-xml>")
            writer.println("")
        }
    }
    private int writeValue(EntityValue ev, Writer writer, boolean useMaster) {
        int valuesWritten
        if (JSON.is(fileType)) {
            Map<String, Object> plainMap
            if (useMaster) {
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
            valuesWritten = 1
        } else {
            if (useMaster) {
                valuesWritten = ev.writeXmlTextMaster(writer, prefix, masterName)
            } else {
                valuesWritten = ev.writeXmlText(writer, prefix, dependentLevels)
            }
        }
        return valuesWritten
    }
    private void checkAllEntities() {
        if (allEntities) {
            LinkedHashSet<String> newEntities = new LinkedHashSet<>(efi.getAllNonViewEntityNames())
            newEntities.removeAll(entityNames)
            entityNames = newEntities
            allEntities = false
        }
    }

    private EntityFind makeEntityFind(String en) {
        EntityFind ef = efi.find(en).condition(filterMap).orderBy(orderByList)
        EntityDefinition ed = efi.getEntityDefinition(en)
        if (ed.isField("lastUpdatedStamp")) {
            if (fromDate) ef.condition("lastUpdatedStamp", ComparisonOperator.GREATER_THAN_EQUAL_TO, fromDate)
            if (thruDate) ef.condition("lastUpdatedStamp", ComparisonOperator.LESS_THAN, thruDate)
        }
        return ef
    }
}
