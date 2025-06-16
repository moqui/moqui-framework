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
import groovy.transform.CompileStatic
import org.moqui.entity.EntityValue
import org.moqui.util.ObjectUtilities

import javax.sql.rowset.serial.SerialBlob
import java.sql.Timestamp

import org.moqui.context.TransactionException
import org.moqui.context.TransactionFacade
import org.moqui.entity.EntityDataWriter
import org.moqui.entity.EntityListIterator
import org.moqui.entity.EntityFind
import org.moqui.entity.EntityCondition.ComparisonOperator

import org.slf4j.LoggerFactory
import org.slf4j.Logger

import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@CompileStatic
class EntityDataWriterImpl implements EntityDataWriter {
    private final static Logger logger = LoggerFactory.getLogger(EntityDataWriterImpl.class)

    private EntityFacadeImpl efi

    private FileType fileType = XML
    private int txTimeout = 3600
    private LinkedHashSet<String> entityNames = new LinkedHashSet<>()
    private LinkedHashSet<String> skipEntityNames = new LinkedHashSet<>()

    private int dependentLevels = 0
    private String masterName = null
    private String prefix = null
    private Map<String, Object> filterMap = [:]
    private List<String> orderByList = []
    private Timestamp fromDate = null
    private Timestamp thruDate = null

    private boolean isoDateTime = false
    private boolean tableColumnNames = false

    EntityDataWriterImpl(EntityFacadeImpl efi) { this.efi = efi }

    EntityFacadeImpl getEfi() { return efi }

    EntityDataWriter fileType(FileType ft) { fileType = ft; return this }
    EntityDataWriter fileType(String ft) { fileType = FileType.valueOf(ft); return this }
    EntityDataWriter entityName(String entityName) { entityNames.add(entityName); return this }
    EntityDataWriter entityNames(Collection<String> enList) { entityNames.addAll(enList); return this }
    EntityDataWriter skipEntityName(String entityName) { skipEntityNames.add(entityName); return this }
    EntityDataWriter skipEntityNames(Collection<String> enList) { skipEntityNames.addAll(enList); return this }
    EntityDataWriter allEntities() {
        LinkedHashSet<String> newEntities = new LinkedHashSet<>(efi.getAllNonViewEntityNames())
        newEntities.removeAll(entityNames)
        entityNames = newEntities
        return this
    }

    EntityDataWriter dependentRecords(boolean dr) { if (dr) { dependentLevels = 2 } else { dependentLevels = 0 }; return this }
    EntityDataWriter dependentLevels(int levels) { dependentLevels = levels; return this }
    EntityDataWriter master(String mn) { masterName = mn; return this }
    EntityDataWriter prefix(String p) { prefix = p; return this }
    EntityDataWriter filterMap(Map<String, Object> fm) { filterMap.putAll(fm); return this }
    EntityDataWriter orderBy(List<String> obl) { orderByList.addAll(obl); return this }
    EntityDataWriter fromDate(Timestamp fd) { fromDate = fd; return this }
    EntityDataWriter thruDate(Timestamp td) { thruDate = td; return this }

    EntityDataWriter isoDateTime(boolean iso) { isoDateTime = iso; return this }
    EntityDataWriter tableColumnNames(boolean tcn) { tableColumnNames = tcn; return this }

