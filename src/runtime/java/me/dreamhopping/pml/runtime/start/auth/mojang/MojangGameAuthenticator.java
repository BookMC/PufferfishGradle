package me.dreamhopping.pml.runtime.start.auth.mojang;

import me.dreamhopping.pml.runtime.start.auth.AuthData;
import me.dreamhopping.pml.runtime.start.auth.GameAuthenticator;
import me.dreamhopping.pml.runtime.start.auth.http.PostRequests;
import me.dreamhopping.pml.runtime.start.auth.mojang.storage.SavedMojangAuthData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MojangGameAuthenticator implements GameAuthenticator {
    private final Logger LOGGER = LogManager.getLogger(this);

    private final String username;
    private final String password;

    public MojangGameAuthenticator(String username, String password) {
        this.username = username;
        this.password = password;
    }

    @Override
    public AuthData authenticate() throws IOException {
        LOGGER.info("!! MOJANG AUTHENTICATION WILL SOON BE DISCONTINUED !!");
        LOGGER.info("!! MIGRATE YOUR ACCOUNT TO A MICROSOFT ACCOUNT IF YOU CAN !!");

        File dataFile = new File("pg_auth_data_mojang.dat");
        if (dataFile.exists()) {
            SavedMojangAuthData data = null;

            try (ObjectInputStream stream = new ObjectInputStream(new FileInputStream(dataFile))) {
                data = (SavedMojangAuthData) stream.readObject();
            } catch (Throwable e) {
                System.err.println("Failed to read cached authentication data: " + e);
                dataFile.delete();
            }

            if (data != null) {
                try {
                    PostRequests.post(
                        "https://authserver.mojang.com/validate",
                        "accessToken", data.getAccessToken(),
                        "clientToken", data.getClientToken()
                    );
                    return new AuthData(data.getUsername(), data.getUuid(), data.getAccessToken());
                } catch (IllegalStateException e) {
                    try {
                        Map<String, Object> response = PostRequests.post(
                            "https://authserver.mojang.com/refresh",
                            "accessToken", data.getAccessToken(),
                            "clientToken", data.getClientToken()
                        );

                        String accessToken = (String) response.get("accessToken");
                        return saveAuthData(new AuthData(data.getUsername(), data.getUuid(), accessToken), dataFile);
                    } catch (IllegalStateException ex) {
                        return auth(username, password, data.getClientToken(), dataFile);
                    }
                }
            }
        }
        String clientToken = UUID.randomUUID().toString();
        return auth(username, password, clientToken, dataFile);
    }

    @SuppressWarnings("unchecked")
    private AuthData auth(String username, String password, String clientToken, File dataFile) throws IOException {
        Map<String, Object> agentMap = new HashMap<>();
        agentMap.put("name", "Minecraft");
        agentMap.put("version", 1);

        Map<String, Object> data = PostRequests.post(
            "https://authserver.mojang.com/authenticate",
            "username", username,
            "password", password,
            "clientToken", clientToken,
            "agent", agentMap
        );

        String accessToken = (String) data.get("accessToken");
        Map<String, Object> selectedProfile = (Map<String, Object>) data.get("selectedProfile");
        String uuid = (String) selectedProfile.get("id");
        String name = (String) selectedProfile.get("name");
        String uuidMsb = uuid.substring(0, 16);
        String uuidLsb = uuid.substring(16, 32);
        UUID id = new UUID(Long.parseUnsignedLong(uuidMsb, 16), Long.parseUnsignedLong(uuidLsb, 16));

        saveAuthData(new SavedMojangAuthData(accessToken, clientToken, name, id.toString()), dataFile);
        return new AuthData(name, id.toString(), accessToken);
    }
}
