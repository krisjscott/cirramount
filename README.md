# CloudMount

Linux desktop cloud mounts built with Java 25, JavaFX and rclone/FUSE. Keep multiple connections mounted together: Google Drive, Amazon S3, S3-compatible storage, Microsoft OneDrive, Dropbox, WebDAV and SFTP.

## Run

```bash
cd /home/krisjscott/Documents/Program/Java/CloudMount
./run.sh
```

The delivered folder includes compiled classes, runtime dependencies and the verified Linux amd64 rclone engine. JDK 25 or newer is required. Launch/build scripts select a compatible installation without changing the system Java default. For an explicit choice:

```bash
CLOUDMOUNT_JAVA_HOME=/usr/lib/jvm/jdk-25.0.2-oracle-x64 ./run.sh
```

An `Unsupported major.minor version 67.0` error means an older Java runtime is loading JavaFX. IDE project SDK, run configuration and Maven runner must also use JDK 25+. Rebuild changes with `./build.sh`; Maven and internet access are needed for a clean build.

The host needs `/dev/fuse` and `fusermount3` (the distribution's `fuse3` package on Debian/Ubuntu/Mint). Run CloudMount as your normal user.

## Add connections

1. Select **Add storage**, give the connection a name and choose a provider. Multiple connections can use the same provider.
2. For S3, enter a bucket, optionally followed by a prefix, such as `backups/laptop`. For other providers the remote folder is optional.
3. Connect through the provider form. OAuth providers open browser consent; additional provider choices appear in a dialog when needed. Setup can be cancelled and has a five-minute timeout per engine operation.
4. Choose an empty local mount folder and a separate cache folder. Save settings, then **Mount**.
5. Select another connection to configure or mount it. Each connection has its own credentials, settings, cache namespace, transfer statistics and recovery state.

| Provider | Connection details |
| --- | --- |
| Google Drive | A Google Desktop app OAuth client, then browser consent. Use **Google app setup…** to import downloaded client JSON once. The sign-in button opens browser consent directly. |
| Amazon S3 | Bucket/prefix, region and access key/secret; optional session token. Leave both keys blank to use the AWS credential chain, with an optional named profile. |
| S3-compatible storage | Bucket/prefix, HTTP(S) endpoint, optional region, and keys or the AWS credential chain. Uses rclone's generic `Other` provider and path-style addressing. |
| Microsoft OneDrive | Browser authorization, then the account/drive choices returned by rclone. Optional custom app client. |
| Dropbox | Browser authorization; optional custom app client. |
| WebDAV | URL, vendor (`other`, `nextcloud`, `owncloud`), username and password/app password as required by the server. |
| SFTP | Host, port, username, password or private key, optional key passphrase, and an existing `known_hosts` file containing the verified server key. |

Provider policies, permissions and endpoint compatibility still apply. This release offers these seven setup types; it does not expose every backend or advanced flag available in rclone. S3-compatible services with special signing/addressing requirements may need additional integration. SFTP authentication and host verification must already be valid for that server.

### Google app setup

Create a Google Cloud project, enable Drive API, configure consent and create an OAuth client of type **Desktop app**. If in testing, add each signing-in Google account to the test-user list. See the [official client guide](https://rclone.org/drive/#making-your-own-client-id).

Import the downloaded JSON with **Google app setup…**. Only `installed` Desktop credentials are accepted. Alternatively provision `CLOUDMOUNT_GOOGLE_CLIENT_ID` and `CLOUDMOUNT_GOOGLE_CLIENT_SECRET`. The sign-in button uses environment credentials, private `google-oauth.json`, or the client from the legacy Google account, in that order, with the selected account client as a fallback. It does not open client ID/secret fields. If app setup is missing, the app gives a one-time setup message; **Google app setup…** remains available separately. This release requires your own Google OAuth client. Google verification/test-user restrictions still apply.

Full Drive scope enables ordinary file reads, uploads, renames and deletes. No real account credentials ship with the app. OneDrive and Dropbox can use rclone's built-in client or custom app credentials.

### Existing Google connection

The original root `rclone.conf`, settings and mount-session descriptor are discovered as a **Google Drive** legacy connection. Their paths and running daemon are preserved; opening the new interface does not migrate or restart that mount. Close the old window and run `./run.sh` again. A file lock prevents two windows from managing the same app data.

## Background mounts, cache and transfers

Closing the window leaves all mounted connections and uploads running. Reopen the app to reconnect to them. **Unmount** affects only the selected connection. There is no automatic mount-at-login/reboot or installed systemd service; logout behavior follows the host session policy.

New connections default to a unique `~/Cloud/<name>-<id>` mount folder, a private cache base, 20 GiB cache budget, 72-hour unused age and five-second write-back delay. `full` VFS caching stages writes locally, then uploads after file close and the delay. Cache size is a target, not a hard limit: pending/open files can exceed it. Keep local free space available; rclone also targets 1 GiB minimum free space.

Mount folders must be empty. Mount/cache paths cannot contain one another or overlap another connection's mount/cache location. Canonical path checks include symlinks. Preferences and credentials are locked while a mount is owned. Each successful setup gets a fresh cache identity; old caches are retained and never automatically uploaded to a newly connected account.

The app polls each active mount's `core/stats`, `vfs/stats` and `vfs/queue` every two seconds. The selected connection shows speed, bytes transferred during this mount, queued uploads, cache use and progress. Activity messages identify the connection. Quota is queried about every 30 seconds when mounted; providers without a quota API show it as unavailable. A failed status poll never implies uploads completed.

Unmount refuses pending uploads, transfers, cache errors or insufficient-space states. It uses normal `fusermount3 -u` only on its verified mount, then stops only its verified daemon. Busy files must be closed first. There is no force/lazy-unmount button or process-name-wide kill command. A local save can precede cloud upload: close files and wait for **No transfers pending**.

If a daemon stops unexpectedly, keep its cache and recovery state. Do not kill all rclone processes or delete caches. Detach only the affected mount after closing its files, and recover with the same credentials and cache settings. A detached filesystem is not proof that writes reached the provider. A mount is not a complete offline mirror or a multi-writer conflict-resolution system.

## Credentials and local files

| Location under XDG directories | Contents |
| --- | --- |
| `config/cloudmount/accounts/<id>/account.json` | Name, provider and remote folder; no credentials |
| `config/cloudmount/accounts/<id>/rclone.conf` | That connection's provider credentials/tokens |
| `config/cloudmount/accounts/<id>/settings.json` | Mount/cache preferences |
| `state/cloudmount/accounts/<id>/mount-session.json` | Private RC password, mount path and verified engine identity |
| `state/cloudmount/accounts/<id>/rclone.log` | Private engine log, rotated at 2 MiB |
| `cache/cloudmount/accounts/<id>/` or chosen cache folder | Retained file data and pending writes, separated by cache identity |
| `config/cloudmount/google-oauth.json` | Optional shared Google Desktop app client |
| `config/cloudmount/app.lock` | Single-window lock |

Here `config`, `cache` and `state` mean `XDG_CONFIG_HOME`, `XDG_CACHE_HOME` and `XDG_STATE_HOME`, defaulting to `~/.config`, `~/.cache` and `~/.local/state`. Relative XDG values are ignored. The legacy account retains its original files directly under `cloudmount/` in each directory.

Configuration directories use `0700`, files use `0600`, and replacement writes are atomic. **rclone credentials are not encrypted at rest**; password obscuring is not encryption. Keep config and session files private. The app does not change `~/.config/rclone/rclone.conf`. Keys/passwords are sent to setup through authenticated loopback RC, never as process arguments. Mount RC uses a generated password; process PID/start time and FUSE device identity are verified before stopping a saved engine.

**Remove connection** is available only while unmounted and removes its saved credentials/profile. Cloud files and local cache stay intact. Revoke provider authorization separately if needed. Failed/cancelled setup leaves previous credentials untouched; replacement happens only after a real directory read succeeds.

## Browser consent page

All OAuth providers use the bundled CloudMount success/error response page through rclone's configuration template. It has no external assets, escapes provider error text, removes callback query parameters from browser history and sets a no-referrer policy. The response confirms receipt of the callback; the desktop app still finishes verification before offering the mount. Previously opened response tabs are not updated.

## File-manager storage bars

The app displays a quota bar when the provider reports capacity. FUSE also exposes filesystem statistics, but providers without quota support may return synthetic filesystem sizes; these are not a measured cloud quota. Nemo 6.6.3 suppresses sidebar bars for no-volume mounts including these FUSE mounts. The optional version-specific patch in `integrations/nemo/` was checked against upstream source but is not built or installed.

## Tests and limits

```bash
mvn test
mvn -Dcloudmount.test.fuse=true test
```

Tests use synthetic credentials, local HTTP fixtures and temporary local FUSE backends, never real cloud accounts. They verify S3 signing/bucket paths, WebDAV authentication/password obscuring, atomic credential replacement, OAuth denial, independent mounts, reconnection, busy-file refusal, transfer/error parsing, settings permissions and legacy discovery. See `docs/VERIFICATION.md` for the tested boundaries. Real provider consent, uploads and server-specific compatibility require the user's accounts.

An isolated UI preview must set all three XDG paths:

```bash
xvfb-run -a env XDG_CONFIG_HOME=/tmp/cloudmount-preview/config \
  XDG_CACHE_HOME=/tmp/cloudmount-preview/cache XDG_STATE_HOME=/tmp/cloudmount-preview/state \
  ./run.sh --smoke-output=/tmp/cloudmount-preview.png
```

## Engine provenance

`tools/rclone` is official rclone 1.76.0 Linux amd64, MIT licensed (`tools/RCLONE-LICENSE.txt`). The archive SHA-256 matched published HTTPS SHA256SUMS: `b00f00b3266c3764413afe599d3ec316f6f43b3e0ea8a0bc1d186d2133e46df7`. `scripts/setup-rclone.sh` fetches the pinned release. Other CPU architectures need the matching engine. [rclone documentation](https://rclone.org/).
