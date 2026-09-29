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

import org.moqui.Moqui
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ExecutionContext
import org.moqui.impl.llm.FindBasicTool
import org.moqui.impl.llm.LlmClientImpl
import org.moqui.impl.llm.LlmFacadeImpl
import org.moqui.impl.llm.LlmGateway
import org.moqui.impl.llm.SkillIndex
import org.moqui.llm.LlmClient
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification

@IgnoreIf({
    String runtime = System.getProperty("moqui.runtime") ?: "../runtime"
    String conf = System.getProperty("moqui.conf") ?: "conf/MoquiDevConf.xml"
    File direct = new File(conf)
    File nested = new File(runtime, conf.startsWith("conf/") ? conf : "conf/" + new File(conf).name)
    !direct.exists() && !nested.exists() && !new File(runtime, "conf/MoquiDevConf.xml").exists()
})
class LlmFindBasicTests extends Specification {
    @Shared ExecutionContext ec
    @Shared FindBasicTool tool

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        if (!ec.user.userId) ec.user.loginUser("john.doe", "moqui")
        tool = new FindBasicTool([new LlmFacadeImpl.BasicEntityAllow("moqui.basic", null)])
    }

    def "package moqui.basic does not match email or print"() {
        expect:
        new LlmFacadeImpl.BasicEntityAllow("moqui.basic", null).matches("moqui.basic", "Enumeration")
        !new LlmFacadeImpl.BasicEntityAllow("moqui.basic", null).matches("moqui.basic.email", "EmailServer")
        !new LlmFacadeImpl.BasicEntityAllow("moqui.basic", null).matches("moqui.basic.print", "NetworkPrinter")
        !new LlmFacadeImpl.BasicEntityAllow(null, null).matches("moqui.basic", "Enumeration")
        new LlmFacadeImpl.BasicEntityAllow("moqui.basic", "Geo").matches("moqui.basic", "Geo")
        !new LlmFacadeImpl.BasicEntityAllow("moqui.basic", "Geo").matches("moqui.basic", "Enumeration")
    }

    def "catalog lists moqui.basic and omits email and print"() {
        when:
        Map cat = (Map) tool.execute([:], ec)
        List<String> names = (List) cat.entities

        then:
        cat.usage
        names.contains("moqui.basic.Enumeration")
        names.contains("moqui.basic.StatusItem")
        names.contains("moqui.basic.Geo")
        !names.any { it.startsWith("moqui.basic.email.") }
        !names.any { it.startsWith("moqui.basic.print.") }
        !names.any { it.startsWith("mantle.") }
    }

    def "enumeration find returns key and text and rejects other entities"() {
        when:
        boolean denied = false
        try {
            ec.entity.find("moqui.basic.Enumeration").condition("enumTypeId", "EnumerationType").disableAuthz().list()
        } catch (ArtifactAuthorizationException e) {
            denied = true
        }
        // A normal find with authz on, so a denied user proves the tool bypasses it.
        boolean authzDenied = false
        try {
            ec.entity.find("moqui.basic.Enumeration").condition("enumTypeId", "EnumerationType").list()
        } catch (ArtifactAuthorizationException e) {
            authzDenied = true
        }
        Map hit = (Map) tool.execute([
                entityName: "moqui.basic.Enumeration", keyField: "enumId", text: '${description}',
                and: [enumTypeId: "DataSourceType"], limit: 5], ec)
        Map inn = (Map) tool.execute([
                entityName: "moqui.basic.Enumeration", keyField: "enumId", text: "description",
                and: [enumId: ["DST_PURCHASED_DATA", "DST_CUSTOMER_ENTRY"]]], ec)
        Map templ = (Map) tool.execute([
                entityName: "moqui.basic.StatusItem", keyField: "statusId", text: "StatusItemNameTemplate",
                and: [statusTypeId: "_NA_"]], ec)
        Map orNull = (Map) tool.execute([
                entityName: "moqui.basic.Enumeration", keyField: "enumId", text: '${description}',
                and: [enumTypeId: "DataSourceType", parentEnumId: "NO_SUCH"], orNull: ["parentEnumId"], limit: 5], ec)
        Map notEq = (Map) tool.execute([
                entityName: "moqui.basic.Enumeration", keyField: "enumId", text: '${description}',
                notEquals: [enumTypeId: "DataSourceType"], limit: 3], ec)
        Map dated = (Map) tool.execute([
                entityName: "moqui.basic.UomConversion", keyField: "uomConversionId", text: "uomConversionId",
                dateFilter: true, limit: 5], ec)
        Map one = (Map) tool.execute([
                entityName: "moqui.basic.Enumeration", keyField: "enumId", text: '${description}',
                and: [enumTypeId: "DataSourceType"], limit: 1], ec)
        Map mantle = (Map) tool.execute([
                entityName: "mantle.party.PartyClassification", keyField: "partyClassificationId", text: "description"], ec)
        Map email = (Map) tool.execute([
                entityName: "moqui.basic.email.EmailServer", keyField: "emailServerId", text: "description"], ec)
        Map badField = (Map) tool.execute([
                entityName: "moqui.basic.Enumeration", keyField: "enumId", text: '${description}',
                and: [notAField: "x"]], ec)

        then:
        !denied
        authzDenied
        hit.options
        hit.options.size() <= 5
        hit.options[0].keySet() == ["key", "text"] as Set
        inn.options.size() == 2
        templ.options.find { it.key == "_NA_" }?.text
        orNull.options
        notEq.options
        !notEq.error
        dated.options != null
        !dated.error
        one.truncated == true
        one.options.size() == 1
        mantle.error == "entity_not_allowed"
        !mantle.options
        email.error == "entity_not_allowed"
        badField.error == "unknown_field"
    }

    static final List<List> SKILL_FORMS = [
            ["create-internal-organization", "/apps/marble/Party/FindParty", "CreateOrganizationForm", []],
            ["add-party-contact-info", "/apps/marble/Party/EditParty/UpdateContactInfo", "UpdateContactInfo", []],
            ["clone-accounting-settings", "/apps/marble/Accounting/OrgSettings/AcctgPreference", "CloneAcctgSettingsForm", ["cogsMethodEnumId", "sourcePartyId"]],
            ["clone-accounting-settings", "/apps/marble/Accounting/OrgSettings/AcctgPreference", "EditPapForm", []],
            ["create-sales-account", "/apps/marble/Customer/FindCustomer", "CreateAccountForm", []],
            ["add-customer-payment-method", "/apps/marble/Customer/EditCustomer/UpdatePaymentMethodInfo", "PaymentMethodInfoForm", []],
            ["add-sales-contact", "/apps/marble/Customer/EditCustomer", "AddContactAssignForm", []],
            ["add-sales-contact", "/apps/marble/Customer/FindCustomer", "CreateContactForm", []],
            ["create-person-customer", "/apps/marble/Customer/FindCustomer", "CreatePersonForm", ["roleTypeId"]],
            ["create-employee", "/apps/marble/Party/FindParty", "CreateEmployeeForm", []],
            ["create-project", "/apps/marble/Project/FindProject", "NewProject", []],
            ["create-work-task", "/apps/marble/Task/FindTask", "NewTaskForm", []],
            ["add-labor-rate", "/apps/marble/HumanRes/EditRateAmounts", "CreateRateAmount", ["rateTypeEnumId", "ratePurposeEnumId", "timePeriodUomId"]],
            ["record-time-entry", "/apps/my/User/TimeEntries", "TimeEntryList", []],
            ["record-time-entry", "/apps/my/User/TimeEntries/EditTimeEntry", "AddTimeForm", []],
            ["create-project-client-invoice", "/apps/marble/Project/ProjectTimeEntries", "ProjectInvoice", []],
            ["create-asset-product", "/apps/marble/Catalog/Product/FindProduct", "NewProductForm", ["productTypeEnumId", "ownerPartyId"]],
            ["create-asset-product", "/apps/marble/Catalog/Product/EditProduct", "EditProductForm", ["assetTypeEnumId", "assetClassEnumId"]],
            ["create-asset-product", "/apps/marble/Catalog/Product/EditPrices", "NewPriceForm", ["priceTypeEnumId", "pricePurposeEnumId"]],
            ["create-warehouse", "/apps/marble/Facility/FindFacility", "NewFacilityForm", ["facilityTypeEnumId"]],
            ["create-product-store", "/apps/marble/ProductStore/FindProductStore", "NewStoreForm", []],
            ["create-product-store", "/apps/marble/ProductStore/EditProductStore", "EditStoreForm", ["reservationAutoEnumId"]],
            ["receive-inventory-direct", "/apps/marble/Asset/Asset/FindSummary", "ReceiveAssetForm", ["statusId"]],
            ["create-sales-order", "/apps/marble/Order/FindOrder", "CreateSalesOrder", []],
            ["create-sales-order", "/apps/marble/Order/OrderDetail", "AddProductItemForm", []],
            ["ship-sales-order", "/apps/marble/Shipment/ShipmentDetail", "ItemSourceIssuanceList", []],
            ["ship-sales-order", "/apps/marble/Shipment/ShipmentDetail", "SetPackedForm", []],
            ["ship-sales-order", "/apps/marble/Shipment/ShipmentDetail", "SetShippedForm", []],
            ["receive-invoice-payment", "/apps/marble/Accounting/Invoice/EditInvoice", "RecordPaymentForm", []],
            ["create-supplier", "/apps/marble/Supplier/FindSupplier", "CreateOrganizationForm", []],
            ["create-purchase-order", "/apps/marble/Order/FindOrder", "CreatePurchaseOrder", []],
            ["create-purchase-order", "/apps/marble/Order/OrderDetail", "AddProductItemForm", []],
            ["receive-incoming-shipment", "/apps/marble/Shipment/FindShipment", "NewIncomingShipment", []],
            ["receive-incoming-shipment", "/apps/marble/Shipment/ShipmentDetail/ReceiveItem", "ReceiveForm", []],
            ["pay-supplier", "/apps/marble/Accounting/Payment/FindPayment", "NewOutPaymentForm", []],
            ["pay-supplier", "/apps/marble/Accounting/Invoice/EditInvoice", "RecordPaymentForm", []],
            ["create-customer-request", "/apps/marble/Request/FindRequest", "NewRequestForm", []],
            ["create-sales-quote", "/apps/marble/Order/FindOrder", "CreateSalesOrder", []],
            ["process-sales-return", "/apps/marble/Return/FindReturn", "CreateReturn", []],
            ["update-work-task", "/apps/marble/Task/EditTask", "EditTask", []],
            ["add-record-note", "/apps/marble/Party/EditParty", "NewNoteForm", []],
            ["add-record-note", "/apps/marble/Order/OrderDetail", "NewNoteForm", []],
            ["add-record-note", "/apps/marble/Task/TaskSummary", "AddCommentForm", []],
    ]

    def "skill input forms resolve to widget lines"() {
        when:
        Map<String, List<String>> bySkill = new LinkedHashMap<>()
        List<String> missing = []
        for (List row : SKILL_FORMS) {
            String skill = row[0]
            String path = row[1]
            String form = row[2]
            Set<String> locked = new LinkedHashSet<>((List) row[3])
            String section = FormWidgetTips.section(ec, path, form, locked)
            if (section == null) {
                missing.add(skill + " " + path + " " + form)
                continue
            }
            bySkill.computeIfAbsent(skill, { [] }).add(section)
        }
        String runtime = System.getProperty("moqui.runtime") ?: "../runtime"
        String xml = new File(runtime, "component/MarbleERP/data/MarbleErpSkillData.xml").text

        then:
        missing.empty
        for (Map.Entry<String, List<String>> e : bySkill.entrySet()) {
            int start = xml.indexOf('name="' + e.key + '"')
            assert start > 0
            int cdata = xml.indexOf("<![CDATA[", start)
            int end = xml.indexOf("]]>", cdata)
            String body = xml.substring(cdata, end)
            for (String section : e.value) assert body.contains(section)
        }
    }

    def "widget tips name find_basic, entity filters, and lookup transitions"() {
        when:
        String person = FormWidgetTips.section(ec, "/apps/marble/Party/FindParty", "CreatePersonCustForm")
        String task = FormWidgetTips.section(ec, "/apps/marble/Task/FindTask", "NewTaskForm")
        String account = FormWidgetTips.section(ec, "/apps/marble/Party/FindParty", "CreateAccountForm")

        then:
        person.contains("find_basic moqui.basic.Geo")
        person.contains("geoTypeEnumId=GEOT_COUNTRY")
        person.contains("entity mantle.party.PartyClassification")
        person.contains("classificationTypeEnumId=PcltCustomer")
        person.contains("or-null disabled")
        person.contains("Lookup GET `/apps/marble/Party/FindParty/getGeoCountryStates`")
        person.contains("depends countryGeoId")
        task.contains("options 1,2,3,4,5,6,7,8,9")
        task.contains("find_basic moqui.basic.Enumeration")
        task.contains("enumTypeId=WorkEffortPurpose")
        task.contains("parentEnumId=WetTask")
        task.contains("find_basic moqui.basic.StatusItem")
        task.contains("statusTypeId=WorkEffort")
        task.contains("text StatusItemNameTemplate")
        account.contains("/searchPartyList`")
        account.contains("roleTypeId=Account")
        account.contains("server-search")
        account.contains("min-length 2")
    }

    def "catalog inject omits the widgets section"() {
        when:
        String body = "Do the thing.\n\n## Widgets\nForm `x` `y`.\n- a text-line"
        SkillIndex.SkillDoc doc = new SkillIndex.SkillDoc()
        doc.name = "widget-tip-test"
        doc.title = "Widget tip test"
        doc.description = "test"
        doc.risk = "confirm"
        doc.body = body
        String injected = SkillIndex.formatInject(ec, [doc])

        then:
        SkillIndex.withoutWidgets(body) == "Do the thing."
        SkillIndex.widgetsSection(body).startsWith("## Widgets")
        injected.contains("Do the thing.")
        injected.contains("omit `## Widgets`")
        !injected.contains("\n## Widgets")
        !injected.contains("Form `x` `y`.")
        !injected.contains("- a text-line")
    }

    def "assist profile attaches find_basic and the default profile does not"() {
        when:
        LlmClient assist = ec.llm.getClient("assist")
        LlmClient plain = ec.llm.getClient("default")
        def profileField = LlmClientImpl.getDeclaredField("profile")
        profileField.accessible = true
        def toolsField = LlmClientImpl.getDeclaredField("tools")
        toolsField.accessible = true
        def assistProfile = profileField.get(assist)
        def plainProfile = profileField.get(plain)
        LlmGateway.attachServletTools(assist, assistProfile, ["find_basic", "browse"])
        LlmGateway.attachServletTools(plain, plainProfile, ["find_basic", "browse"])
        List assistNames = toolsField.get(assist).collect { it.name }
        List plainNames = toolsField.get(plain).collect { it.name }

        then:
        assistProfile.allowedBasicEntities.size() == 1
        assistNames.contains("find_basic")
        !plainNames.contains("find_basic")
    }
}
