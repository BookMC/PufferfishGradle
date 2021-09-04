package me.dreamhopping.pml.runtime.start.auth.microsoft.storage.data;

import me.dreamhopping.pml.runtime.start.auth.util.TimeUtils;

import java.io.Serializable;

public class Token implements Serializable {
    private final String token;
    private final long expiry;

    public Token(String token, long expiry) {
        this.token = token;
        this.expiry = expiry;
    }

    public String getToken() {
        return token;
    }

    public long getExpiry() {
        return expiry;
    }

    public boolean isExpired() {
        return TimeUtils.secondsSinceEpoch() > expiry;
    }
}
