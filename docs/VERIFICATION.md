# Verification

Validated on this Linux Mint amd64 host with Java 25.0.2, JavaFX 25.0.2 and bundled rclone 1.76.0.

## Multi-connection release

The Maven package build passed **33 tests with zero failures, errors or skips**, including the opt-in FUSE suite (`-Dcloudmount.test.fuse=true`). Tests use temporary directories and synthetic credentials; no real cloud account is touched.

- Multiple accounts receive separate profile, settings, credentials, cache and recovery paths. Legacy Google discovery preserves original credential and session bytes.
- Canonical mount/cache overlap checks catch nested paths and symlinks across connections. Removal refuses an owned mount session.
- Real rclone RC configuration commits a local alias only after a successful directory read, preserves previous credentials on failure, and removes setup staging directories.
- A local WebDAV HTTP fixture verifies actual PROPFIND and Basic authentication and checks stored password obscuring. A local S3 fixture verifies the selected bucket path and signed requests using the supplied test key.
- Real rclone guided configuration for Google Drive, OneDrive and Dropbox reaches the local browser consent endpoint. A synthetic denial renders the custom page and retains previous credentials. These tests do not exchange a real authorization code or access provider APIs.
- Two simultaneous real FUSE mounts read different local backends. Unmounting one leaves the other running and readable; a new registry reconnects to the survivor.
- Another real mount test launches from a separate JVM, waits for that creator JVM to exit, then reconnects, reads/writes, waits for upload, verifies filesystem capacity, refuses busy/open files and safely unmounts.
- Existing tests cover authenticated control API rejection, retained startup descriptors, stale PID reuse, credential replacement, token parsing, transfer queues/cache errors, settings permissions, argument boundaries, timeouts and Google client JSON validation.

An isolated Xvfb walkthrough used **eight synthetic profiles**, including two Google accounts, and opened all seven provider forms. Account switching, the Add storage dialog, all provider choices, minimum-width layout and normal window close passed. The selected-account view, setup form and Add dialog were visually inspected. These screenshot profiles are UI fixtures, not connected user accounts. All XDG config/cache/state directories were isolated from the user's real data. The empty-state preview also uses fresh app directories.

The previous single-provider browser success/error template was checked at desktop/narrow widths. Real local OAuth failure callbacks verify escaped provider text. The current wording is provider-neutral.

## Boundaries

Real Google, Microsoft and Dropbox consent/token exchange, actual cloud transfers, AWS IAM permissions, arbitrary S3-compatible endpoints and live SFTP host-key/authentication behavior require user accounts/servers and were not exercised. SFTP fields and host-key-file validation are implemented; the seven forms do not expose every rclone advanced option.

The existing user's configuration and daemon are not used by tests or migrated. The legacy account remains at its original paths. There is no automatic mount-at-login/systemd mode or distribution installer.

Quota is shown only when the provider reports it. S3 commonly has no fixed account capacity. FUSE filesystem values on an unsupported provider must not be treated as measured cloud quota. Nemo 6.6.3 explicitly disables sidebar bars for no-volume mounts; the optional source patch applies to that version but has not been built or installed.
