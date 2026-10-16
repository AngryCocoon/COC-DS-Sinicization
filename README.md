# COC DS Sinicization（COC 的 DS 汉化）

Fabric 客户端模组：在 Minecraft **26.x** 下自动把缺失的简体中文翻译键用 AI 补全，结果会缓存并写成汉化资源包（resourcepacks 里可复用）。支持融合社区汉化包、进度实时显示、一键清理等。

## AI 创作声明 · 欢迎贡献

本项目**核心代码主要由 DeepSeek AI 生成**，最初由 [DSH](https://github.com/angrycocoon) 搭建脚手架与第一版可用原型，后续版本迭代（0.4.1 → 0.5.7）使用 Trae（Kimi 模型）进行功能扩展、bug 修复与真机验证。所有 AI 产出的代码都经过了人工审查与 Minecraft 真机测试，但可能仍有疏漏。

**欢迎大家提交 issue / PR 帮忙修改补充**——无论是 API 调用策略优化、新功能需求、翻译质量建议、还是 1.20.x 版本移植，所有贡献都会被认真对待。

## 工作原理

### 注入点

模组通过 Mixin 拦截 `net.minecraft.client.resources.language.ClientLanguage.getOrDefault`——这是 Minecraft 每次文本渲染时查表的必经之路。

- **命中缓存** → 直接返回缓存的中文，游戏渲染无感知
- **未命中但 keepOriginal 判定为"保持原文"**（Shift、Discord、模组名等本来就该是英文的键）→ 放行让原版显示英文
- **未命中且可翻译** → 先返回原文让游戏正常渲染，同时把键加入后台翻译队列

### 完整流程

```
游戏启动
  │
  ├─ 1. 资源包写盘（异步：把已有译文写成 resourcepacks/hanhua_pack）
  │
  ├─ 2. 扫描（本地统计）
  │     ├─ 读取 ClientLanguage.storage（玩家当前选中语言 + en_us 兜底的合并表）
  │     ├─ 拉取社区汉化包（Minecraft-Mod-Language-Package / CFPA, CC BY-NC-SA）
  │     └─ 计算覆盖率：已有中文 + 社区包吸收 + keepOriginal / 全量键
  │
  ├─ 3. 缺键进队列（可选：进入世界时只扫新增模组）
  │
  ├─ 4. 批量 AI 调用（DeepSeek, OpenAI 兼容协议）
  │     ├─ 默认 150 条/批, 8 并发, 3 次 3s/8s/20s 退避重试
  │     └─ 429 限流后自动降到下限并发, 队列安静后可回升
  │
  ├─ 5. 缓存 + 写盘（JSON + 资源包）
  │
  └─ 6. 进度 100% → 自动重载资源包让译文立刻生效
```

### 关键机制

| 机制 | 说明 |
|---|---|
| 进度口径统一 | 全场景唯一口径：`progress = coveredKeys / (totalKeys)`，设置页 / 主界面 / `/hanhua status` 同数 |
| keepOriginal 保护 | 扫描和 clearcache 都不清这个集合——它是"模型判定原文本就该是英文"的键，**不可再生的判定结果**不是可再生的译文缓存 |
| 社区包自动融合 | 启动时下载 sha1 校验，扫描时优先吸收社区译文，省 API tokens |
| clearcache 二次确认 | `/hanhua clearcache` 只预览不删；`/hanhua clearcache confirm` 才真正清空译文缓存 + 删除资源包（保留 keepOriginal） |
| 资源包异步写盘 | 避免进主菜单时主线程卡顿；写完后才触发重载，禁止竞态双重重载 |
| 熔断自恢复 | 所有注入点有 AtomicBoolean 熔断 + 单例错误上报，异常绝不逃逸到渲染线程 |

## 安装使用

### 前置

| 依赖 | 版本 |
|---|---|
| Minecraft | 26.x（当前 26.3） |
| Fabric Loader | ≥ 0.19.5 |
| Fabric API | 对应 26.x 版本 |
| ModMenu | 对应 26.x 版本 |
| Cloth Config | 对应 26.x 版本 |

### 安装

1. 下载最新 Release jar（见仓库右侧 Releases）
2. 放入 `.minecraft/mods/`
3. 启动游戏 → 开始菜单左上角自动显示汉化进度
4. 无 API Key 时会弹出输入界面（ESC 可跳过，之后去 ModMenu → COC DS Sinicization 里填）

### 推荐配置

- **模型**：`deepseek-chat`（又快又便宜）
- **批量大小**：150（默认），键量少于 300 时可降到 50
- **并发请求数**：8（默认），网络好可到 24

## 配置项一览

全部在 ModMenu → COC DS Sinicization 设置界面：

### 通用

| 项 | 默认 | 说明 |
|---|---|---|
| 开始扫描 | — | 手动触发全量扫描（统计覆盖率 + 把缺键入队） |
| 启用自动翻译 | 是 | 总开关；关了就不请求 AI，但资源包仍会写 |
| DeepSeek API Key | — | 必填才会翻译；`run/config/hanhua.json` 持久化，**重装 mod 会被要求重新填**（换版本=换 Key，有意设计） |
| 模型 | deepseek-chat | 可选 deepseek-reasoner（贵但更准） |
| API 地址 | https://api.deepseek.com | 填 OpenAI 兼容协议端点也能用 |
| 目标语言 | zh_cn | 只有玩家选中此语言时缺失键才触发 AI 翻译 |
| 进入世界检查新增模组 | 是 | 进世界时对比模组清单，有新增/更新模组才翻译（结果缓存，重复进不花钱） |
| 只翻译新增模组 | 否 | 省钱模式：只有新增/更新模组才自动译；已安装的模组不管 |
| 融合社区汉化包 | 是 | 下载社区现成译文，已有的键不再调 AI |
| 社区包自动更新 | 是 | 启动时检查 sha1 |
| 生成汉化资源包 | 是 | 写成 resourcepacks/hanhua_pack 并自动启用 |
| 进度 100% 后重载 | 是 | 有新译文写入时自动重载让它立刻生效 |
| 调试日志 | 否 | 开了之后控制台会刷屏 |

### 高级

| 项 | 默认 | 说明 |
|---|---|---|
| 批量大小 | 150 | 每个 HTTP 请求塞多少条键（10–400） |
| 请求超时（秒） | 60 | 单个请求的超时 |
| 并发请求数 | 8 | 下限；队列拥挤时自动加到 24，被限流（429）后退回这个值 |
| 缓存条目上限 | 20000 | 超过会淘汰最旧的条目（JSON 持久化，重启不丢） |

## 指令用法（全部在聊天栏）

| 指令 | 说明 |
|---|---|
| `/hanhua` | 同 status |
| `/hanhua status` | 实时状态：覆盖率、API Key、缓存数、队列数、最近错误、资源包状态 |
| `/hanhua scan [modid]` | 手动扫描；不指定 modid 全量扫，指定则只扫该模组（如 `/hanhua scan jei`） |
| `/hanhua reload` | 手动重载资源包（正常不需要，进度 100% 会自动重载） |
| `/hanhua pack` | 强制写出资源包到磁盘（有新译文会覆盖） |
| `/hanhua clearcache` | **预览模式**：只输出将删除的内容（译文缓存条数、资源包状态、进度计数），不删任何数据 |
| `/hanhua clearcache confirm` | **执行删除**：清空译文缓存 + 删除汉化资源包；**保留 keepOriginal 判定记录**（不可再生） |
| `/hanhua key <sk-...>` | 命令行填 Key（设置界面打不进字时的兜底） |
| `/hanhua key` | 查看当前 Key 是否已设置（不回显明文） |
| `/hanhua mods` | 列出已识别的模组清单 + 新增/更新模组 |

## 工具链

本项目使用 **Fabric Loom** 编译，目标平台是 **已命名的 Minecraft 26.x jar**（unobfuscated，无需 yarn mappings）。

### 本地缓存机制（重要）

`maven.fabricmc.net` 在中国网络下极慢（< 5KB/s，容易超时），所以项目有两层兜底缓存：

1. **`.gradle-home/`**（项目内）：预先下载好的 gradle 9.8.0 发行版 + 全部依赖 jar 缓存 + fabric-loom 下载的 Minecraft 客户端 jar
2. **`local-maven/`**：把 fabric-api、modmenu、cloth-config 的 jar 提前 curl 进来，`build.gradle` 把这个目录排在所有仓库最前面优先读取

两个目录都在 `.gitignore` 里，**不会进仓库**。别人 clone 后如果网络正常可以直接连 fabricmc.net 拉取；如果网络也被限速，可以把自己机器上的缓存打包过去。

### 编译要求

```
JDK:      25 (loom 1.18.3 强制)
Gradle:   9.8.0 (distributionUrl = gradle-9.8.0-bin.zip)
Loom:     1.18.3 (锁死, 不得改 -SNAPSHOT)
Loader:   0.19.5
Fabric API: 对应 26.x 的版本号
```

### 编译命令（Windows PowerShell）

```powershell
$env:GRADLE_USER_HOME = "$PWD\.gradle-home"
$env:JAVA_HOME = "C:\Users\你的用户名\AppData\Roaming\.minecraft\runtime\java-runtime-epsilon"
.\.gradle-home\gradle-9.8.0\bin\gradle.bat build --offline
```

> **不要用 `gradlew.bat`**——它的 distributionUrl 指向 gradle-9.5.1，而 loom 1.18.3 要求 api-version ≥ 9.7，会报 "No matching variant" 然后尝试联网下载 9.5.1 卡死。

## 已知限制

| 限制 | 状态 | 备注 |
|---|---|---|
| 仅支持 Minecraft 26.x | 当前 | 26.x 使用 Mojang 官方未混淆 jar，源码类名与 1.20.x 不同，编译配置差异大 |
| 1.20.x 版本 | 规划中 | **可行性已验证**：本机 loom 缓存已有 1.20.1 全套（client/server jar + Mojang mappings），代码改造点仅 4 个文件、约 6 处 API 适配（render extractor 签名、MouseButtonEvent → double 参数、keyPressed 签名、packVersion API）。开分支后可独立开发，不影响 26.3 主线 |
| API 仅测试了 DeepSeek | OpenAI 协议兼容 | 改 `baseUrl` 字段可兼容任何支持 OpenAI 协议的端点 |
| 不碰 1.20.2+ 网络层/注册表 | 无影响 | 本 mod 只扫 lang 目录 + ClientLanguage 注入，不涉及这些大改点 |
| 社区包 CC BY-NC-SA | 自动融合 | 下载后自动吸收译文，但不重新分发社区包本体 |

## 路线图

- [ ] 1.20.1 版本适配（独立分支，loom 1.6.x + yarn 0.92.x + Java 17 目标）
- [ ] 1.20.4 实测验证（pack_format / 注册表变更检查）
- [ ] GitHub Actions CI（需先解决 fabricmc.net 拉取速率问题，可以用 self-hosted runner）
- [ ] 多模组联合包（hanhua-testmod 等测试用例）

## 许可证

MIT License，详见 [LICENSE](./LICENSE)。
