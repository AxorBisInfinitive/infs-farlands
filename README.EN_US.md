# Inf's Farlands

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Fabric](https://img.shields.io/badge/Fabric-0.19.3-blue)](https://fabricmc.net/)
[![Minecraft](https://img.shields.io/badge/Minecraft-26.1.2-green)](https://minecraft.net)

![@Overwrite](https://img.shields.io/endpoint?url=https%3A%2F%2Fgist.githubusercontent.com%2FAxorBisInfinitive%2Fdc5cf49ced449ef6cda0c106718f8e53%2Fraw%2Foverwrite.json)
![@Inject](https://img.shields.io/endpoint?url=https%3A%2F%2Fgist.githubusercontent.com%2FAxorBisInfinitive%2Fdc5cf49ced449ef6cda0c106718f8e53%2Fraw%2Finject.json)
![@Mixin](https://img.shields.io/endpoint?url=https%3A%2F%2Fgist.githubusercontent.com%2FAxorBisInfinitive%2Fdc5cf49ced449ef6cda0c106718f8e53%2Fraw%2Fmixin.json)

[Wiki](docs/wiki.EN_US/wiki.md)

## Building

```bash
./gradlew build
```

## Features

- Extends the game's X/Y/Z coordinate limits to [-2147483648, 2147483647].
- Implements an independent **lighting engine** and **terrain generation pipeline** for the entire Y-axis.
- Fixes some abnormal game behavior at high coordinates.

## Notes

This mod does not depend on `Fabric API`.

The configuration file is located at `config/infs-farlands.json`.

The lighting engine and terrain generation pipeline are currently still in the **experimental stage**.

## Warnings

This mod is experimental.

Certain bugs may cause the following:

- CTD
- OOM
- Game freezes
- Data corruption

If you find a bug or would like to make a suggestion, please create an Issue or Pull Request.

## Compatibility

### Known Incompatible Mods

- **C2ME**
- **ScalableLux**

### Known Compatible Mods

- **Sodium 0.9.2**
- **Chunky 1.5.3**
