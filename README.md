# Inf's Farlands

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Fabric](https://img.shields.io/badge/Fabric-0.19.3-blue)](https://fabricmc.net/)
[![Minecraft](https://img.shields.io/badge/Minecraft-26.1.2-green)](https://minecraft.net)

![@Overwrite](https://img.shields.io/endpoint?url=https%3A%2F%2Fgist.githubusercontent.com%2FAxorBisInfinitive%2Fdc5cf49ced449ef6cda0c106718f8e53%2Fraw%2Foverwrite.json)
![@Inject](https://img.shields.io/endpoint?url=https%3A%2F%2Fgist.githubusercontent.com%2FAxorBisInfinitive%2Fdc5cf49ced449ef6cda0c106718f8e53%2Fraw%2Finject.json)
![@Mixin](https://img.shields.io/endpoint?url=https%3A%2F%2Fgist.githubusercontent.com%2FAxorBisInfinitive%2Fdc5cf49ced449ef6cda0c106718f8e53%2Fraw%2Fmixin.json)

[English](README.EN_US.md) / [Wiki](docs/wiki/wiki.md)

> [!IMPORTANT]
>
> 本模组是实验性的。
>
> 如果您发现了漏洞，或想提出建议，请创建 Issue 和 Pull Request。

## 特性

- 将 `Vanilla` 的 X/Y/Z 轴界限改为 [-2147483648, 2147483647]。
- 为全 Y 轴编写了独立的**光照引擎**和**地形管线**。
- 修复了一些 `Vanilla` 在高坐标下的异常行为。
- 使用 `Simulation` 架构，探索 `int64` 范围内的世界。

<details>
<summary>模拟（Simulation）</summary>
<br>

模拟架构为位于 `int32` 外但位于 `int64` 内的区域提供支持。

**已实现**：模拟渲染管线、模拟地形管线、模拟 `AABB` 碰撞。

**技术性内容**：

- 使用 `Binary256` 存储实体坐标，并且允许**非有限值坐标**。
- 修复了游戏在极高坐标下的一些异常行为。

**未计划**，包括但不限于：交互、`tick`。

</details>

**作为依赖**：`https://axorbisinfinitive.github.io/infs-farlands/maven/`
