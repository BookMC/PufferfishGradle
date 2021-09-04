package me.dreamhopping.pml.runtime.start.auth;

public class AuthData {
    private final String username;
    private final String uuid;
    private final String accessToken;

    public AuthData(String username, String uuid, String accessToken) {
        this.username = username;
        this.uuid = uuid;
        this.accessToken = accessToken;
    }

    public String getUsername() {
        return username;
    }

    public String getUuid() {
        return uuid;
    }

    public String getAccessToken() {
        return accessToken;
    }
}
