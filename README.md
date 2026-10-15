# Inf's Farlands

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Fabric](https://img.shields.io/badge/Fabric-0.19.3-blue)](https://fabricmc.net/)
[![Minecraft](https://img.shields.io/badge/Minecraft-26.1.2-green)](https://minecraft.net)

![@Overwrite](https://img.shields.io/endpoint?url=https%3A%2F%2Fgist.githubusercontent.com%2FAxorBisInfinitive%2Fdc5cf49ced449ef6cda0c106718f8e53%2Fraw%2Foverwrite.json)
![@Inject](https://img.shields.io/endpoint?url=https%3A%2F%2Fgist.githubusercontent.com%2FAxorBisInfinitive%2Fdc5cf49ced449ef6cda0c106718f8e53%2Fraw%2Finject.json)
![@Mixin](https://img.shields.io/endpoint?url=https%3A%2F%2Fgist.githubusercontent.com%2FAxorBisInfinitive%2Fdc5cf49ced449ef6cda0c106718f8e53%2Fraw%2Fmixin.json)

[English](README.EN_US.md) / [Wiki](docs/wiki/wiki.md)

## 构建

```bash
./gradlew build
```

## 特性

- 将游戏的 X/Y/Z 轴界限改为 [-2147483648, 2147483647]。
- 为全 Y 轴编写了独立的**光照引擎**和**地形管线**。
- 修复了一些游戏在高坐标下的异常行为。

## 提示

本模组不依赖 `Fabric API`。

配置文件位于 `config/infs-farlands.json`。

光照引擎和地形管线目前依然处于**测试阶段**。

## 警告

本模组是实验性的。

某些漏洞可能造成如下影响：

- CTD
- OOM
- 游戏冻结
- 数据损坏

如果您发现了漏洞，或想提出建议，请创建 Issue 和 Pull Request。

## 兼容性

### 已知不兼容的模组

- **C2ME**
- **ScalableLux**

### 已兼容的模组

- **Sodium 0.9.2**
- **Chunky 1.5.3**
