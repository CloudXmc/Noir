# `Noir` / `ノワール`

<img src="https://github.com/Klop233/Klop233/raw/main/Noir.jpg" alt="Noir logo" align="right" width="200">
<p>第三代ysm插件版)</p>

<p>这个项目名字参考自我好友(Klop233)项目(<a href="https://github.com/StarCodeClub/Noir/">Noir</a>)的名字, 算是对我22年回忆的纪念?(x)</p>
<p>PS: 这个项目的参考的原项目(<a href="https://github.com/StarCodeClub/Noir/">Noir</a>)的名字来源于 <a href="https://zh.moegirl.org.cn/%E6%98%9F%E7%A9%BA%E5%88%97%E8%BD%A6%E4%B8%8E%E7%99%BD%E7%9A%84%E6%97%85%E8%A1%8C">《星空列车与白的旅行》</a>(虽然我并没有玩过这个galgame(x))</p>

![Github Action](https://img.shields.io/github/actions/workflow/status/StarCraftOffical/Noir/.github/workflows/gradle.yml?style=flat-square)
![License](https://img.shields.io/github/license/NaturalCodeClub/Hearse?style=flat-square)

[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/MMeowRealms/Noir)

### 服务端支持

- Folia(1.21+)
- Paper(1.21+)

当前构建目标为 Folia/Paper 1.21.11，Java 21。

### 与客户端 YSM 的工作方式

- 插件通过 YSM 2.6.0 通道完成握手和模型同步。
- 只有握手完成的 YSM 客户端会收到模型、动画和资源数据。
- 没有安装 YSM 的客户端不会收到 YSM 数据，继续使用原版玩家皮肤。
- 服务端模型放在 `plugins/Noir/models/`，首次初始化模型目录时释放内置模型；更新插件不会重复覆盖这些文件。
- 自定义模型可放入 `models/custom/`；需要权限控制的模型放入 `models/auth/`。

模型同步会把服务端模型缓存发送给已安装 YSM 的客户端，因此不要求每个玩家手动复制模型文件。
模型文件的加载和缓存生成在异步线程执行；请使用 `/noir reload` 重新加载。

- 模型齿轮中的表情、配饰显隐、身体大小等 `v.roaming.*` 设置按“玩家 + 模型”永久保存。
- 设置只在切换到另一个模型时才“重置”：换上的新模型使用它自己保存过的设置，从未用过的模型才保持模型默认值。
- 切换模型不会删除原模型设置；切回时自动恢复。
- 退出服务器重进、以及在 Velocity 群组中切换子服，都会继续沿用已保存的设置。
- 玩家第一次使用某个模型时，服务端会先下发一份空的齿轮变量表。客户端收到全量变量后才会开始
  把玩家的齿轮改动回传服务端，缺少这一步会导致该模型的设置永远无法被保存（1.3.3 及更早的缺陷）。
- 每位玩家最多保留 64 个模型的齿轮设置，超出后淘汰最久未使用的模型，当前使用中的模型不会被淘汰。
- 正在播放的跳舞动作不会持久化，重进后不会自动继续播放。

### Velocity 跨服模型状态同步

- 每个 Paper/Folia 子服需要安装 Noir，并放置完全相同的 `plugins/Noir/models/` 模型包。
- 在每个子服的 `config.yml` 中启用 `database.enabled`，填写同一个 MySQL 8 数据库。
- 玩家模型 ID、材质和其他持久状态通过 UUID 保存到共享表；Velocity 本身不需要额外安装 Noir 插件。
- 首次启用 MySQL 时，如果数据库中还没有该玩家，会读取当前子服的本地 `player_data` 并写入数据库；本地文件不会被删除。
- 数据库不可用时会保留本地备份，数据库配置可通过 `/noir reload` 验证并热切换。

### 语言文件与状态提示

- 首次启动会将语言文件释放到 `plugins/Noir/lang/zh_CN.lang` 和 `plugins/Noir/lang/en_US.lang`。
- 语言文件中的 `noir.prefix` 是所有插件提示的独立前缀，可单独修改颜色和文本。
- 当前默认使用 `zh_CN.lang`；可直接修改 MiniMessage 颜色和文本。
- 修改语言文件或 `announcements` 开关后执行 `/noir reload`，无需重启服务器。
- YSM 未安装、同步开始、同步成功等状态会发送到全服公屏。
- 每位玩家默认接收全服 YSM 公屏，可用 `/noir broadcast` 永久关闭或重新开启自己的接收；不会影响其他玩家。
- 接收设置保存在玩家数据中，启用 MySQL 时会跨子服同步。
- 模型齿轮设置也保存在同一玩家数据中，启用 MySQL 时会跨子服同步。
- 齿轮设置以模型 ID 为键保存（存档字段 `molang_model_variables`），不依赖模型文件内容 hash；
  1.3.2 及更早版本的 `molang_current_*`、`molang_datastorage` 存档会在玩家首次进入时自动迁移，旧字段仍会同步写入以便回退。
- 跨子服共享要求所有子服都升级到同一版本；混用 1.3.2 与 1.3.3 时，旧子服只能读到当前模型的设置。

### 命令列表
  - `/noir help`：显示帮助
  - `/noir reload`：异步重载模型
  - `/noir setmodel <玩家> <模型ID> [材质]`：设置玩家模型
  - `/noir players`：查询已完成握手、正在使用 YSM 客户端模组的在线玩家
  - `/noir broadcast`：永久开关玩家自己接收全服 YSM 公屏，默认开启
  - `/noir kickysm [玩家]`：指定玩家时踢出该 YSM 玩家；不填玩家时踢出全部已完成握手的 YSM 玩家

### TODO
  - 完成所有的ysm功能
  - NPC api(会在extras里写)
  - 等ysm开源了之后重构(雾)

### 声明
鉴于这个项目目前特殊的性质(轮子目前由于ysm迟迟不开源外加我等待时间没那么多所以就先借用了oysm的轮子了())

### 致谢
感谢oysm给了模型加载和缓存生成的轮子)

### 其他
Contributor: `MrHua269` `Klop233`

---

<div align="center">

<img src="https://github.com/Klop233/Klop233/raw/main/CG-4-1.png" alt=""/>

---
この旅は、 <br>
彼女のなかに <br>
なにを残していくのだろう

</div>
