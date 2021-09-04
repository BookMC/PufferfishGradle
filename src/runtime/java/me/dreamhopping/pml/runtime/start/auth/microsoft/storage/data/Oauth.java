package me.dreamhopping.pml.runtime.start.auth.microsoft.storage.data;

import java.io.Serializable;

public class Oauth extends Token implements Serializable {
    private final String refreshToken;

    public Oauth(String token, long expiry, String refreshToken) {
        super(token, expiry);
        this.refreshToken = refreshToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }
}
