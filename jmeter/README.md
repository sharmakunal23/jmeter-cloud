# jmeter

The JMeter base Docker image. It bakes in JMeter 5.6.3, the plugin set (via
`PluginsManagerCMD`) and the `user.properties` that fixes the JTL column
format — everything a worker needs to run a test on one host.

**Plugins are baked in at build time, never uploaded at runtime.** To add one,
edit the plugin list in [`Dockerfile`](Dockerfile) and rebuild; the orchestrator
deliberately exposes no plugin-upload API.

**[`user.properties`](user.properties) is a cross-component contract.** It sets
the JTL columns that `jmeter-local-orchestrator`'s `parser/` package reads —
change one and you must change the other, or every row fails to parse.

Build: [`buildImage.sh`](buildImage.sh). `JMETER_INSTALL_MODE={Download,Local}`
picks between a pinned mirror download and a local distribution.
The image and AMI notes (`docs/baseImageDocker.md`, `docs/baseImageAmi.md`) are
kept locally and are not in the public tree.
