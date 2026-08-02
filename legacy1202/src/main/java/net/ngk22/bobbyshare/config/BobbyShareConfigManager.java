package net.ngk22.bobbyshare.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.ngk22.bobbyshare.BobbyShare;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

public final class BobbyShareConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File FILE = FabricLoader.getInstance().getConfigDir().resolve("bobbyshare.json").toFile();
    private static BobbyShareConfig config = new BobbyShareConfig();
    public static BobbyShareConfig getConfig() { return config; }
    public static void load() {
        if (!FILE.exists()) { save(); return; }
        try (FileReader reader = new FileReader(FILE)) {
            config = GSON.fromJson(reader, BobbyShareConfig.class);
            if (config == null) config = new BobbyShareConfig();
        } catch (Exception e) { BobbyShare.LOGGER.error("Failed to load configuration", e); config = new BobbyShareConfig(); }
    }
    public static void save() {
        try {
            File parent = FILE.getParentFile();
            if (parent != null) parent.mkdirs();
            try (FileWriter writer = new FileWriter(FILE)) { GSON.toJson(config, writer); }
        } catch (Exception e) { BobbyShare.LOGGER.error("Failed to save configuration", e); }
    }
    private BobbyShareConfigManager() {}
}
