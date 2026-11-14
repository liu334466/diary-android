# 日记 Android App

> 仓库：<https://github.com/liu334466/diary-android> ｜ 下载：[v1.0.0 安装包](https://github.com/liu334466/diary-android/raw/main/dist/diary-android-v1.0.0-debug.apk)

极简日记应用：标题 + 富文本正文、分类、标签、全文搜索、日历回顾、本地备份与多主题。
单 Activity + Navigation，Kotlin + XML + ViewBinding，Hilt + Room + DataStore，Material 3。

**完全离线**：不申请任何权限（包括网络权限），没有账号与统计上报，日记只存在本机。

## 功能

**写作**

- 标题（≤50 字）与富文本正文（≤20000 字），输入即自动保存
- 加粗 / 斜体 / 下划线 / 文字颜色，段落对齐与列表
- 插入插图（单篇 ≤20 张），按栏宽等比缩放
- 字体与字号：内置字体列表、导入 TTF/OTF（单文件 ≤30 MB，最多 10 个），字号 10 档，可对整篇或选区设置
- 阅读页独立调整本篇字号，不影响正文内容

**组织**

- 分类：默认「未分类」不可删除，自定义分类 ≤20 个
- 标签：独立标签库，单篇最多 10 个、标签名 ≤10 字、标签库 ≤50 个；设置页可重命名或删除标签
- 回收站：删除进入回收站保留 30 天，支持单篇恢复（回到原分类）与彻底删除

**查找**

- 全文搜索：标题与正文，中文按单字 + 相邻双字建索引，结果标题/摘要高亮
- 标签筛选可与关键词组合（同时满足）

**回顾**

- 日历视图：按月网格标记有日记的日期，点选查看当天日记，可翻月

**导出与分享**

- PNG 长图（宽 1080px）与 PDF（A4 分页），带进度与取消
- 通过系统分享面板发送，导出图固定浅色纸面

**数据与外观**

- 本地备份与恢复：单个 ZIP（清单 + 插图 + 导入字体），恢复为覆盖式
- 三套主题：浅色、深色（`#121212`）、暖纸（`#F6F1E7`），支持跟随系统
- 应用内深链接：`diary://editor/{id}`、`diary://reader/{id}`、`diary://search`、`diary://calendar`、`diary://tags`、`diary://trash`、`diary://fonts`、`diary://export/{id}`

## 隐私与数据

- 无网络权限、无第三方 SDK、无埋点，应用不会向外发送任何内容
- 数据全部在本机私有目录：Room 数据库（`diary`、`category`、`tag`、`font_asset`、FTS 索引）、插图与字体文件
- `android:allowBackup="false"`，卸载即清除；需要迁移或换机时请先导出备份 ZIP
- 导出与分享通过 `FileProvider` 临时授权，仅在你主动操作时发生
- 备份 ZIP 由你自己保存，应用不会上传到任何地方

## 已知限制

- 备份不包含全局设置（外观模式、默认字体与字号），恢复后需重新设置
- 全文搜索不索引标签名，标签通过筛选条件使用
- 日历只有月视图，按**创建时间**归日，回收站中的日记不显示
- 暖纸与深色互斥，暂不支持「暖纸 + 深色」
- 导出图与 PDF 固定浅色纸面，不随应用主题变化
- 应用图标为自绘矢量图，未做商店素材

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

## 技术栈

| 类别 | 选型 |
| --- | --- |
| 语言 / 构建 | Kotlin 2.0.21、AGP 8.7.3、Gradle 8.9、JDK 17 |
| UI | XML + ViewBinding、Material 3、Navigation 2.8.5、RecyclerView |
| 架构 | 单 Activity + Fragment、MVVM、Hilt 2.52 |
| 存储 | Room 2.6.1（含 FTS4）、DataStore 1.1.1、私有文件目录 |
| 质量 | JUnit4 + Robolectric 4.14.1、ktlint 12.1.1 |

## 模块划分

依赖方向固定为 `feature → core`，feature 之间不允许横向依赖。完整说明见 [MODULES.md](MODULES.md)。

```text
app/        单 Activity 宿主、导航图、Application 启动任务
core/
  model         领域模型与产品约束常量
  common        通用工具：格式化、崩溃日志、图片存储、主题偏好
  database      Room 数据库、DAO、迁移、全文索引、仓库层
  designsystem  语义色属性、配色主题、公共弹层与图标
  font          字体导入、校验、缓存与可用性检查
  fontui        字体与字号选择弹层
  richtext      富文本 JSON 编解码与 Spannable 渲染
  backup        备份打包、校验与恢复
feature/
  diarylist     首页、搜索、日历、回收站
  editor        编辑页、格式工具条、标签选择
  reader        阅读页、字号调整、导出入口
  category      分类管理
  settings      设置、标签管理、备份恢复
  fontsetting   字体管理
  export        PNG / PDF 导出与分享
```

## 关键实现约定

- **富文本**：`core:richtext` 负责 JSON 序列化与 Spannable 双向转换，编辑页与阅读页共用同一套渲染；正文中的「黑色」在深色主题下会映射为当前主题正文色。
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

直接下载：[diary-android-v1.0.0-debug.apk](dist/diary-android-v1.0.0-debug.apk)（debug 签名，16.7 MB），传到手机后允许「未知来源安装」即可。

当前定位是自用，用调试包就够了，无需配置签名与上架素材；也可以自己构建：

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
