package com.ytchatbridge.client.bridge;

import com.ytchatbridge.client.model.ChatMessage;

public interface ChatProvider {
    void start(String videoIdOrUrl);
    void stop();
    ProviderStatus status();
    void setListener(Listener listener);

    interface Listener {
        void onMessage(ChatMessage msg);
        void onInfo(String info);
        void onError(String error);
    }
}
