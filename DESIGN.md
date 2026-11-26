# CloudMount interface

One Linux desktop window, default scene 1280 × 900, minimum 1080 × 680. A 250px connection sidebar stays visible beside a scrollable details area containing connection actions, mount/cache preferences and transfers. The header counts mounted connections. Closing the window leaves all mounts active; Unmount acts only on the selected profile.

Use scene: managing cloud files alongside ordinary desktop applications in ambient light. Palette: header/text `#173b4a`, workspace `#eef3f6`, action `#086d72`, muted `#425a66`, border `#d2dfe5`, information `#dceff0`, warning `#fff0dc` with text `#744312`. White panels; DejaVu Sans controls and DejaVu Sans Mono logs. Product 23px, page title 27px, section title 18px, body 13px. Typical spacing 8/14/24px, panel padding 22px.

Add storage chooses a name, one of seven providers and optional remote path (required bucket/prefix for S3). Setup forms expose provider-relevant fields only; passwords use masked inputs. OAuth opens the browser and guided provider questions appear as dialogs. Google app-client JSON import is separate from the primary Sign in with Google button.

State vocabulary: Not mounted, Mounted in background, Needs attention. Connection verification is separate from mount status. Settings and credentials are disabled while mounted; background operations expose a busy state. Errors provide a recovery action. Native controls retain keyboard focus and validation. Pending writes are distinct from upload completion. Empty metrics use dashes, with no invented transfer or account data.

Quota uses free/total text and a linear bar only when reported by the provider. A failed quota request does not turn transfer monitoring into a failure. Activity labels identify each connection. Long paths remain inside text fields; large provider forms and detail content scroll.

The browser response page shares the blue header, light background, white content and teal success treatment. It confirms callback receipt while the desktop app checks access; errors are escaped. It has a narrow-screen layout, no external assets, no unsupported deep-link or tab-closing button.
