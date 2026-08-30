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
package org.moqui.impl.llm;

import org.moqui.context.ExecutionContext;
import org.moqui.context.LlmFacade;
import org.moqui.impl.context.ExecutionContextFactoryImpl;
import org.moqui.llm.LlmClient;
import org.moqui.llm.LlmConversation;
import org.moqui.llm.LlmException;
import org.moqui.llm.LlmProtocol;
import org.moqui.llm.LlmTool;
import org.moqui.llm.WindowPolicy;
import org.moqui.util.MNode;
import org.moqui.util.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class LlmFacadeImpl implements LlmFacade {
    private static final Logger logger = LoggerFactory.getLogger(LlmFacadeImpl.class);
    static final String DEFAULT_PROTOCOL = "org.moqui.impl.llm.OpenAiCompatProtocol";

    public final ExecutionContextFactoryImpl ecfi;
    private final Map<String, ProfileState> profileByName = new LinkedHashMap<>();
    private final Map<String, LlmTool.Factory> clientToolTypes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RestClient.RestStream> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> cancelledIds = new ConcurrentHashMap<>();
    private final boolean enabledFlag;
    private final String defaultProfileName;
    private boolean anyUrl = false;

    public LlmFacadeImpl(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi;
        MNode facadeNode = ecfi.getConfXmlRoot().first("llm-facade");
        this.enabledFlag = facadeNode == null || !"false".equalsIgnoreCase(nvl(facadeNode.attribute("enabled"), "true"));
        this.defaultProfileName = facadeNode != null ? nvl(facadeNode.attribute("default-profile"), "default") : "default";
        registerClientToolType(WriteUiTool.NAME, WriteUiTool::new);
        if (this.enabledFlag) init(facadeNode);
        else logger.info("LlmFacade disabled (llm-facade.@enabled=false)");
    }

    void init(MNode facadeNode) {
        if (facadeNode == null) {
            logger.warn("No llm-facade element in conf, LlmFacade has no profiles");
            return;
        }
        for (MNode profileNode : facadeNode.children("profile")) {
            profileNode.setSystemExpandAttributes(true);
            String name = profileNode.attribute("name");
            if (name == null || name.isEmpty()) {
                logger.warn("Skipping llm-facade profile with no name");
                continue;
            }
            if (profileByName.containsKey(name)) {
                logger.warn("LlmFacade profile {} already initialized, skipping", name);
                continue;
            }
            if ("true".equalsIgnoreCase(profileNode.attribute("disabled"))) {
                logger.info("LlmFacade profile {} is disabled, skipping", name);
                continue;
            }
            String url = profileNode.attribute("url");
            if (url == null || url.isBlank()) {
                logger.warn("LlmFacade profile {} has no url, skipping", name);
                continue;
            }
            try {
                ProfileState state = ProfileState.fromConf(name, profileNode, ecfi);
                profileByName.put(name, state);
                anyUrl = true;
                logger.info("Initialized LlmFacade profile {} at {} protocol {}", name, state.endpointUrl, state.protocol.getName());
            } catch (Throwable t) {
                logger.error("Error initializing LlmFacade profile " + name, t);
            }
        }
    }

    public void destroy() {
        for (ProfileState state : profileByName.values()) {
            try {
                if (state.requestFactory != null) state.requestFactory.destroy();
            } catch (Throwable t) {
                logger.error("Error destroying LLM request factory for profile " + state.name, t);
            }
        }
        profileByName.clear();
    }

    @Override
    public LlmClient getDefault() { return getClient(defaultProfileName); }

    @Override
    public LlmClient client(String profileName) { return getClient(profileName); }

    @Override
    public LlmClient getClient(String profileName) {
        if (!enabledFlag) throw new LlmException("LLM facade is disabled");
        if (profileName == null || profileName.isBlank())
            throw new LlmException("LLM profile name is required");
        ProfileState state = profileByName.get(profileName);
        if (state == null)
            throw new LlmException("No LLM profile named '" + profileName + "'");
        ExecutionContext ec = ecfi.getExecutionContext();
        return new LlmClientImpl(ec, state);
    }

    @Override
    public List<String> getProfileNames() { return new ArrayList<>(profileByName.keySet()); }

    @Override
    public boolean isEnabled() { return enabledFlag && anyUrl; }

    @Override
    public LlmConversation getConversation(String conversationId) {
        return LlmConversationImpl.load(ecfi.getExecutionContext(), conversationId, true);
    }
    @Override
    public LlmConversation createConversation(String profileName) {
        return createConversation(profileName, null);
    }
    @Override
    public LlmConversation createConversation(String profileName, Map<String, Object> attributes) {
        if (profileName == null || profileName.isBlank()) profileName = defaultProfileName;
        if (!profileByName.containsKey(profileName))
            throw new LlmException("No LLM profile named '" + profileName + "'");
        return LlmConversationImpl.create(ecfi.getExecutionContext(), profileName, attributes);
    }
    @Override
    public void registerClientToolType(String name, LlmTool.Factory factory) {
        if (name == null || name.isBlank() || factory == null)
            throw new LlmException("client tool type name and factory are required");
        clientToolTypes.put(name, factory);
    }

    ProfileState getProfileState(String name) { return profileByName.get(name); }

    /** Register the provider RestStream so /cancel and SSE disconnect can abort it.
     *  If cancel already won, close immediately so persistSuccess cannot run on a live body. */
    public void registerInFlight(String conversationId, RestClient.RestStream stream) {
        if (conversationId == null || conversationId.isBlank() || stream == null) return;
        if (cancelledIds.containsKey(conversationId)) {
            try { stream.close(); } catch (Throwable ignored) { }
            return;
        }
        RestClient.RestStream prev = inFlight.put(conversationId, stream);
        if (prev != null && prev != stream) {
            try { prev.close(); } catch (Throwable ignored) { }
        }
    }
    public void unregisterInFlight(String conversationId, RestClient.RestStream stream) {
        if (conversationId == null || conversationId.isBlank()) return;
        if (stream == null) inFlight.remove(conversationId);
        else inFlight.remove(conversationId, stream);
    }
    public boolean isCancelled(String conversationId) {
        return conversationId != null && cancelledIds.containsKey(conversationId);
    }
    public void clearCancelled(String conversationId) {
        if (conversationId != null) cancelledIds.remove(conversationId);
    }
    /** Mark cancelled and RestStream.close() / Request.abort. True if a stream was closed or the id was marked. */
    public boolean abortInFlight(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return false;
        cancelledIds.put(conversationId, Boolean.TRUE);
        RestClient.RestStream stream = inFlight.remove(conversationId);
        if (stream == null) return true;
        try { stream.close(); } catch (Throwable t) {
            logger.warn("Error aborting in-flight LLM RestStream for conversation " + conversationId, t);
        }
        return true;
    }

    static String nvl(String v, String def) { return v == null || v.isEmpty() ? def : v; }

    /**
     * Substitute the profile api-key into a raw (unexpanded) auth-header-pattern.
     * Omitted/blank pattern defaults to {@code Bearer ${api-key}}. Blank key omits the header.
     */
    public static String resolveAuthHeaderValue(String rawPattern, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return null;
        String pattern = (rawPattern == null || rawPattern.isEmpty()) ? "Bearer ${api-key}" : rawPattern;
        return pattern.replace("${api-key}", apiKey);
    }

    static boolean parseBoolean(String v, boolean def) {
        if (v == null || v.isBlank()) return def;
        return "true".equalsIgnoreCase(v.trim());
    }
    static int parseInt(String v, int def) {
        if (v == null || v.isBlank()) return def;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return def; }
    }
    static Integer parseInteger(String v) {
        if (v == null || v.isBlank()) return null;
        try { return Integer.valueOf(v.trim()); }
        catch (NumberFormatException e) { return null; }
    }
    static Double parseDouble(String v) {
        if (v == null || v.isBlank()) return null;
        try { return Double.valueOf(v.trim()); }
        catch (NumberFormatException e) { return null; }
    }
    static float parseFloat(String v, float def) {
        if (v == null || v.isBlank()) return def;
        try { return Float.parseFloat(v.trim()); }
        catch (NumberFormatException e) { return def; }
    }

    /** Interned per profile: conf, secrets, pool, protocol, allow-lists. Never intern LlmClient. */
    public static final class ProfileState {
        public final String name;
        public final MNode confNode;
        public final String url;
        public final String path;
        public final String endpointUrl;
        public final String apiKey;
        public final String authHeaderName;
        public final String authHeaderValue;
        public final String model;
        public final String maxTokensParameter;
        public final boolean allowTxOverHttp;
        public final int timeoutSeconds;
        public final float retryInitialSeconds;
        public final int retryMax;
        public final boolean timeoutRetry;
        public final int emptyRetries;
        public final WindowPolicy.ContextLimitPolicy contextLimitPolicy;
        public final Integer maxTokens;
        public final Double temperature;
        public final boolean logContent;
        public final Map<String, String> extraHeaders;
        public final Map<String, String> extraQuery;
        public final RestClient.PooledRequestFactory requestFactory;
        public final LlmProtocol protocol;
        public final Set<String> allowedEntities;
        public final List<AllowedPath> allowedPaths;
        public final boolean allowWriteUi;
        public final int ssePingSeconds;

        ProfileState(String name, MNode confNode, String url, String path, String endpointUrl, String apiKey,
                String authHeaderName, String authHeaderValue, String model, String maxTokensParameter,
                boolean allowTxOverHttp, int timeoutSeconds, float retryInitialSeconds, int retryMax,
                boolean timeoutRetry, int emptyRetries, WindowPolicy.ContextLimitPolicy contextLimitPolicy,
                Integer maxTokens, Double temperature, boolean logContent,
                Map<String, String> extraHeaders, Map<String, String> extraQuery,
                RestClient.PooledRequestFactory requestFactory, LlmProtocol protocol,
                Set<String> allowedEntities, List<AllowedPath> allowedPaths,
                boolean allowWriteUi, int ssePingSeconds) {
            this.name = name;
            this.confNode = confNode;
            this.url = url;
            this.path = path;
            this.endpointUrl = endpointUrl;
            this.apiKey = apiKey;
            this.authHeaderName = authHeaderName;
            this.authHeaderValue = authHeaderValue;
            this.model = model;
            this.maxTokensParameter = maxTokensParameter;
            this.allowTxOverHttp = allowTxOverHttp;
            this.timeoutSeconds = timeoutSeconds;
            this.retryInitialSeconds = retryInitialSeconds;
            this.retryMax = retryMax;
            this.timeoutRetry = timeoutRetry;
            this.emptyRetries = emptyRetries;
            this.contextLimitPolicy = contextLimitPolicy;
            this.maxTokens = maxTokens;
            this.temperature = temperature;
            this.logContent = logContent;
            this.extraHeaders = extraHeaders;
            this.extraQuery = extraQuery;
            this.requestFactory = requestFactory;
            this.protocol = protocol;
            this.allowedEntities = allowedEntities;
            this.allowedPaths = allowedPaths;
            this.allowWriteUi = allowWriteUi;
            this.ssePingSeconds = ssePingSeconds > 0 ? ssePingSeconds : 15;
        }

        static ProfileState fromConf(String name, MNode node, ExecutionContextFactoryImpl ecfi) {
            // Read auth-header-pattern from the raw map. attribute() SystemBinding-expands
            // ${api-key} to empty (api - key) and would force Azure onto Bearer.
            String authPatternRaw = node.getAttributes() != null ? node.getAttributes().get("auth-header-pattern") : null;
            String url = node.attribute("url");
            String path = nvl(node.attribute("path"), OpenAiCompatProtocol.DEFAULT_PATH);
            String apiKey = node.attribute("api-key");
            if (apiKey != null) apiKey = apiKey.trim();
            String authHeaderName = nvl(node.attribute("auth-header-name"), "Authorization");
            String authHeaderValue = resolveAuthHeaderValue(authPatternRaw, apiKey);
            String model = node.attribute("model");
            if (model == null) model = "";
            String maxTokensParameter = nvl(node.attribute("max-tokens-parameter"), "max_tokens");
            boolean allowTxOverHttp = parseBoolean(node.attribute("allow-tx-over-http"), false);
            int timeoutSeconds = parseInt(node.attribute("timeout-seconds"), 120);
            float retryInitialSeconds = parseFloat(node.attribute("retry-initial-seconds"), 2.0f);
            int retryMax = parseInt(node.attribute("retry-max"), 5);
            boolean timeoutRetry = parseBoolean(node.attribute("timeout-retry"), true);
            int emptyRetries = parseInt(node.attribute("empty-retries"), 2);
            String clp = nvl(node.attribute("context-limit-policy"), "fail");
            WindowPolicy.ContextLimitPolicy contextLimitPolicy = "trim-once".equalsIgnoreCase(clp)
                    ? WindowPolicy.ContextLimitPolicy.TRIM_AND_RETRY_ONCE
                    : WindowPolicy.ContextLimitPolicy.FAIL;
            Integer maxTokens = parseInteger(node.attribute("max-tokens"));
            Double temperature = parseDouble(node.attribute("temperature"));
            boolean logContent = parseBoolean(node.attribute("log-content"), false);
            boolean allowWriteUi = parseBoolean(node.attribute("allow-write-ui"), false);
            int ssePingSeconds = parseInt(node.attribute("sse-ping-seconds"), 15);
            if (ssePingSeconds < 1) ssePingSeconds = 15;

            Map<String, String> extraHeaders = new LinkedHashMap<>();
            for (MNode header : node.children("header")) {
                header.setSystemExpandAttributes(true);
                String hn = header.attribute("name");
                String hv = header.attribute("value");
                if (hn != null && !hn.isEmpty() && hv != null) extraHeaders.put(hn, hv);
            }
            Map<String, String> extraQuery = new LinkedHashMap<>();
            for (MNode query : node.children("query")) {
                query.setSystemExpandAttributes(true);
                String qn = query.attribute("name");
                String qv = query.attribute("value");
                if (qn != null && !qn.isEmpty()) extraQuery.put(qn, qv != null ? qv : "");
            }
            Set<String> allowedEntities = new LinkedHashSet<>();
            for (MNode ent : node.children("allowed-entity")) {
                String en = ent.attribute("name");
                if (en != null && !en.isBlank()) allowedEntities.add(en);
            }
            List<AllowedPath> allowedPaths = new ArrayList<>();
            for (MNode ap : node.children("allowed-path")) {
                String prefix = ap.attribute("prefix");
                if (prefix == null || prefix.isBlank()) continue;
                allowedPaths.add(new AllowedPath(prefix, nvl(ap.attribute("methods"), "GET")));
            }

            String endpointUrl = OpenAiCompatProtocol.composeEndpointUrl(url, path, extraQuery);

            int poolMax = parseInt(node.attribute("pool-max"), 16);
            int queueSize = parseInt(node.attribute("queue-size"), 64);
            RestClient.PooledRequestFactory rf = new RestClient.PooledRequestFactory("llm-" + name);
            rf.poolSize(poolMax).queueSize(queueSize).init();

            String protocolClass = nvl(node.attribute("protocol"), DEFAULT_PROTOCOL);
            LlmProtocol protocol;
            try {
                Class<?> cls = ecfi.getClassLoader().loadClass(protocolClass);
                protocol = (LlmProtocol) cls.getDeclaredConstructor().newInstance();
            } catch (Throwable t) {
                rf.destroy();
                throw new LlmException("Could not load LLM protocol class " + protocolClass + " for profile " + name, t);
            }

            return new ProfileState(name, node, url, path, endpointUrl, apiKey, authHeaderName, authHeaderValue,
                    model, maxTokensParameter, allowTxOverHttp, timeoutSeconds, retryInitialSeconds, retryMax,
                    timeoutRetry, emptyRetries, contextLimitPolicy, maxTokens, temperature, logContent,
                    Collections.unmodifiableMap(extraHeaders), Collections.unmodifiableMap(extraQuery),
                    rf, protocol, Collections.unmodifiableSet(allowedEntities),
                    Collections.unmodifiableList(allowedPaths), allowWriteUi, ssePingSeconds);
        }

        /** Test helper: no HTTP pool. */
        public static ProfileState forTest(String name, LlmProtocol protocol, String model,
                boolean allowTxOverHttp, int emptyRetries, float retryInitialSeconds, int retryMax) {
            return forTest(name, protocol, model, allowTxOverHttp, emptyRetries, retryInitialSeconds, retryMax,
                    Collections.emptyList(), false);
        }
        public static ProfileState forTest(String name, LlmProtocol protocol, String model,
                boolean allowTxOverHttp, int emptyRetries, float retryInitialSeconds, int retryMax,
                List<AllowedPath> allowedPaths, boolean allowWriteUi) {
            if (protocol == null) protocol = new OpenAiCompatProtocol();
            return new ProfileState(name, null, "http://127.0.0.1", OpenAiCompatProtocol.DEFAULT_PATH,
                    "http://127.0.0.1/v1/chat/completions", "", "Authorization", null,
                    model != null ? model : "", "max_tokens", allowTxOverHttp, 120,
                    retryInitialSeconds, retryMax, true, emptyRetries,
                    WindowPolicy.ContextLimitPolicy.FAIL, null, null, false,
                    Collections.emptyMap(), Collections.emptyMap(), null, protocol,
                    Collections.emptySet(),
                    allowedPaths != null ? allowedPaths : Collections.emptyList(),
                    allowWriteUi, 15);
        }
    }

    public static final class AllowedPath {
        public final String prefix;
        public final String methodsCsv;
        public AllowedPath(String prefix, String methodsCsv) {
            this.prefix = prefix;
            this.methodsCsv = methodsCsv;
        }
    }
}
