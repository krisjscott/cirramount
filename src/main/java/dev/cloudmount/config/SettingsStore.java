package dev.cloudmount.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;

public final class SettingsStore {
    private final AppPaths paths;
    private final ObjectMapper json = new ObjectMapper();

    public SettingsStore(AppPaths paths) {
        this.paths = paths;
    }

    public MountSettings load() throws IOException {
        if (!Files.exists(paths.settings())) return MountSettings.defaults(paths);
        MountSettings settings = json.readValue(paths.settings().toFile(), MountSettings.class);
        settings.validate();
        return settings;
    }

    public void save(MountSettings settings) throws IOException {
        settings.validate();
        PrivateFiles.write(paths.settings(), json.writerWithDefaultPrettyPrinter().writeValueAsString(settings));
    }
}
