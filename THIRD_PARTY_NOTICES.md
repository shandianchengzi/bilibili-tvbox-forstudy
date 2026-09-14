# Third-party notices

The original source code in this repository uses these components:

- **ZXing core 3.5.3**, Copyright ZXing authors, licensed under Apache License 2.0. The native plugin bundles ZXing bytecode to generate QR images locally. Source: https://github.com/zxing/zxing/tree/zxing-3.5.3 . Full license: [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt).
- **JSON-java**, used only by JVM tests, is licensed under the public domain notice in its upstream source: https://github.com/stleary/JSON-java . Production uses Android's platform `org.json` implementation; the test library is not shipped in the plugin.

The CatVod `Spider` declaration under `compile-stubs/` is a minimal API signature stub. It is used only to compile against the host application's API and is excluded from the delivered DEX. The repository does not redistribute a third-party spider bundle or TVBox APK.

Bilibili names and marks belong to their respective owners. This project is not affiliated with Bilibili or TVBox.
