# CirraMount

Linux desktop app using Java 25, JavaFX, Maven and rclone/FUSE. One window manages multiple Google Drive, Amazon S3, S3-compatible, Microsoft OneDrive, Dropbox, WebDAV and SFTP connections.

The connection sidebar selects independently saved credentials, local mount/cache settings, transfers and quota. Users can add several accounts from the same provider and keep them mounted together. Closing the window keeps every daemon running; reopening reattaches safely. Explicit unmount affects only the selected connection and refuses unfinished writes or unknown completion.

The original Google account is discovered without moving credentials, changing settings or interrupting its daemon. Browser consent uses a shared branded result page and staged replacement verified by a real provider directory read. No cloud credentials or fabricated account/transfer data ship with the product.

Distribution packaging, systemd/login mounting, exhaustive rclone backend support and deployment of a modified Nemo sidebar remain separate work. Provider access is subject to the user's credentials and provider policies; unknown quota remains unknown.
