package me.dreamhopping.pml.runtime.start.auth.microsoft.storage;

import me.dreamhopping.pml.runtime.start.auth.microsoft.storage.data.Oauth;
import me.dreamhopping.pml.runtime.start.auth.microsoft.storage.data.Token;

import java.io.Serializable;

public class SavedMicrosoftAuthData implements Serializable {
    private Oauth oauth;
    private Token accessToken;

    public SavedMicrosoftAuthData(Oauth oauth, Token accessToken) {
        this.oauth = oauth;
        this.accessToken = accessToken;
    }

    public Oauth getOauth() {
        return oauth;
    }

    public Token getAccessToken() {
        return accessToken;
    }

    public void setOauth(Oauth oauth) {
        this.oauth = oauth;
    }

    public void setAccessToken(Token accessToken) {
        this.accessToken = accessToken;
    }
}
