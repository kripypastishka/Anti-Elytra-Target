# AntiElytraTarget

![AntiElytraTarget overview](docs/images/01-overview.png)

![AntiElytraTarget features and detection checks](docs/images/02-features.png)

![AntiElytraTarget commands and configuration](docs/images/03-commands-and-config.png)

## Build and verify

Install a **JDK 17** (a JRE alone cannot compile the plugin). The included wrapper
downloads Gradle 8.11 and verifies its distribution checksum.

```sh
./gradlew clean build
```

On Windows, use `gradlew.bat clean build`. The shaded, obfuscated plugin is written
to `build/libs/AntiElytraTarget-5.3.1.3.jar`. Dependencies are downloaded on the
first build; the repository currently includes snapshot dependencies.

`build` runs all 16 regression simulation tasks through `check`, including
transaction acknowledgement and publication, replay resource ownership and task
cancellation, profile retention, and aim calculation compatibility. To run only
the verification tasks:

```sh
./gradlew check
```

An optional HotSpot allocation comparison for `AimStatistics.getJiffDelta` is
available separately; its measurements do not gate the build:

```sh
./gradlew aimAllocationBenchmark
```

GitHub Actions runs the full build on pull requests and pushes to `main`, then
uploads the plugin JAR. These simulations do not start a Minecraft server. See
[verification notes](docs/verification.md) for the covered failures, measured
allocation change, and remaining server checks.
