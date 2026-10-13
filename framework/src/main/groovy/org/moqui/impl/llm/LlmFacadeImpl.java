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
import org.moqui.impl.llm.tools.WriteUiTool;
import org.moqui.llm.LlmClient;
import org.moqui.llm.LlmConversation;
import org.moqui.llm.LlmException;
import org.moqui.llm.LlmProtocol;
import org.moqui.llm.LlmTool;
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
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class LlmFacadeImpl implements LlmFacade {
    private static final Logger logger = LoggerFactory.getLogger(LlmFacadeImpl.class);
    static final String DEFAULT_PROTOCOL = "org.moqui.impl.llm.OpenAiCompatProtocol";

    public final ExecutionContextFactoryImpl ecfi;
    private final Map<String, ProfileState> profileByName = new LinkedHashMap<>();
    private final Map<String, LlmTool.Factory> clientToolTypes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RestClient.RestStream> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> cancelledIds = new ConcurrentHashMap<>();
    /** Conversation ids with a turn running in this JVM. The value is the claim token. */
    private final ConcurrentHashMap<String, Long> activeTurns = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong activeTurnSeq = new java.util.concurrent.atomic.AtomicLong();
    /** Identifies this node in LlmConversation.turnOwner. A fresh value per facade, so a restart is a different owner. */
    private final String nodeId = java.util.UUID.randomUUID().toString();
    /** A turn's lease runs this long past the last renewal. Another node may take a Streaming conversation only after it. */
    public static final long LEASE_SECONDS = 90L;
    private static final long LEASE_RENEW_SECONDS = 20L;
    private volatile java.util.concurrent.ScheduledExecutorService leaseRenewer = null;
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
                logger.info("Initialized LlmFacade profile {} at {} protocol {}", name, org.moqui.util.RestClient.redactUri(state.endpointUrl), state.protocol.getName());
            } catch (Throwable t) {
                logger.error("Error initializing LlmFacade profile " + name, t);
            }
        }
    }

    public void destroy() {
        java.util.concurrent.ScheduledExecutorService ses = leaseRenewer;
        if (ses != null) ses.shutdownNow();
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
    /** True while a turn in this JVM has claimed the conversation and not released it. */
    public String getNodeId() { return nodeId; }

    /** Start the renewal task the first time a turn is claimed. Daemon thread, stopped by destroy(). */
    private synchronized void ensureLeaseRenewer() {
        if (leaseRenewer != null) return;
        java.util.concurrent.ScheduledExecutorService ses = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "llm-lease-renew");
            t.setDaemon(true);
            return t;
        });
        ses.scheduleWithFixedDelay(this::renewLeasesNow, LEASE_RENEW_SECONDS, LEASE_RENEW_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        leaseRenewer = ses;
    }

    /** Push turnLeaseThru forward for every turn running on this node. Public so tests can drive it. */
    public void renewLeasesNow() {
        if (activeTurns.isEmpty()) return;
        org.moqui.impl.context.ExecutionContextImpl eci = null;
        // The scheduler thread has no context of its own. A caller that already has one keeps it.
        boolean ownContext = ecfi.getActiveExecutionContext() == null;
        boolean authzWasDisabled = false;
        try {
            eci = ecfi.getEci();
            authzWasDisabled = eci.getArtifactExecution().disableAuthz();
            org.moqui.context.TransactionFacade tf = eci.getTransaction();
            boolean began = tf.begin(30);
            try {
                java.sql.Timestamp thru = new java.sql.Timestamp(System.currentTimeMillis() + LEASE_SECONDS * 1000L);
                for (String id : new ArrayList<>(activeTurns.keySet())) {
                    // Only this node's own rows, so a row another node has since taken over is left alone
                    eci.getEntity().find("moqui.llm.LlmConversation").condition("conversationId", id)
                            .condition("turnOwner", nodeId).updateAll(java.util.Collections.singletonMap("turnLeaseThru", thru));
                }
                tf.commit(began);
            } catch (Throwable t) {
                tf.rollback(began, "renew LLM turn leases", t);
                throw t;
            }
        } catch (Throwable t) {
            logger.warn("Could not renew LLM turn leases: " + t.getMessage());
        } finally {
            if (eci != null) {
                if (ownContext) { try { eci.destroy(); } catch (Throwable ignored) { } }
                else if (!authzWasDisabled) eci.getArtifactExecution().enableAuthz();
            }
        }
    }

    public boolean turnClaimed(String conversationId) {
        return conversationId != null && activeTurns.containsKey(conversationId);
    }
    /** Replace any claim. The token is what {@link #releaseTurn} must pass so a 409 cannot drop another turn's claim. */
    public long claimTurn(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return 0L;
        long token = activeTurnSeq.incrementAndGet();
        activeTurns.put(conversationId, token);
        ensureLeaseRenewer();
        return token;
    }
    public void releaseTurn(String conversationId, long token) {
        if (conversationId == null || token <= 0L) return;
        activeTurns.remove(conversationId, token);
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
        public final String systemLocation;
        public final boolean allowClientSystem;
        public final boolean allowBrowse;
        public final boolean allowRunService;
        public final boolean allowUnprefixedRequest;
        public final boolean allowEnterSim;
        public final boolean allowVueSfc;
        public final boolean summarizeConversation;
        public final List<BasicEntityAllow> allowedBasicEntities;
        public final LlmApiConfig apiConfig;

        /**
         * Builds a ProfileState with defaults for everything a caller does not set, so a profile is written by name rather than
         * by position in a 38-argument constructor. The defaults are those of a profile with no configuration beyond a name.
         */
        static final class Builder {
            String name;
            MNode confNode;
            String url;
            String path;
            String endpointUrl;
            String apiKey = "";
            String authHeaderName = "Authorization";
            String authHeaderValue;
            String model = "";
            String maxTokensParameter;
            boolean allowTxOverHttp;
            int timeoutSeconds = 120;
            float retryInitialSeconds = 2.0f;
            int retryMax = 5;
            boolean timeoutRetry = true;
            int emptyRetries;
            Integer maxTokens;
            Double temperature;
            boolean logContent;
            Map<String, String> extraHeaders = Collections.emptyMap();
            Map<String, String> extraQuery = Collections.emptyMap();
            RestClient.PooledRequestFactory requestFactory;
            LlmProtocol protocol;
            Set<String> allowedEntities = Collections.emptySet();
            List<AllowedPath> allowedPaths = Collections.emptyList();
            boolean allowWriteUi;
            int ssePingSeconds = 15;
            String systemLocation;
            boolean allowClientSystem = true;
            boolean allowBrowse;
            boolean allowRunService;
            boolean allowUnprefixedRequest;
            boolean allowEnterSim;
            boolean allowVueSfc;
            boolean summarizeConversation;
            List<BasicEntityAllow> allowedBasicEntities = Collections.emptyList();
            LlmApiConfig apiConfig = LlmApiConfig.none();

            Builder name(String v) { this.name = v; return this; }
            Builder confNode(MNode v) { this.confNode = v; return this; }
            Builder url(String v) { this.url = v; return this; }
            Builder path(String v) { this.path = v; return this; }
            Builder endpointUrl(String v) { this.endpointUrl = v; return this; }
            Builder apiKey(String v) { this.apiKey = v; return this; }
            Builder authHeaderName(String v) { this.authHeaderName = v; return this; }
            Builder authHeaderValue(String v) { this.authHeaderValue = v; return this; }
            Builder model(String v) { this.model = v; return this; }
            Builder maxTokensParameter(String v) { this.maxTokensParameter = v; return this; }
            Builder allowTxOverHttp(boolean v) { this.allowTxOverHttp = v; return this; }
            Builder timeoutSeconds(int v) { this.timeoutSeconds = v; return this; }
            Builder retryInitialSeconds(float v) { this.retryInitialSeconds = v; return this; }
            Builder retryMax(int v) { this.retryMax = v; return this; }
            Builder timeoutRetry(boolean v) { this.timeoutRetry = v; return this; }
            Builder emptyRetries(int v) { this.emptyRetries = v; return this; }
            Builder maxTokens(Integer v) { this.maxTokens = v; return this; }
            Builder temperature(Double v) { this.temperature = v; return this; }
            Builder logContent(boolean v) { this.logContent = v; return this; }
            Builder extraHeaders(Map<String, String> v) { this.extraHeaders = v; return this; }
            Builder extraQuery(Map<String, String> v) { this.extraQuery = v; return this; }
            Builder requestFactory(RestClient.PooledRequestFactory v) { this.requestFactory = v; return this; }
            Builder protocol(LlmProtocol v) { this.protocol = v; return this; }
            Builder allowedEntities(Set<String> v) { this.allowedEntities = v; return this; }
            Builder allowedPaths(List<AllowedPath> v) { this.allowedPaths = v; return this; }
            Builder allowWriteUi(boolean v) { this.allowWriteUi = v; return this; }
            Builder ssePingSeconds(int v) { this.ssePingSeconds = v; return this; }
            Builder systemLocation(String v) { this.systemLocation = v; return this; }
            Builder allowClientSystem(boolean v) { this.allowClientSystem = v; return this; }
            Builder allowBrowse(boolean v) { this.allowBrowse = v; return this; }
            Builder allowRunService(boolean v) { this.allowRunService = v; return this; }
            Builder allowUnprefixedRequest(boolean v) { this.allowUnprefixedRequest = v; return this; }
            Builder allowEnterSim(boolean v) { this.allowEnterSim = v; return this; }
            Builder allowVueSfc(boolean v) { this.allowVueSfc = v; return this; }
            Builder summarizeConversation(boolean v) { this.summarizeConversation = v; return this; }
            Builder allowedBasicEntities(List<BasicEntityAllow> v) { this.allowedBasicEntities = v; return this; }
            Builder apiConfig(LlmApiConfig v) { this.apiConfig = v; return this; }

            ProfileState build() { return new ProfileState(this); }
        }

        ProfileState(Builder b) {
            this.name = b.name;
            this.confNode = b.confNode;
            this.url = b.url;
            this.path = b.path;
            this.endpointUrl = b.endpointUrl;
            this.apiKey = b.apiKey;
            this.authHeaderName = b.authHeaderName;
            this.authHeaderValue = b.authHeaderValue;
            this.model = b.model;
            this.maxTokensParameter = b.maxTokensParameter;
            this.allowTxOverHttp = b.allowTxOverHttp;
            this.timeoutSeconds = b.timeoutSeconds;
            this.retryInitialSeconds = b.retryInitialSeconds;
            this.retryMax = b.retryMax;
            this.timeoutRetry = b.timeoutRetry;
            this.emptyRetries = b.emptyRetries;
            this.maxTokens = b.maxTokens;
            this.temperature = b.temperature;
            this.logContent = b.logContent;
            this.extraHeaders = b.extraHeaders;
            this.extraQuery = b.extraQuery;
            this.requestFactory = b.requestFactory;
            this.protocol = b.protocol;
            this.allowedEntities = b.allowedEntities;
            this.allowedPaths = b.allowedPaths;
            this.allowWriteUi = b.allowWriteUi;
            this.ssePingSeconds = b.ssePingSeconds > 0 ? b.ssePingSeconds : 15;
            this.systemLocation = b.systemLocation;
            this.allowClientSystem = b.allowClientSystem;
            this.allowBrowse = b.allowBrowse;
            this.allowRunService = b.allowRunService;
            this.allowUnprefixedRequest = b.allowUnprefixedRequest;
            this.allowEnterSim = b.allowEnterSim;
            this.allowVueSfc = b.allowVueSfc;
            this.summarizeConversation = b.summarizeConversation;
            this.allowedBasicEntities = b.allowedBasicEntities != null ? b.allowedBasicEntities : Collections.emptyList();
            this.apiConfig = b.apiConfig != null ? b.apiConfig : LlmApiConfig.none();
        }

        static ProfileState fromConf(String name, MNode node, ExecutionContextFactoryImpl ecfi) {
            // Read auth-header-pattern from the raw map. attribute() SystemBinding-expands
            // ${api-key} to empty (api - key) and would force Azure onto Bearer.
            String authPatternRaw = node.getAttributes() != null ? node.getAttributes().get("auth-header-pattern") : null;
            String url = node.attribute("url");
            String apiKey = node.attribute("api-key");
            if (apiKey != null) apiKey = apiKey.trim();
            String authHeaderName = nvl(node.attribute("auth-header-name"), "Authorization");
            String authHeaderValue = resolveAuthHeaderValue(authPatternRaw, apiKey);
            String model = node.attribute("model");
            if (model == null) model = "";
            // Null when omitted so each protocol can apply its own default (max_tokens vs max_output_tokens).
            String maxTokensParameter = node.attribute("max-tokens-parameter");
            if (maxTokensParameter != null && maxTokensParameter.isBlank()) maxTokensParameter = null;
            boolean allowTxOverHttp = parseBoolean(node.attribute("allow-tx-over-http"), false);
            int timeoutSeconds = parseInt(node.attribute("timeout-seconds"), 120);
            float retryInitialSeconds = parseFloat(node.attribute("retry-initial-seconds"), 2.0f);
            int retryMax = parseInt(node.attribute("retry-max"), 5);
            boolean timeoutRetry = parseBoolean(node.attribute("timeout-retry"), true);
            int emptyRetries = parseInt(node.attribute("empty-retries"), 2);
            Integer maxTokens = parseInteger(node.attribute("max-tokens"));
            Double temperature = parseDouble(node.attribute("temperature"));
            boolean logContent = parseBoolean(node.attribute("log-content"), false);
            boolean allowWriteUi = parseBoolean(node.attribute("allow-write-ui"), false);
            int ssePingSeconds = parseInt(node.attribute("sse-ping-seconds"), 15);
            if (ssePingSeconds < 1) ssePingSeconds = 15;
            String systemLocation = node.attribute("system-location");
            if (systemLocation != null) systemLocation = systemLocation.trim();
            if (systemLocation != null && systemLocation.isEmpty()) systemLocation = null;
            boolean allowClientSystem = parseBoolean(node.attribute("allow-client-system"), false);
            boolean allowBrowse = parseBoolean(node.attribute("allow-browse"), false);
            boolean allowRunService = parseBoolean(node.attribute("allow-run-service"), false);
            boolean allowUnprefixedRequest = parseBoolean(node.attribute("allow-unprefixed-request"), false);
            boolean allowEnterSim = parseBoolean(node.attribute("allow-enter-sim"), false);
            boolean allowVueSfc = parseBoolean(node.attribute("allow-vue-sfc"), false);
            boolean summarizeConversation = parseBoolean(node.attribute("summarize-conversation"), false);

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
            List<BasicEntityAllow> allowedBasic = new ArrayList<>();
            for (MNode be : node.children("allowed-basic-entity")) {
                try {
                    allowedBasic.add(new BasicEntityAllow(be.attribute("package"), be.attribute("entity")));
                } catch (PatternSyntaxException e) {
                    throw new LlmException("Invalid allowed-basic-entity pattern on profile " + name
                            + ": " + e.getMessage());
                }
            }

            String protocolClass = nvl(node.attribute("protocol"), DEFAULT_PROTOCOL);
            LlmProtocol protocol;
            try {
                Class<?> cls = ecfi.getClassLoader().loadClass(protocolClass);
                protocol = (LlmProtocol) cls.getDeclaredConstructor().newInstance();
            } catch (Throwable t) {
                throw new LlmException("Could not load LLM protocol class " + protocolClass + " for profile " + name, t);
            }

            String path = resolvePath(node.attribute("path"), protocol);
            String endpointUrl = OpenAiCompatProtocol.composeEndpointUrl(url, path, extraQuery);

            int poolMax = parseInt(node.attribute("pool-max"), 16);
            int queueSize = parseInt(node.attribute("queue-size"), 64);
            // Parse the api-config before the pool exists, so invalid JSON cannot leak a started HTTP client
            LlmApiConfig apiConfig = LlmApiConfig.fromNode(node, name);
            RestClient.PooledRequestFactory rf = new RestClient.PooledRequestFactory("llm-" + name);
            rf.poolSize(poolMax).queueSize(queueSize).init();

            return new Builder()
                    .name(name)
                    .confNode(node)
                    .url(url)
                    .path(path)
                    .endpointUrl(endpointUrl)
                    .apiKey(apiKey)
                    .authHeaderName(authHeaderName)
                    .authHeaderValue(authHeaderValue)
                    .model(model)
                    .maxTokensParameter(maxTokensParameter)
                    .allowTxOverHttp(allowTxOverHttp)
                    .timeoutSeconds(timeoutSeconds)
                    .retryInitialSeconds(retryInitialSeconds)
                    .retryMax(retryMax)
                    .timeoutRetry(timeoutRetry)
                    .emptyRetries(emptyRetries)
                    .maxTokens(maxTokens)
                    .temperature(temperature)
                    .logContent(logContent)
                    .extraHeaders(Collections.unmodifiableMap(extraHeaders))
                    .extraQuery(Collections.unmodifiableMap(extraQuery))
                    .requestFactory(rf)
                    .protocol(protocol)
                    .allowedEntities(Collections.unmodifiableSet(allowedEntities))
                    .allowedPaths(Collections.unmodifiableList(allowedPaths))
                    .allowWriteUi(allowWriteUi)
                    .ssePingSeconds(ssePingSeconds)
                    .systemLocation(systemLocation)
                    .allowClientSystem(allowClientSystem)
                    .allowBrowse(allowBrowse)
                    .allowRunService(allowRunService)
                    .allowUnprefixedRequest(allowUnprefixedRequest)
                    .allowEnterSim(allowEnterSim)
                    .allowVueSfc(allowVueSfc)
                    .summarizeConversation(summarizeConversation)
                    .allowedBasicEntities(Collections.unmodifiableList(allowedBasic))
                    .apiConfig(apiConfig)
                    .build();
        }

        /** Profile path, or the protocol default when the attribute is omitted. An explicit path is kept. */
        public static String resolvePath(String pathAttribute, LlmProtocol protocol) {
            if (pathAttribute != null && !pathAttribute.isBlank()) return pathAttribute.trim();
            if (protocol != null) {
                String d = protocol.defaultPath();
                if (d != null && !d.isBlank()) return d;
            }
            return OpenAiCompatProtocol.DEFAULT_PATH;
        }

        /** Test helper: no HTTP pool. */
        public static ProfileState forTest(String name, LlmProtocol protocol, String model,
                boolean allowTxOverHttp, int emptyRetries, float retryInitialSeconds, int retryMax) {
            return testBuilder(name, protocol, model, allowTxOverHttp, emptyRetries, retryInitialSeconds, retryMax).build();
        }
        public static ProfileState forTest(String name, LlmProtocol protocol, String model,
                boolean allowTxOverHttp, int emptyRetries, float retryInitialSeconds, int retryMax,
                List<AllowedPath> allowedPaths, boolean allowWriteUi) {
            return testBuilder(name, protocol, model, allowTxOverHttp, emptyRetries, retryInitialSeconds, retryMax)
                    .allowedPaths(allowedPaths != null ? allowedPaths : Collections.emptyList())
                    .allowWriteUi(allowWriteUi).allowClientSystem(false).build();
        }
        public static ProfileState forTest(String name, LlmProtocol protocol, String model,
                boolean allowTxOverHttp, int emptyRetries, float retryInitialSeconds, int retryMax,
                List<AllowedPath> allowedPaths, boolean allowWriteUi, boolean allowUnprefixedRequest,
                boolean allowBrowse, boolean allowRunService) {
            return forTest(name, protocol, model, allowTxOverHttp, emptyRetries, retryInitialSeconds, retryMax,
                    allowedPaths, allowWriteUi, allowUnprefixedRequest, allowBrowse, allowRunService, true);
        }
        public static ProfileState forTest(String name, LlmProtocol protocol, String model,
                boolean allowTxOverHttp, int emptyRetries, float retryInitialSeconds, int retryMax,
                List<AllowedPath> allowedPaths, boolean allowWriteUi, boolean allowUnprefixedRequest,
                boolean allowBrowse, boolean allowRunService, boolean allowClientSystem) {
            return forTest(name, protocol, model, allowTxOverHttp, emptyRetries, retryInitialSeconds, retryMax,
                    allowedPaths, allowWriteUi, allowUnprefixedRequest, allowBrowse, allowRunService,
                    allowClientSystem, false);
        }
        public static ProfileState forTest(String name, LlmProtocol protocol, String model,
                boolean allowTxOverHttp, int emptyRetries, float retryInitialSeconds, int retryMax,
                List<AllowedPath> allowedPaths, boolean allowWriteUi, boolean allowUnprefixedRequest,
                boolean allowBrowse, boolean allowRunService, boolean allowClientSystem, boolean allowEnterSim) {
            return forTest(name, protocol, model, allowTxOverHttp, emptyRetries, retryInitialSeconds, retryMax,
                    allowedPaths, allowWriteUi, allowUnprefixedRequest, allowBrowse, allowRunService,
                    allowClientSystem, allowEnterSim, false);
        }
        public static ProfileState forTest(String name, LlmProtocol protocol, String model,
                boolean allowTxOverHttp, int emptyRetries, float retryInitialSeconds, int retryMax,
                List<AllowedPath> allowedPaths, boolean allowWriteUi, boolean allowUnprefixedRequest,
                boolean allowBrowse, boolean allowRunService, boolean allowClientSystem, boolean allowEnterSim,
                boolean summarizeConversation) {
            return testBuilder(name, protocol, model, allowTxOverHttp, emptyRetries, retryInitialSeconds, retryMax)
                    .allowedPaths(allowedPaths != null ? allowedPaths : Collections.emptyList())
                    .allowWriteUi(allowWriteUi).allowUnprefixedRequest(allowUnprefixedRequest).allowBrowse(allowBrowse)
                    .allowRunService(allowRunService).allowClientSystem(allowClientSystem).allowEnterSim(allowEnterSim)
                    .summarizeConversation(summarizeConversation).build();
        }

        /** A local profile with no HTTP pool. Tests and callers that only need a profile shape start here. */
        static Builder testBuilder(String name, LlmProtocol protocol, String model, boolean allowTxOverHttp,
                int emptyRetries, float retryInitialSeconds, int retryMax) {
            return new Builder().name(name).url("http://127.0.0.1").path(OpenAiCompatProtocol.DEFAULT_PATH)
                    .endpointUrl("http://127.0.0.1/v1/chat/completions").model(model != null ? model : "")
                    .maxTokensParameter("max_tokens").allowTxOverHttp(allowTxOverHttp).emptyRetries(emptyRetries)
                    .retryInitialSeconds(retryInitialSeconds).retryMax(retryMax)
                    .protocol(protocol != null ? protocol : new OpenAiCompatProtocol());
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

    /**
     * One allowed-basic-entity element. package and entity are full-string regexes.
     * A null pattern means that side is not constrained. Both null matches nothing.
     */
    public static final class BasicEntityAllow {
        public final Pattern packagePattern;
        public final Pattern entityPattern;

        public BasicEntityAllow(String packagePattern, String entityPattern) {
            this.packagePattern = compile(packagePattern);
            this.entityPattern = compile(entityPattern);
        }
        private static Pattern compile(String raw) {
            if (raw == null || raw.isBlank()) return null;
            return Pattern.compile(raw.trim());
        }
        public boolean matches(String packageName, String entityName) {
            if (packagePattern == null && entityPattern == null) return false;
            if (packagePattern != null && (packageName == null || !packagePattern.matcher(packageName).matches()))
                return false;
            if (entityPattern != null && (entityName == null || !entityPattern.matcher(entityName).matches()))
                return false;
            return true;
        }
        public static boolean any(List<BasicEntityAllow> rules, String packageName, String entityName) {
            if (rules == null) return false;
            for (BasicEntityAllow rule : rules) {
                if (rule != null && rule.matches(packageName, entityName)) return true;
            }
            return false;
        }
    }
}
