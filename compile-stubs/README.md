# Host API compile contract

`Spider.java` declares the TVBox host API needed by this plugin. It has no runtime
implementation and is compiled into a separate **compile-only** directory.
`scripts/build.sh` supplies it to javac / D8 as a classpath dependency and never
packages it in the plugin. Android and `org.json` are provided by the Android
runtime too. `scripts/verify.py` reads the DEX class definitions and rejects a
plugin that accidentally bundles any of these host classes.
