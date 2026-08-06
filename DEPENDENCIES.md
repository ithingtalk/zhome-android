# Native dependencies (not included)

This repository is **source-only**. Prebuilt `clibs` trees are intentionally omitted.

## Download

Prebuilt native libraries: [https://www.ithingtalk.com/zhome-libs.zip](https://www.ithingtalk.com/zhome-libs.zip)

Download the zip, unpack it, and place the contents into `clibs/` as required by this project (see layout below). VLC frameworks are **not** assumed to be in this zip — obtain them separately if needed.

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

P2P support requires `libip2p` (often included in the zip above, or build from private P2P sources under a separate license).

Without these dependencies the app will not link.
