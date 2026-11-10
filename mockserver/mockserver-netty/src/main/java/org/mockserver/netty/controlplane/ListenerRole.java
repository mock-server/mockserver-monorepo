package org.mockserver.netty.controlplane;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

/** Listener roles are fixed when accepted, including on HTTP/2 child streams. */
public enum ListenerRole {
    COMBINED, PROXY, MANAGEMENT;

    public static final AttributeKey<ListenerRole> KEY = AttributeKey.valueOf("MOCKSERVER_LISTENER_ROLE");

    public static ListenerRole of(Channel channel) {
        for (Channel current = channel; current != null; current = current.parent()) {
            ListenerRole role = current.attr(KEY).get();
            if (role != null) {
                return role;
            }
        }
        return COMBINED;
    }
}
