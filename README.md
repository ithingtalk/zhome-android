# ZHome Android

ZHome / AirMemo client (Android). Licensed under the [MIT License](LICENSE).

Part of the [ithingtalk](https://github.com/ithingtalk) open-source set.

## Important limitations

- **Native libraries are not included** (`clibs` / `libs` / `thirdparty`, VLC frameworks). Download prebuilts from [https://www.ithingtalk.com/zhome-libs.zip](https://www.ithingtalk.com/zhome-libs.zip) and see [DEPENDENCIES.md](DEPENDENCIES.md).
- **Production AWS IDs are not included.** Copy `awsconfig.json.example` to the path expected by the app and fill in your own Cognito / API Gateway / IoT values.
- This tree is meant for reading and rebuilding with your own credentials and prebuilts — it is **not** a turnkey binary release.

## Configure

1. Copy `app/src/main/assets/awsconfig.json.example` → `app/src/main/assets/awsconfig.json`.
2. Create `local.properties` with your Android SDK path (gitignored).
3. Place `clibs/` for NDK/CMake linking. Provide your own release keystore; do not commit it.

## License

MIT — see [LICENSE](LICENSE). Third-party components you add (VLC, OpenSSL, AWS SDK, etc.) keep their own licenses; keep NOTICE/attribution when redistributing binaries.

## Contact

- z345766218@gmail.com
- 345766218@qq.com

