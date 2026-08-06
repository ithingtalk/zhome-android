# Native dependencies (not included)

This repository is **source-only**. Prebuilt `clibs` trees are intentionally omitted.

## Expected layout

Place privately built or obtained libraries under:

```
clibs/
  include/   # headers (OpenSSL, AWS SDK, juice, lsquic, libip2p, ...)
  lib/       # static/shared libraries for your target platform
```

## VLC (iOS / macOS)

Download VideoLAN **MobileVLCKit** / **VLCKit** xcframeworks and place them at the paths expected by the Xcode project (same names as in the private build), or adjust the project to use CocoaPods/SPM.

## libip2p

P2P support requires `libip2p` (not redistributed here). Build from your private P2P sources or obtain binaries under a separate license.

Without these dependencies the app will not link.
