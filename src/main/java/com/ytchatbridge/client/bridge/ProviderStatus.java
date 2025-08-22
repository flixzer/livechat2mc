package com.ytchatbridge.client.bridge;

public final class ProviderStatus {
    public final String providerName;
    public final boolean running;
    public final String channelTitle;
    public final int msgsPerMin;
    public final long lastLagMs;
    public final String lastError;

    public ProviderStatus(String providerName, boolean running, String channelTitle, int msgsPerMin, long lastLagMs, String lastError) {
        this.providerName = providerName;
        this.running = running;
        this.channelTitle = channelTitle;
        this.msgsPerMin = msgsPerMin;
        this.lastLagMs = lastLagMs;
        this.lastError = lastError;
    }
}
