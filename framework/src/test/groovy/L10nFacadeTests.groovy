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

import spock.lang.*

import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.entity.EntityValue
import java.sql.Timestamp

class L10nFacadeTests extends Specification {
    @Shared
    ExecutionContext ec

    def setupSpec() {
        // init the framework, get the ec
        ec = Moqui.getExecutionContext()
    }

    def cleanupSpec() {
        ec.destroy()
    }

    @Unroll
    def "get Localized Message (#original - #language #country)"() {
        // NOTE: this relies on a LocalizedMessage records in CommonL10nData.xml
        expect:
        ec.user.setLocale(new Locale(language, country))
        localized == ec.l10n.localize(original)

        cleanup:
        ec.user.setLocale(Locale.US)

        where:
        original | language | country | localized
        "Create" | "en" | ""  | "Create"
        "Create" | "es" | ""  | "Crear"
        "Create" | "es" | "ES"  | "Crear"
        "Create" | "es" | "MX"  | "Crear"
        "Create" | "fr" | ""  | "Cr\u00E9er"
        "Create" | "zh" | ""  | "\u65B0\u5EFA" // for XML: &#26032;&#24314;
        "Not Localized" | "en" | ""  | "Not Localized"
        "Not Localized" | "es" | ""  | "Not Localized"
        "Not Localized" | "zh" | ""  | "Not Localized"
    }

    @Unroll
    def "LocalizedEntityField with Enumeration.description (#enumId - #language #country)"() {
        // NOTE: this relies on a LocalizedEntityField records in CommonL10nData.xml
        setup:
        ec.artifactExecution.disableAuthz()

        expect:
        ec.user.setLocale(new Locale(language, country))
        EntityValue enumValue = ec.entity.find("Enumeration").condition("enumId", enumId).one()
        localized == enumValue.get("description")

        cleanup:
        ec.artifactExecution.enableAuthz()
        ec.user.setLocale(Locale.US)

        where:
        enumId | language | country | localized
        "GEOT_CITY" | "en" | "" | "City"
        "GEOT_CITY" | "es" | ""  | "Ciudad"
        "GEOT_CITY" | "es" | "ES"  | "Ciudad"
        "GEOT_CITY" | "es" | "MX"  | "Ciudad"
        "GEOT_CITY" | "zh" | ""  | "\u5E02" // for XML: &#24066;
        "GEOT_STATE" | "en" | ""  | "State"
        "GEOT_STATE" | "es" | ""  | "Estado"
        "GEOT_COUNTRY" | "es" | ""  | "Pa\u00EDs"
    }

    /* TODO alternative for example
    def "localized message with variable expansion"() {
        // test localized message with variable expansion (ensure translate then expand)
        // NOTE: this relies on a LocalizedMessage record in ExampleL10nData.xml
        expect:
        ec.l10n.localize("Test expansion \${ec.user.locale} original") == "Test expansion \${ec.tenantId} localized"
        ec.resource.expand("Test expansion \${ec.user.locale} original", "") == "Test expansion DEFAULT localized"
    }
    */

    def "format USD and GBP currency in US and UK locales"() {
        expect:
        ec.user.setLocale(Locale.US)
        ec.l10n.formatCurrency(new BigDecimal("12.34"), "USD", 2) == '$12.34'
        ec.l10n.formatCurrency(new BigDecimal("43.21"), "GBP", 2) in ["GBP43.21", "£43.21"]
        ec.user.setLocale(Locale.UK)
        ec.l10n.formatCurrency(new BigDecimal("12.34"), "USD", 2) in ["USD12.34", 'US$12.34']
        ec.l10n.formatCurrency(new BigDecimal("43.21"), "GBP", 2) == "\u00A343.21"

        cleanup:
        // back to the default
        ec.user.setLocale(Locale.US)
    }

    @Unroll
    def "format output value (#value - #format)"() {
        expect:
        result == ec.l10n.format(value, format)

        where:
        value | format | result
        new BigDecimal("5") | "##.#" | "5"
        new BigDecimal("5") | "##.00" | "5.00"
        Timestamp.valueOf("2010-01-02 12:34:56.789") | "yyyy-MM-dd" | "2010-01-02"
        Timestamp.valueOf("2010-01-02 12:34:56.789") | "d MMM yyyy" | "2 Jan 2010"
        Timestamp.valueOf("2010-01-02 12:34:56.789") | "hh:mm:ss" | "12:34:56"
    }

    def "parse time"() {
        expect:
        java.sql.Time.valueOf("12:34:56") == ec.l10n.parseTime("12:34:56", "HH:mm:ss")
        java.sql.Time.valueOf("00:34:56") == ec.l10n.parseTime("12:34:56 AM", "hh:mm:ss a")
        java.sql.Time.valueOf("12:34:56") == ec.l10n.parseTime("12:34:56 PM", "hh:mm:ss a")
    }

    def "parse date"() {
        expect:
        java.sql.Date.valueOf("2010-01-02") == ec.l10n.parseDate("2010-01-02", "yyyy-MM-dd")
        java.sql.Date.valueOf("2010-01-02") == ec.l10n.parseDate("2 Jan 2010", "d MMM yyyy")
    }

    def "parse timestamp"() {
        expect:
        Timestamp.valueOf("2010-01-02 12:34:56.000") == ec.l10n.parseTimestamp("2010-01-02 12:34:56", "yyyy-MM-dd HH:mm:ss")
    }

    // TODO test parseDateTime
    // TODO test parseNumber
}
