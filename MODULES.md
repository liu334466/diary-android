# 日记 App 模块说明

## 依赖规则

- `app` 只做组装：导航、依赖注入、启动。
- `feature:*` 只依赖 `core:*`，禁止 feature 之间横向依赖。
- `core:*` 之间按需依赖，不反向依赖 feature。
- 公共 UI Token 统一放在 `core:designsystem`，禁止各模块自定义颜色与间距常量。

## 模块清单

| 模块 | 命名空间 | 职责 |
|---|---|---|
| app | com.diary.app | 应用外壳、导航、Hilt 入口 |
| core:model | com.diary.core.model | 日记、分类、字体领域模型 |
| core:database | com.diary.core.database | Room 数据库、DAO、迁移 |
| core:common | com.diary.core.common | 通用工具与扩展 |
| core:designsystem | com.diary.core.designsystem | 颜色、间距、圆角 Token |
| core:font | com.diary.core.font | 字体导入、加载、缓存、回退 |
| core:richtext | com.diary.core.richtext | 富文本序列化与反序列化 |
| feature:diarylist | com.diary.feature.diarylist | 日记列表与分类筛选 |
| feature:editor | com.diary.feature.editor | 富文本编辑与自动保存 |
| feature:reader | com.diary.feature.reader | 阅读页与单篇字体调整 |
| feature:category | com.diary.feature.category | 分类夹管理 |
| feature:fontsetting | com.diary.feature.fontsetting | 字体与字号设置、字体管理 |
| feature:export | com.diary.feature.export | PNG / PDF 导出与分享 |
| feature:settings | com.diary.feature.settings | 设置页与回收站入口 |

## 技术基线

- Kotlin，minSdk 28，compileSdk 35，targetSdk 35
- XML + ViewBinding，不使用 Compose
- Hilt + Room + DataStore + Navigation + Coil
- 不申请网络权限，不接入统计与广告 SDK
