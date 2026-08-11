# arc 自制谱管理工具

Arcaea 自制谱的 **导入** 与 **曲包/歌曲管理** Android 应用。

## 功能

### 自制谱导入
- zip / rar 压缩包解压与文件自动识别
- songlist 宽容解析、难度槽位补全、合并写回
- 导入前预览确认、重复歌曲替换提示
- songlist 备份恢复、源文件编辑

### 曲包管理
- 曲包网格展示（包图 + 歌曲数）、跨曲包搜索
- 下拉刷新：重新检测曲包 / 歌曲并刷新缩略图
- 新建 / 删除 / 编辑曲包（名称、分区、描述、扩展包标记等）
- **内置封面编辑器**：选择图片后缩放 / 拖动定位，按固定蒙版裁切 + 白边，直接生成游戏封面（文件名按 packlist 规则只写单文件）
- 自动备份，任何改动可还原

### 歌曲管理
- 曲包内歌曲列表（封面 + 难度），支持搜索与时间排序
- **编辑模式拖拽**：长按拖动歌曲跨曲包移动、同包内排序
- 歌曲信息 / 难度编辑、重命名 id、删除
- 侧栏点击曲包快速切换

### 备份还原
- 版本化快照：每次写 songlist / packlist 前自动备份
- 可恢复任意历史快照，恢复前再自动备份（可撤销）

## 技术要点

- 通过 **SAF（Storage Access Framework）** 访问游戏目录，无需 root
- 性能优化：java.io.File 直连 + DocumentsContract 批量查询 + 内存缓存
- **免解锁曲包规则**（实测）：packlist 中 `section="mainstory2"` 且 `is_extend_pack=true` 的自建曲包不需要购买验证；`free + false` 会被游戏要求解锁

## 构建

需要 JDK 21、Android SDK 34、Gradle 8.9。

```bash
gradle assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

> 计时调试版：`gradle assembleDebug -Ptiming=true`（输出加载耗时日志，`adb logcat -s Timing`）

## 文件规则

Arcaea 自制谱文件格式说明见 [`docs/arcaea-file-rules.md`](docs/arcaea-file-rules.md)。

## 免责声明

本项目仅用于自制谱管理与学习交流，请勿用于侵犯版权或违反游戏规则的行为。
