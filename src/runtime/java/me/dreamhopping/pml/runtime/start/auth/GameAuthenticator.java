package me.dreamhopping.pml.runtime.start.auth;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;

public interface GameAuthenticator {
    AuthData authenticate() throws IOException;

    default <T> T saveAuthData(T data, File dataFile) throws IOException {
        File parent = dataFile.getParentFile();
        if (parent != null) parent.mkdirs();
        try (ObjectOutputStream output = new ObjectOutputStream(new FileOutputStream(dataFile))) {
            output.writeObject(data);
        }
        return data;
    }
}
