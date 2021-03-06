package me.dreamhopping.pml.runtime.start;

import me.dreamhopping.pml.runtime.start.args.StartArgs;
import me.dreamhopping.pml.runtime.start.auth.AuthData;
import me.dreamhopping.pml.runtime.start.auth.GameAuthenticator;
import me.dreamhopping.pml.runtime.start.auth.io.IOUtil;
import me.dreamhopping.pml.runtime.start.auth.microsoft.MicrosoftGameAuthenticator;
import me.dreamhopping.pml.runtime.start.auth.mojang.MojangGameAuthenticator;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public class Start {
    public static void main(String[] args) throws Throwable {
        StartArgs arguments = StartArgs.parse(args);

        boolean isServer = Boolean.parseBoolean(System.getenv().getOrDefault("PG_IS_SERVER", "false"));

        if (!isServer) {
            addArgument(arguments.getLiteralArguments(), "--version", () -> "PufferfishGradle");
            addArgument(arguments.getLiteralArguments(), "--assetIndex", () -> System.getenv("PG_ASSET_INDEX"));
            File assetDir = new File(System.getenv("PG_ASSETS_DIR"));
            if (assetDir.exists()) {
                File assetIndexFile = new File(assetDir, "indexes/" + System.getenv("PG_ASSET_INDEX") + ".json");
                String data;
                try (InputStream i = new FileInputStream(assetIndexFile)) {
                    data = IOUtil.readFully(i);
                }
                if (data.contains("\"virtual\": true")) {
                    assetDir = new File(assetDir, "virtual/legacy");
                } else if (data.contains("\"map_to_resources\": true")) {
                    assetDir = new File("resources").getAbsoluteFile();
                }
            }

            addArgument(arguments.getLiteralArguments(), "--assetsDir", assetDir::getPath);

            boolean shouldAuthenticate = arguments.getAuthUsername() != null && arguments.getAuthPassword() != null || arguments.isMicrosoft();
            String accessToken = "PufferfishGradle";
            String username = "Developer";
            String uuid = UUID.randomUUID().toString();

            if (shouldAuthenticate) {
                GameAuthenticator authenticator;
                if (arguments.isMicrosoft()) {
                    authenticator = new MicrosoftGameAuthenticator();
                } else {
                    authenticator = new MojangGameAuthenticator(arguments.getAuthUsername(), arguments.getAuthPassword());
                }

                AuthData data = authenticator.authenticate();

                accessToken = data.getAccessToken();
                username = data.getUsername();
                uuid = data.getUuid();
            }

            addArgument(arguments.getLiteralArguments(), "--username", shouldAuthenticate, username);
            addArgument(arguments.getLiteralArguments(), "--uuid", shouldAuthenticate, uuid);
            addArgument(arguments.getLiteralArguments(), "--accessToken", shouldAuthenticate, accessToken);
            addArgument(arguments.getLiteralArguments(), "--userProperties", () -> "{}");
        } else {
            addArgument(arguments.getLiteralArguments(), "--nogui", false, "");
        }

        String mainClass = System.getenv("PG_MAIN_CLASS");
        if (mainClass == null) mainClass = isServer ? "net.minecraft.server.Main" : "net.minecraft.client.main.Main";

        Class<?> cl = Class.forName(mainClass);
        Method m = cl.getMethod("main", String[].class);
        try {
            m.invoke(null, (Object) arguments.getLiteralArguments().toArray(new String[0]));
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }

    private static void addArgument(List<String> list, String arg, Supplier<String> value) {
        addArgument(list, arg, false, value);
    }

    private static void addArgument(List<String> list, String arg, boolean override, String value) {
        addArgument(list, arg, override, () -> value);
    }

    private static void addArgument(List<String> list, String arg, boolean override, Supplier<String> value) {
        if (override || !list.contains(arg)) {
            if (override) {
                int index = list.indexOf(arg);
                while (index != -1) {
                    list.remove(index);
                    list.remove(index);
                    index = list.indexOf(arg);
                }
            }
            list.add(arg);

            String v = value.get();
            if (!v.isEmpty()) {
                list.add(v);
            }
        }
    }
}
