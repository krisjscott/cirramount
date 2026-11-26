package dev.cloudmount;

import dev.cloudmount.config.*;
import dev.cloudmount.mount.MountService;
import dev.cloudmount.rclone.CommandRunner;
import java.nio.file.Path;

/** A separate short-lived JVM proves that a mount outlives its creator. */
public final class MountWorker {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[1]);
        AppPaths paths = new AppPaths(root.resolve("config/cloudmount"), root.resolve("cache/cloudmount"), root.resolve("state/cloudmount"));
        MountService service = new MountService(paths, new CommandRunner(), message -> {});
        if (args[0].equals("mount")) service.mount(new SettingsStore(paths).load());
        else { service.restore(); service.unmount(); }
    }
}
