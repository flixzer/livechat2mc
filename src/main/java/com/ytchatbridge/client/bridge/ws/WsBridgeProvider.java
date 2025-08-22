package com.ytchatbridge.client.bridge.ws;

import com.ytchatbridge.client.bridge.ChatProvider;
import com.ytchatbridge.client.bridge.ProviderStatus;
import com.ytchatbridge.client.model.ChatMessage;

import java.util.concurrent.atomic.AtomicBoolean;

public class WsBridgeProvider implements ChatProvider {
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Listener listener;
    private volatile String endpoint = "ws://localhost:8787";

    @Override
    public void start(String videoIdOrUrl) {
        running.set(true);
        if (listener != null) listener.onInfo("WS provider not implemented yet.");
    }

    @Override
    public void stop() { running.set(false); }

    @Override
    public ProviderStatus status() {
        return new ProviderStatus("wsBridge", running.get(), "", 0, 0, running.get()?"" : "stopped");
    }

    @Override
    public void setListener(Listener listener) { this.listener = listener; }
}
