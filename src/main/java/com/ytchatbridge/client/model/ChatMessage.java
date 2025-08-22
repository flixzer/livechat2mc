package com.ytchatbridge.client.model;

public final class ChatMessage {
    public final String displayName;
    public final String message;
    public final Role role;
    public final long timestamp;

    public ChatMessage(String displayName, String message, Role role, long timestamp) {
        this.displayName = displayName;
        this.message = message;
        this.role = role;
        this.timestamp = timestamp;
    }

    public enum Role { OWNER, MODERATOR, MEMBER, USER }
}
