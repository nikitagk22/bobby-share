package net.ngk22.bobbyshare.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.ngk22.bobbyshare.BobbyShare;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class BobbyShareConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "bobbyshare.json");
    private static BobbyShareConfig config = new BobbyShareConfig();

    public static BobbyShareConfig getConfig() {
        return config;
    }

    public static void load() {
        if (!CONFIG_FILE.exists()) {
            save();
            return;
        }

        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            config = GSON.fromJson(reader, BobbyShareConfig.class);
            if (config == null) {
                config = new BobbyShareConfig();
            }
            BobbyShare.LOGGER.info("Successfully loaded configuration file.");
        } catch (Exception e) {
            BobbyShare.LOGGER.error("Failed to load configuration file, using defaults.", e);
            config = new BobbyShareConfig();
        }
    }

    public static void save() {
        try {
            File parent = CONFIG_FILE.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
                GSON.toJson(config, writer);
                BobbyShare.LOGGER.info("Saved configuration file.");
            }
        } catch (IOException e) {
            BobbyShare.LOGGER.error("Failed to save configuration file.", e);
        }
    }
}
