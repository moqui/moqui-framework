import ars.rockycube.entity.BulkEntityHandler

def bulkUpload()
{
    BulkEntityHandler beh = new BulkEntityHandler()
    try {
        return beh.writeChanges(entityName, changes, deletions, serviceAllowedOn)
    } catch (Exception exc){
        ec.logger.error("Error when bulk uploading data: ${exc.message}")
        if (failsafe) return [result: false, message: "Failed on bulk upload: ${exc.message}"]
        ec.message.addPublic(exc.message, 'error')
        throw exc
    }
}