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
package org.moqui.impl.llm.a2a

import org.moqui.context.ExecutionContext
import org.moqui.impl.llm.SkillIndex
import org.moqui.util.SystemBinding

/**
 * Builds A2A 1.0 AgentCards per request. The public card (/.well-known/agent-card.json) lists one generic skill;
 * the authenticated extended card lists the SkillIndex skills the caller can see. Identity comes from the
 * a2a_agent_* properties, interfaces from the request base URL (or a2a_public_url).
 */
final class A2ACardBuilderImpl {
    static final String PROTOCOL_VERSION = '1.0'
    static final String JSONRPC_PATH = '/llm/a2a/jsonrpc'
    static final int MAX_SKILLS = 100
    private static final Map<String, Object> SECURITY_SCHEMES = [
        basic: [httpAuthSecurityScheme: [scheme: 'Basic', description: 'Moqui username and password']],
        loginKey: [apiKeySecurityScheme: [location: 'header', name: 'login_key', description: 'Moqui login key']]
    ].asImmutable() as Map<String, Object>
    private static final List<Map<String, Object>> SECURITY_REQUIREMENTS = [
        [schemes: [basic: [list: []]]], [schemes: [loginKey: [list: []]]]
    ].asImmutable() as List<Map<String, Object>>

    private A2ACardBuilderImpl() { }

    static boolean enabled() { !'false'.equalsIgnoreCase(property('a2a_enabled')) }

    static Map<String, Object> buildPublic(String baseUrl) {
        card([:], interfacesFor(baseUrl), [genericSkill()], true)
    }

    static Map<String, Object> buildExtended(ExecutionContext ec, Map options = [:]) {
        List<Map> interfaces
        if (options.supportedInterfaces != null) {
            if (!(options.supportedInterfaces instanceof List) || options.supportedInterfaces.isEmpty())
                throw new IllegalArgumentException('supportedInterfaces must contain at least one transport binding')
            interfaces = options.supportedInterfaces.collect { Object value ->
                if (!(value instanceof Map)) throw new IllegalArgumentException('Each supported interface must be an object')
                Map binding = new LinkedHashMap((Map) value)
                if (!binding.url?.toString()?.trim() || !binding.protocolBinding?.toString()?.trim() ||
                        !binding.protocolVersion?.toString()?.trim())
                    throw new IllegalArgumentException('Each supported interface requires url, protocolBinding, and protocolVersion')
                binding
            }
        } else if (options.baseUrl?.toString()?.trim()) {
            interfaces = interfacesFor(options.baseUrl as String)
        } else {
            throw new IllegalArgumentException('supportedInterfaces or baseUrl is required to advertise a transport binding')
        }
        // hard cap: the extended card is built per request from the skills this user can see
        int skillLimit = Math.min(Math.max(options.skillLimit != null ? options.skillLimit as int : MAX_SKILLS, 1), MAX_SKILLS)
        List<SkillIndex.SkillDoc> docs = SkillIndex.retrieve(ec, '', skillLimit)
        List<Map> skills = docs.collect { SkillIndex.SkillDoc doc ->
            List<String> examples = (doc.body ?: '').readLines().collect { it.trim() }
                .findAll { it && !it.startsWith('#') }.take(3)
            [id: doc.name, name: doc.title ?: doc.name,
             description: doc.description ?: "Use the ${doc.title ?: doc.name} Moqui skill".toString(),
             tags: [doc.risk ?: 'confirm', doc.provenanceId ?: 'human'], examples: examples]
        }
        if (!skills) skills.add(genericSkill())
        card(options, interfaces, skills, options.streaming == true)
    }

    static List<Map> interfacesFor(String baseUrl) {
        String base = baseUrl.trim()
        while (base.endsWith('/')) base = base.substring(0, base.length() - 1)
        [[url: base + JSONRPC_PATH, protocolBinding: 'JSONRPC', protocolVersion: PROTOCOL_VERSION]]
    }

    private static Map<String, Object> card(Map options, List<Map> interfaces, List<Map> skills, boolean streaming) {
        Map<String, Object> card = [
            name: options.name ?: property('a2a_agent_name') ?: 'Moqui',
            description: options.description ?: property('a2a_agent_description') ?: 'Moqui enterprise application agent',
            supportedInterfaces: interfaces,
            version: options.version ?: property('a2a_agent_version') ?: '1.0.0',
            capabilities: [
                streaming: streaming,
                pushNotifications: options.pushNotifications == true,
                extendedAgentCard: true
            ],
            securitySchemes: SECURITY_SCHEMES,
            securityRequirements: SECURITY_REQUIREMENTS,
            defaultInputModes: ['text/plain', 'application/json'],
            defaultOutputModes: ['text/plain', 'application/json'],
            skills: skills
        ] as Map<String, Object>
        String organization = property('a2a_provider_organization')
        if (organization) card.provider = [organization: organization, url: property('a2a_provider_url') ?: '']
        card
    }

    private static Map genericSkill() {
        [id: 'moqui-assist', name: 'Moqui Assist', description: 'General Moqui enterprise application assistant',
         tags: ['erp', 'assist'], examples: []]
    }

    private static String property(String name) {
        SystemBinding.getPropOrEnv(name)?.trim() ?: null
    }
}
