package org.moqui.util;

import java.util.HexFormat;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.session.DefaultSessionIdManager;

/** Test-only Jetty adapter: session ids are persisted in Visit, so container entropy is application input. */
public final class DeterministicSessionIdManager extends DefaultSessionIdManager {
    public DeterministicSessionIdManager(Server server) {
        super(server);
        if (!DeterministicClock.isEnabled()) throw new IllegalStateException("Deterministic session ids require test mode");
    }

    @Override
    public String newSessionId(long created) {
        return HexFormat.of().formatHex(DeterministicRandom.bytes("session-id", 20));
    }
}
