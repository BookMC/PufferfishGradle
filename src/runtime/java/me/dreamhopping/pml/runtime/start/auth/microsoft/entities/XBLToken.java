package me.dreamhopping.pml.runtime.start.auth.microsoft.entities;

import me.dreamhopping.pml.runtime.start.auth.util.TimeUtils;

public class XBLToken {
    private final String token;
    private final long expiry;
    private final String userHash;

    public XBLToken(String token, long expiry, String userHash) {
        this.token = token;
        this.expiry = expiry;
        this.userHash = userHash;
    }

    public String getToken() {
        return token;
    }

    public long getExpiry() {
        return expiry;
    }

    public String getUserHash() {
        return userHash;
    }

    public boolean isExpired() {
        return TimeUtils.secondsSinceEpoch() > expiry;
    }
}
