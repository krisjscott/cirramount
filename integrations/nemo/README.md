# Nemo 6.6.3 sidebar capacity bars

Nemo 6.6.3 deliberately passes `show_df_percent = FALSE` for mounts without a `GVolume` in `src/nemo-places-sidebar.c` (the branch adding mounts with no volume). A normal user rclone FUSE mount enters that branch. The File System and block-volume branches query capacity and enable the bar instead. Therefore a Java/rclone flag alone cannot enable the sidebar bar in this version of Nemo.

CloudMount now reports the backend's quota through its existing FUSE filesystem statistics and displays the same quota in its own window. `df -h /path/to/mount` and filesystem-aware file properties can read it. The daemon does not override the total with an invented local cache size.

`nemo-6.6.3-fuse-capacity.patch` enables the existing disk renderer for native mounts without a volume when their filesystem reports a valid capacity. It uses the same query as Nemo's existing disk bars. It has been checked for clean application to the upstream 6.6.3 source. **It has not been compiled or installed.** Replacing the file manager is a separate system integration step; the CloudMount build does not patch `/usr/bin/nemo` or restart the user's file manager.

For a Nemo source checkout at tag 6.6.3, review and check it with:

```bash
git apply --check /absolute/path/to/CloudMount/integrations/nemo/nemo-6.6.3-fuse-capacity.patch
```

Building requires Nemo's distribution build dependencies, which are not installed by CloudMount. The query is synchronous in this older codebase; evaluate responsiveness when a remote filesystem is slow or offline before installing a modified build. Newer Nemo source has a different disk-space refresh implementation, so do not apply this version-specific patch to another release.

Owning sources: [Nemo 6.6.3 sidebar](https://github.com/linuxmint/nemo/blob/6.6.3/src/nemo-places-sidebar.c), [rclone filesystem capacity](https://rclone.org/commands/rclone_mount/).