    @Override
    int file(String filename) {
        File outFile = new File(filename)
        if (!outFile.createNewFile()) {
            efi.ecfi.executionContext.message.addError(efi.ecfi.resource.expand('File ${filename} already exists.','',[filename:filename]))
            return 0
        }

        if (filename.endsWith('.json')) fileType(JSON)
        else if (filename.endsWith('.xml')) fileType(XML)
        else if (filename.endsWith('.csv')) fileType(CSV)

        if (CSV.is(fileType) && entityNames.size() > 1) {
            efi.ecfi.executionContext.message.addError('Cannot write to single CSV file with multiple entity names')
            return 0
        }

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
        else if (filenameWithinZip.endsWith('.csv')) fileType(CSV)

        if (CSV.is(fileType) && entityNames.size() > 1) {
            efi.ecfi.executionContext.message.addError('Cannot write to single CSV file with multiple entity names')
            return 0
        }

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

        if (dependentLevels > 0) efi.createAllAutoReverseManyRelationships()

        int valuesWritten = 0

        TransactionFacade tf = efi.ecfi.transactionFacade
        boolean suspendedTransaction = false
        try {
            if (tf.isTransactionInPlace()) suspendedTransaction = tf.suspend()
            boolean beganTransaction = tf.begin(txTimeout)
            try {
                for (String en in entityNames) {
                    if (skipEntityNames.contains(en)) continue
                    EntityDefinition ed = efi.getEntityDefinition(en)
                    boolean useMaster = masterName != null && masterName.length() > 0 && ed.getMasterDefinition(masterName) != null
                    EntityFind ef = makeEntityFind(en)


                    try (EntityListIterator eli = ef.iterator()) {
                        if (!eli.hasNext()) continue

                        String filename = path + '/' + en + '.' + fileType.name().toLowerCase()
                        File outFile = new File(filename)
                        if (outFile.exists()) {
                            efi.ecfi.getEci().message.addError(efi.ecfi.resource.expand('File ${filename} already exists, skipping entity ${en}.','',[filename:filename,en:en]))
                            continue
                        }
                        outFile.createNewFile()

                        PrintWriter pw = new PrintWriter(outFile)
                        try {
                            startFile(pw, ed)

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

        return zipDirectory(pathWithinZip, new FileOutputStream(zipFile))
    }
    @Override
    int zipDirectory(String pathWithinZip, OutputStream outputStream) {
        if (dependentLevels > 0) efi.createAllAutoReverseManyRelationships()

        int valuesWritten = 0
        ZipOutputStream out = new ZipOutputStream(outputStream)
        try {
            PrintWriter pw = new PrintWriter(out)
            for (String en in entityNames) {
                if (skipEntityNames.contains(en)) continue
                EntityDefinition ed = efi.getEntityDefinition(en)
                boolean useMaster = masterName != null && masterName.length() > 0 && ed.getMasterDefinition(masterName) != null
                EntityFind ef = makeEntityFind(en)
                try (EntityListIterator eli = ef.iterator()) {
                    if (!eli.hasNext()) continue

                    String filenameBase = tableColumnNames ? ed.getTableName() : en
                    String filenameWithinZip = (pathWithinZip ? pathWithinZip + '/' : '') + filenameBase + '.' + fileType.name().toLowerCase()
                    ZipEntry e = new ZipEntry(filenameWithinZip)
                    out.putNextEntry(e)
                    try {
                        startFile(pw, ed)

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
                }
            }
        } finally {
            out.close()
        }
        return valuesWritten
    }


    @Override
    int writer(Writer writer) {
        if (dependentLevels > 0) efi.createAllAutoReverseManyRelationships()

        LinkedHashSet<String> activeEntityNames
        if (skipEntityNames.size() == 0) {
            activeEntityNames = entityNames
        } else {
            activeEntityNames = new LinkedHashSet<>(entityNames)
            activeEntityNames.removeAll(skipEntityNames)
        }
        EntityDefinition singleEd = null
        if (activeEntityNames.size() == 1) singleEd = efi.getEntityDefinition(activeEntityNames.first())

        TransactionFacade tf = efi.ecfi.transactionFacade
        boolean suspendedTransaction = false
        int valuesWritten = 0
        try {
            if (tf.isTransactionInPlace()) suspendedTransaction = tf.suspend()
            boolean beganTransaction = tf.begin(txTimeout)
            try {
                startFile(writer, singleEd)

                for (String en in activeEntityNames) {
                    EntityDefinition ed = efi.getEntityDefinition(en)
                    boolean useMaster = masterName != null && masterName.length() > 0 && ed.getMasterDefinition(masterName) != null
                    try (EntityListIterator eli = makeEntityFind(en).iterator()) {
                        EntityValue ev
                        while ((ev = eli.next()) != null) {
                            valuesWritten+= writeValue(ev, writer, useMaster)
                        }
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

    private void startFile(Writer writer, EntityDefinition ed) {
        if (JSON.is(fileType)) {
            writer.println("[")
        } else if (XML.is(fileType)) {
            writer.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            writer.println("<entity-facade-xml>")
        } else if (CSV.is(fileType)) {
            if (ed == null) throw new IllegalArgumentException("Tried to start CSV file with no single entity specified")
            // first record: entity name, 'export' for file type, then each PK field
            if (tableColumnNames) {
                writer.println(ed.getTableName() + ",export," + ed.getPkFieldNames().collect({ ed.getFieldInfo(it).columnName }).join(","))
            } else {
                writer.println(ed.getFullEntityName() + ",export," + ed.getPkFieldNames().join(","))
            }
            // second record: header row with all field names
            if (tableColumnNames) {
                writer.println(ed.getAllFieldNames().collect({ ed.getFieldInfo(it).columnName }).join(","))
            } else {
                writer.println(ed.getAllFieldNames().join(","))
            }
        }
    }
    private void endFile(Writer writer) {
        if (JSON.is(fileType)) {
            writer.println("]")
            writer.println("")
        } else if (XML.is(fileType)) {
            writer.println("</entity-facade-xml>")
            writer.println("")
        } else if (CSV.is(fileType)) {
            // could add empty line at end but is effectively an empty record, better to do nothing to end the file
            // writer.println("")
        }
    }
    private int writeValue(EntityValue ev, Writer writer, boolean useMaster) {
        int valuesWritten
        if (JSON.is(fileType)) {
            // TODO: support isoDateTime and tableColumnNames
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
        } else if (XML.is(fileType)) {
            // TODO: support isoDateTime and tableColumnNames
            if (useMaster) {
                valuesWritten = ev.writeXmlTextMaster(writer, prefix, masterName)
            } else {
                valuesWritten = ev.writeXmlText(writer, prefix, dependentLevels)
            }
        } else if (CSV.is(fileType)) {
            EntityValueBase evb = (EntityValueBase) ev
            // NOTE: master entity def concept doesn't apply to CSV, file format cannot handle multiple entities in single file
            FieldInfo[] fieldInfoArray = evb.getEntityDefinition().entityInfo.allFieldInfoArray
            for (int i = 0; i < fieldInfoArray.length; i++) {
                Object fieldValue = evb.getKnownField(fieldInfoArray[i])
                String fieldStr = convertFieldValue(fieldValue)

                // write the field value
                if (fieldStr.contains(",") || fieldStr.contains("\"") || fieldStr.contains("\n")) {
                    writer.write("\"")
                    writer.write(fieldStr.replace("\"", "\"\""))
                    writer.write("\"")
                } else {
                    writer.write(fieldStr)
                }

                // add the comma
                if (i < (fieldInfoArray.length - 1)) writer.write(",")
            }

            // end the line
            writer.println()
            valuesWritten = 1
        }
        return valuesWritten
    }

    String convertFieldValue(Object fieldValue) {
        String fieldStr
        if (fieldValue instanceof byte[]) {
            fieldStr = Base64.getEncoder().encodeToString((byte[]) fieldValue)
        } else if (fieldValue instanceof SerialBlob) {
            if (((SerialBlob) fieldValue).length() == 0) {
                fieldStr = ""
            } else {
                byte[] objBytes = ((SerialBlob) fieldValue).getBytes(1, (int) ((SerialBlob) fieldValue).length())
                fieldStr = Base64.getEncoder().encodeToString(objBytes)
            }
        } else if (isoDateTime && fieldValue instanceof java.util.Date) {
            if (fieldValue instanceof Timestamp) {
                fieldStr = fieldValue.toInstant().atZone(ZoneOffset.UTC.normalized()).format(DateTimeFormatter.ISO_INSTANT)
            } else if (fieldValue instanceof java.sql.Date) {
                fieldStr = efi.ecfi.getEci().l10nFacade.formatDate(fieldValue, "yyyy-MM-dd", null, null)
            } else if (fieldValue instanceof java.sql.Time) {
                fieldStr = efi.ecfi.getEci().l10nFacade.formatTime(fieldValue, "HH:mm:ssZ", null, TimeZone.getTimeZone(ZoneOffset.UTC))
            } else {
                fieldStr = fieldValue.toInstant().atZone(ZoneOffset.UTC.normalized()).format(DateTimeFormatter.ISO_DATE_TIME)
            }
        } else {
            fieldStr = ObjectUtilities.toPlainString(fieldValue)
        }
        if (fieldStr == null) fieldStr = ""
        return fieldStr
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
