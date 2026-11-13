# Inf's Farlands

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Fabric](https://img.shields.io/badge/Fabric-0.19.3-blue)](https://fabricmc.net/)
[![Minecraft](https://img.shields.io/badge/Minecraft-26.1.2-green)](https://minecraft.net)

![@Overwrite](https://img.shields.io/endpoint?url=https%3A%2F%2Fgist.githubusercontent.com%2FAxorBisInfinitive%2Fdc5cf49ced449ef6cda0c106718f8e53%2Fraw%2Foverwrite.json)
![@Inject](https://img.shields.io/endpoint?url=https%3A%2F%2Fgist.githubusercontent.com%2FAxorBisInfinitive%2Fdc5cf49ced449ef6cda0c106718f8e53%2Fraw%2Finject.json)
![@Mixin](https://img.shields.io/endpoint?url=https%3A%2F%2Fgist.githubusercontent.com%2FAxorBisInfinitive%2Fdc5cf49ced449ef6cda0c106718f8e53%2Fraw%2Fmixin.json)

[中文](README.md) / [Wiki](docs/wiki.EN_US/wiki.md)

> [!IMPORTANT]
>
> This mod is experimental.
>
> If you find a bug or would like to make a suggestion, please create an Issue and a Pull Request.

## Features

- Changes `Vanilla`'s X/Y/Z axis limits to [-2147483648, 2147483647].
- Implements an independent **lighting engine** and **terrain pipeline** for the entire Y axis.
- Fixes some abnormal `Vanilla` behavior at high coordinates.
- Uses the `Simulation` architecture to explore the world within the `int64` range.

<details>
<summary>Simulation</summary>
<br>

The simulation architecture supports regions outside `int32` but within `int64`.

**Implemented**: simulation rendering pipeline, simulation terrain pipeline, simulation `AABB` collision.

**Technical details**:

- Entity coordinates are stored as `Binary256`, and **non-finite coordinates** are allowed.
- Fixes some abnormal game behavior at extremely high coordinates.

**Not planned**, including but not limited to: interaction, `tick`.

</details>

**As a dependency**: `https://axorbisinfinitive.github.io/infs-farlands/maven/`
