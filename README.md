<div align="center">

<img src="https://github.com/Klop233/Klop233/raw/main/Noir.jpg" alt="Noir" width="180">

# Noir / ノワール

**第三代 YSM 服务端插件 —— 为 Folia 而生**

模型同步 · 齿轮设置持久化 · MySQL 跨服共享 · 客户端版本门禁

[![Build](https://img.shields.io/github/actions/workflow/status/CloudXmc/Noir/default.yml?branch=main&style=flat-square&label=build)](https://github.com/CloudXmc/Noir/actions)
[![License](https://img.shields.io/github/license/CloudXmc/Noir?style=flat-square)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-brightgreen?style=flat-square)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-21-orange?style=flat-square)](https://adoptium.net/)
[![Folia](https://img.shields.io/badge/Folia-supported-9b59b6?style=flat-square)](https://papermc.io/software/folia)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/MMeowRealms/Noir)

</div>

---

## 目录

- [这是什么](#这是什么)
- [环境要求](#环境要求)
- [快速开始](#快速开始)
- [客户端配套要求](#客户端配套要求)
- [模型与齿轮设置](#模型与齿轮设置)
- [Velocity 跨服同步](#velocity-跨服同步)
- [命令与权限](#命令与权限)
- [语言文件与公屏提示](#语言文件与公屏提示)
- [配置文件结构](#配置文件结构)
- [从源码构建](#从源码构建)
- [TODO](#todo)
- [许可证与致谢](#许可证与致谢)

---

## 这是什么

Noir 是一个把 **YSM（Yes Steve Model）** 客户端模组接入 Paper / Folia 服务端的桥接插件。

服务端统一托管模型包，玩家无需手动复制模型文件；玩家的模型选择、材质、以及模型齿轮里的表情、配饰显隐、身体大小等设置由服务端持久化保存，并可通过 MySQL 在 Velocity 群组的所有子服之间共享。

> 项目名参考自好友 [Klop233](https://github.com/Klop233) 的项目 [Noir](https://github.com/StarCodeClub/Noir/)，算是对 22 年回忆的纪念。
> 那个项目的名字则来源于 [《星空列车与白的旅行》](https://zh.moegirl.org.cn/%E6%98%9F%E7%A9%BA%E5%88%97%E8%BD%A6%E4%B8%8E%E7%99%BD%E7%9A%84%E6%97%85%E8%A1%8C)。

---

## 环境要求 

| 项目 | 要求 |
| :--- | :--- |
| 服务端 | Folia 1.21+ / Paper 1.21+ |
| 构建目标 | Folia / Paper **1.21.11** |
| Java | **21** |
| 客户端模组 | openysm **2.6.5.22**（协议 YSM 2.6.0） |
| 数据库（可选） | MySQL 8 |

---

## 快速开始

1. 把 `Noir-<版本>.jar` 放进 `plugins/`，启动服务端。
2. 首次启动会生成：
   - `plugins/Noir/config.yml`
   - `plugins/Noir/lang/zh_CN.lang`、`en_US.lang`
   - `plugins/Noir/models/` —— 释放内置模型（**更新插件不会覆盖已有文件**）
3. 自定义模型放入 `models/custom/`；需要权限控制的模型放入 `models/auth/`。
4. 执行 `/noir reload` 异步重载模型。

模型文件的加载和缓存生成全部在异步线程执行，不会卡住区域线程。

---

## 客户端配套要求

**服务端只与配套发布的客户端握手。**

客户端在版本检查包里回传自己的构建标识（`openysm:2.6.5.22`）。标识不匹配时：

- 服务端**不会**完成握手，**不会**开始下发模型；
- 只向玩家发送一条提示消息（语言键 `noir.announcement.client_rejected`）；
- **不踢人、不修改任何玩家数据。**

旧客户端不写这个字段，服务端读到空标识，同样按不匹配处理。协议版本仍然是 `2.6.0`，所以老服务端只会忽略这段多余字节，不受影响。

> 升级客户端版本时，必须同步修改 `NoirConstants.ClientRequirements.REQUIRED_CLIENT_BRAND` 与客户端的 `NetworkHandler.CLIENT_BRAND`，两者必须完全一致。

没有安装 YSM 的客户端不会收到任何 YSM 数据，继续使用原版玩家皮肤。

---

## 模型与齿轮设置

模型齿轮中的表情、配饰显隐、身体大小等 `v.roaming.*` 变量，按 **「玩家 + 模型」** 为单位永久保存。

**保存规则**

| 场景 | 行为 |
| :--- | :--- |
| 退出服务器后重进 | ✅ 沿用已保存的设置 |
| Velocity 切换子服 | ✅ 沿用已保存的设置（需启用 MySQL） |
| 切换到另一个模型 | 🔄 使用新模型自己保存过的设置 |
| 切换到从未用过的模型 | 🆕 使用该模型的默认值 |
| 切回原模型 | ✅ 自动恢复，切换不会删除原设置 |

**实现细节**

- 玩家第一次使用某个模型时，服务端先下发一份空的齿轮变量表。客户端收到全量变量后才会开始把齿轮改动回传服务端 —— 缺少这一步会导致该模型的设置永远无法保存（1.3.3 及更早版本的缺陷）。
- 每位玩家最多保留 **64 个**模型的齿轮设置，超出后淘汰最久未使用的模型；**当前使用中的模型不会被淘汰**。
- 正在播放的跳舞动作不会持久化，重进后不会自动继续播放。

---

## Velocity 跨服同步

```
┌──────────┐     ┌──────────┐     ┌──────────┐
│  子服 A  │     │  子服 B  │     │  子服 C  │   ← 每个子服都装 Noir
│  Noir    │     │  Noir    │     │  Noir    │      并放置完全相同的模型包
└────┬─────┘     └────┬─────┘     └────┬─────┘
     └────────────────┼────────────────┘
                ┌─────┴──────┐
                │  MySQL 8   │   ← 共享玩家数据（按 UUID）
                └────────────┘
```

- 每个 Paper / Folia 子服都需要安装 Noir，并放置**完全相同**的 `plugins/Noir/models/` 模型包。
- 在每个子服的 `config.yml` 中启用 `database.enabled`，填写**同一个** MySQL 8 数据库。
- Velocity 代理端本身**不需要**安装 Noir。
- 首次启用 MySQL 时，如果数据库中还没有该玩家，会读取当前子服的本地 `player_data` 并写入数据库；**本地文件不会被删除**。
- 数据库不可用时会保留本地备份；数据库配置可通过 `/noir reload` 验证并热切换。

> ⚠️ 跨子服共享要求所有子服升级到**同一版本**。混用 1.3.2 与更高版本时，旧子服只能读到当前模型的设置。

**存档字段**：齿轮设置以模型 ID 为键保存在 `molang_model_variables`，不依赖模型文件内容 hash。1.3.2 及更早版本的 `molang_current_*`、`molang_datastorage` 存档会在玩家首次进入时自动迁移，旧字段仍会同步写入以便回退。

---

## 命令与权限

主指令 `/noir`，别名 `/ysm`。

| 命令 | 说明 | 权限 | 默认 |
| :--- | :--- | :--- | :--- |
| `/noir help` | 显示帮助 | `noir.command` | op |
| `/noir reload` | 异步重载模型与配置 | `noir.command.reload` | op |
| `/noir setmodel <玩家> <模型ID> [材质]` | 设置玩家模型 | `noir.command.setmodel` | op |
| `/noir players` | 查询已完成握手的 YSM 在线玩家 | `noir.command.players` | op |
| `/noir broadcast` | 开关自己接收全服 YSM 公屏 | `noir.command.broadcast` | **所有人** |
| `/noir kickysm [玩家]` | 踢出指定 YSM 玩家；不填则踢出全部已握手玩家 | `noir.command.kickysm` | op |

所有子命令均支持 Tab 补全，且只对拥有权限的玩家显示。

---

## 语言文件与公屏提示

- 首次启动释放 `plugins/Noir/lang/zh_CN.lang` 与 `en_US.lang`，默认使用 `zh_CN`。
- `noir.prefix` 是所有插件提示的独立前缀，可单独修改颜色和文本。
- 全部文本使用 **MiniMessage** 格式，可直接改颜色和渐变。
- 版本升级时只**追加**缺失的语言键，**不覆盖**已有内容。
- 修改语言文件或 `announcements` 开关后执行 `/noir reload` 即可生效，无需重启。
- YSM 未安装、同步开始、同步成功、客户端版本不匹配等状态会发送到全服公屏。
- 每位玩家默认接收公屏，可用 `/noir broadcast` 永久关闭或重新开启自己的接收，不影响其他玩家。接收设置保存在玩家数据中，启用 MySQL 时跨子服同步。

---

## 配置文件结构

`plugins/Noir/config.yml` 的顶层节点（每项均带完整中文注释）：

| 节点 | 作用 |
| :--- | :--- |
| `model-sync` | 模型同步带宽限速（单玩家 / 全服，单位 Mbps，`0` 为不限速） |
| `model-defaults` | 玩家模型或材质无效时使用的回退模型 ID 与材质 |
| `announcements` | 各类公屏提示的开关 |
| `identity` | 玩家身份模式（`OFFLINE_NAME` / `ONLINE_UUID`） |
| `database` | MySQL 连接与连接池参数 |

升级时只补充缺失节点，**不覆盖**已有设置。

---

## 从源码构建

```bash
git clone https://github.com/CloudXmc/Noir.git
cd Noir
./gradlew build
```

产物位于 `build/libs/`。需要 JDK 21。

运行测试：

```bash
./gradlew test
```

---

## TODO

- [ ] 完成所有的 YSM 功能
- [ ] NPC API（会在 extras 里写）
- [ ] 等 YSM 开源之后重构（雾）

---

## 许可证与致谢

本项目基于 **GPL-3.0** 许可证发布，详见 [LICENSE](LICENSE)。

**声明**：鉴于这个项目目前特殊的性质 —— YSM 迟迟不开源，而等待时间没那么多，所以先借用了 oysm 的轮子。

**致谢**：感谢 oysm 提供的模型加载和缓存生成实现。

**Contributors**：`MrHua269` · `Klop233` · `xWtree`

---

<div align="center">

<img src="https://github.com/Klop233/Klop233/raw/main/CG-4-1.png" alt="">

この旅は、<br>
彼女のなかに<br>
なにを残していくのだろう

</div>
