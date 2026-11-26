package dev.cloudmount.mount;

import dev.cloudmount.config.MountSettings;

// Private reattachment credentials
// never include the password in logs.

public record MountSession(int version, MountSettings settings, String mountPath, String deviceName,
                           int port, String password, long pid, long processStartedAt) {
    public MountSession withProcess(long newPid, long startedAt) {
        return new MountSession(version, settings, mountPath, deviceName, port, password, newPid, startedAt);
    }

    @Override
    public String toString() {
        return "MountSession[" + mountPath + ", pid=" + pid + "]";
    }
}
