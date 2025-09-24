package ars.rockycube.excel

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.ss.usermodel.*
import org.apache.poi.ss.util.CellReference

class Manipulator {
    /**
     * Modifies an XLSM file with specified worksheet changes and returns the modified file as a byte array
     * @param inputStream InputStream containing the XLSM file
     * @param modifications Map where key is worksheet name and value is a list of cell modifications
     *        Each cell modification is a Map with keys:
     *        - cell: String (e.g. "A1", "B2")
     *        - value: Object (the value to set)
     *        - type: CellType (optional, will be determined automatically if not provided)
     * @return ByteArrayOutputStream containing the modified XLSM file
     * @throws IOException if there are issues with file operations
     * @throws IllegalArgumentException if the input file or modifications are invalid
     */
    static ByteArrayOutputStream modifyXlsm(InputStream inputStream, Map<String, List<Map>> modifications) {
        if (!inputStream) {
            throw new IllegalArgumentException("Input stream cannot be null")
        }
        if (!modifications) {
            throw new IllegalArgumentException("Modifications map cannot be null")
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook(inputStream)) {
            // Process each worksheet
            modifications.each { sheetName, cellModifications ->
                Sheet sheet = workbook.getSheet(sheetName)
                if (!sheet) {
                    throw new IllegalArgumentException("Worksheet '${sheetName}' not found")
                }

                // Apply modifications to cells
                cellModifications.each { mod ->
                    if (!mod.cell) {
                        throw new IllegalArgumentException("Cell reference is required")
                    }

                    CellReference cellRef = new CellReference(mod.cell)
                    Row row = sheet.getRow(cellRef.row)
                    if (!row) {
                        row = sheet.createRow(cellRef.row)
                    }

                    Cell cell = row.getCell(cellRef.col)
                    if (!cell) {
                        cell = row.createCell(cellRef.col)
                    }

                    // Set cell value based on type or auto-detect
                    setCellValue(cell, mod.value, mod.type)
                }
            }

            // Convert modified workbook to byte array
            ByteArrayOutputStream bos = new ByteArrayOutputStream()
            workbook.write(bos)
            return bos
        }
    }

    /**
     * Helper method to set cell value with appropriate type
     */
    private static void setCellValue(Cell cell, Object value, CellType type = null) {
        if (value == null) {
            cell.setBlank()
            return
        }

        if (type) {
            // Use specified type
            switch (type) {
                case CellType.STRING:
                    cell.setCellValue(value.toString())
                    break
                case CellType.NUMERIC:
                    cell.setCellValue(value as Double)
                    break
                case CellType.BOOLEAN:
                    cell.setCellValue(value as Boolean)
                    break
                case CellType.FORMULA:
                    cell.setCellFormula(value.toString())
                    break
                default:
                    cell.setCellValue(value.toString())
            }
        } else {
            // Auto-detect type
            switch (value) {
                case Number:
                    cell.setCellValue(value as Double)
                    break
                case Boolean:
                    cell.setCellValue(value as Boolean)
                    break
                case Date:
                    cell.setCellValue(value as Date)
                    break
                default:
                    cell.setCellValue(value.toString())
            }
        }
    }
}
