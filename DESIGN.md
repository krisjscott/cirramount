# CloudMount interface

CloudMount is a Linux desktop workspace for independently mounted cloud storage. The daily task is to see which connections are mounted, check transfer progress, and act on the selected connection. The interface is designed for a 1280 × 900 window and remains usable at 1000 × 680 with the detail area scrolling.

## Layout

A compact white toolbar carries the product name and total mounted count. A light gray sidebar keeps connections visible, with a colored status dot and short state label in each row. The selected connection leads the content area with its name, provider, mount state, and the three direct actions: Mount, Unmount, Open folder. A short note explains that closing the window leaves mounts running.

Transfer activity is the leading panel. It shows the current transfer state, speed, transferred bytes, queued uploads, cache use, current items, and quota when available. The activity log is expandable. Connection setup and verification sit beside transfers; removal actions are separated below. Mount and cache preferences follow beneath these panels, so setup does not displace monitoring.

## Visual system

The workspace uses cool near-white `#f5f7fa`, a gray sidebar `#edf1f5`, white panels, graphite text `#202b36`, and blue `#315e9e` for primary actions and selection. Green `#168060` marks a mounted connection; amber marks an attention state. Borders are quiet gray and cards use a small offset shadow. No gradients or decorative glass effects appear in the operating view.

Inter is the primary UI face, with Noto Sans as fallback. The product name is 19px, selected storage 29px, panel titles 17px, body 13px, and supporting labels 11px. Controls share a 7px radius and immediate hover, pressed, focused, and disabled states. Panel radius is 12px. Spacing separates related actions while leaving dense transfer information easy to scan.

## States and safety

State labels distinguish mounted, ready, needs setup, and needs attention. Unknown quota remains unknown. Empty metrics use dashes and no invented account data. Settings and credentials are disabled while a mount is owned. Unmount still refuses pending writes and uncertain completion. Deleting local data is a separate action with confirmation; provider files are never deleted. Keyboard focus remains visible, dialogs use the same control palette, and the content area scrolls when the window is short.

The OAuth browser response page remains a separate surface. Its callback confirmation and error behavior are unchanged.
