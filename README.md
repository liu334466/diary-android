# 日记 Android App

> 仓库：<https://github.com/liu334466/diary-android> ｜ 下载：[Releases](https://github.com/liu334466/diary-android/releases)

极简日记应用：标题 + 富文本正文、分类、标签、全文搜索、日历回顾、本地备份与多主题。
单 Activity + Navigation，Kotlin + XML + ViewBinding，Hilt + Room + DataStore，Material 3。

## 环境要求

- JDK 17
- Android SDK：compileSdk 35，minSdk 28（Android 9 起）
- Gradle 8.9（本机为全局 `gradle` 命令，仓库不含 wrapper）

## 常用命令

```bash
# 编译调试包，产物在 app/build/outputs/apk/debug/app-debug.apk
gradle :app:assembleDebug

# 全部单元测试
gradle testDebugUnitTest

# 单模块测试
gradle :core:database:testDebugUnitTest

# 代码风格：检查 / 自动修复
gradle ktlintCheck
gradle ktlintFormat

# 安装到已连接设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> 若 Kotlin 编译守护进程报 `AccessDenied`，构建会自动回退为进程内编译，仍可正常出包。

## 模块划分

依赖方向固定为 `feature → core`，feature 之间不允许横向依赖。完整说明见 [MODULES.md](MODULES.md)。

| 类型 | 模块 |
| --- | --- |
| 应用 | `app`：单 Activity 宿主、导航图、Application 启动任务 |
| core | `model`、`common`、`database`、`designsystem`、`font`、`fontui`、`richtext`、`backup` |
| feature | `diarylist`（首页/搜索/日历/回收站）、`editor`、`reader`、`category`、`settings`、`fontsetting`、`export` |

## 关键实现约定

- **富文本**：`core:richtext` 负责 JSON 序列化与 Spannable 双向转换，编辑页与阅读页共用同一套渲染。
- **全文搜索**：FTS4 + unicode61；中文额外生成单字与相邻双字（bigram）索引，查询侧拆词 AND 后再用原文 contains 精确过滤（`SearchTokenizer`）。
- **数据库**：Room v3，迁移 SQL 与 `core/database/schemas` 导出的建表语句保持一致；物理删除依赖外键 CASCADE 清理标签关联与索引。
- **主题**：颜色统一走 `dsColor*` 语义属性，`Theme.Diary` / `Theme.Diary.Dark` / `Theme.Diary.Paper` 三套色板，页面不写死色值。
- **备份**：ZIP 包（`backup.json` + `images/` + `fonts/`），恢复为覆盖式并在单事务内重建 FTS 索引。
- **导出**：PNG 长图与 PDF 均为离屏渲染，固定浅色纸面，不随应用主题变化。

## 测试

- `core:database`：分词、迁移（v1→v3）、标签仓库、备份快照
- `core:common`：崩溃日志、主题偏好
- `core:backup`：导出/恢复往返、非法备份拒绝
- `feature:diarylist`：日历格子生成
- `feature:export`：导出文件命名与失败清理

Robolectric 单测使用真实 SQLite 文件验证迁移，避免只比对 schema。

## 安装到自己的手机

当前定位是自用，直接用调试包即可，无需配置签名与上架素材：

```bash
gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

也可以把 `app-debug.apk` 传到手机，允许「未知来源安装」后直接安装。

注意：debug 包使用本机 `~/.android/debug.keystore` 签名，在另一台电脑上构建的包无法覆盖安装（需先卸载，数据会丢失）。如需跨设备稳定升级，再按下面配置自己的签名。

## 可选：仅在计划上架时执行

以下三项不影响自用，做发布时才需要：

1. 在 `local.properties` 配置签名（`RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`），未配置时 release 包回退 debug 签名。
2. 商店素材（截图、描述、隐私政策链接）与内测分发。
3. 上架前真机走查：编辑、导出、备份恢复、三套主题对比度。

## 许可证

本项目基于 [MIT License](LICENSE) 开源，可自由使用、修改与分发，请保留版权声明。

自用安装包可从仓库的 Releases 页面下载（`app-debug.apk`），或按上文命令本地构建。
