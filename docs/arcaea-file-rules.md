# Arcaea 自制谱文件规则说明

> 本文档基于 Arcaea 资源目录（`Arc6` / `OpnArc_`）的实际文件结构整理，
> 用于指导自制谱的打包、识别、导入。构建类似 Arcaea 自制谱工具时可直接参考。

---

## 一、游戏资源根目录结构

游戏资源根目录（下称 `<game>`）的典型结构：

```
<game>/
├── audio/          # 全局音效/音频（非歌曲音乐）
├── img/
│   ├── bg/         # 背景图目录
│   │   └── 1080/   # 高清分辨率背景图（实际存放处）
│   └── ...         # 其他 UI 图片
├── songs/          # 歌曲目录（最重要）
│   ├── songlist    # 歌曲列表（JSON）
│   ├── packlist    # 曲包列表（JSON, {"packs":[...]}）
│   ├── pack/       # 曲包选择图片
│   ├── unlocks     # 解锁列表（通常空）
│   ├── <songid>/   # 每首歌一个文件夹
│   └── ...
├── char/           # 角色
├── startup/        # 启动相关
└── config.json     # 配置
```

> 注意：`songlist`、`packlist`、`unlocks` 都是**无扩展名**的文本文件。

---

## 二、songs 歌曲目录规则

- 每首歌曲一个文件夹，文件夹名 = 歌曲 `id`（songlist 中该歌曲条目的 `id` 字段）
- `songs/songlist` 是游戏读取的歌曲总列表
- 歌曲文件夹必须放在 `songs/` 下，与 songlist 中 `id` 一一对应

```
songs/
├── songlist
├── alea/           # 文件夹名 = id
├── echoes/
└── ...
```

---

## 三、歌曲文件夹（songs/<id>/）规则

每首歌曲文件夹内固定包含以下文件（**约定文件名，无需在 songlist 中引用**）：

```
songs/<id>/
├── base.ogg      # 歌曲音乐（固定名）
├── base.jpg      # 默认封面（固定名）
├── base_256.jpg  # 封面缩略图（固定名，256x256）
├── 2.aff         # 谱面文件（名称任意，通常为难度编号）
└── 3.aff         # 可有多个 .aff，按难度拆分
```

关键点：
- **音乐**：必须叫 `base.ogg`
- **封面**：必须叫 `base.jpg`
- **封面缩略图**：必须叫 `base_256.jpg`
- **谱面**：`*.aff`，文件名任意，但同一首歌可拆成多个（如 `2.aff`、`3.aff`）
- **自定义封面**：可能有 `3.jpg`、`3_256.jpg` 等（配合难度条目的 `jacketOverride` 使用），保持原名放在歌曲目录即可
- songlist 条目中**不需要** `audio` / `jacket` / `aff` 字段，靠上述约定文件名加载

---

## 四、背景图规则（img/bg）

- 背景图放在 `<game>/img/bg/1080/` 下（1080p 高清）
- 文件名任意，但 songlist 条目的 `bg` 字段引用其**文件名（不含扩展名）**
- 例如 `img/bg/1080/infb.jpg` → 条目 `"bg": "infb"`

```
img/bg/1080/
├── infb.jpg
├── maimai_light.jpg
├── vs_conflict.jpg
└── ...
```

标准背景图分辨率参考：**1920x1440**

---

## 五、songlist 文件格式

`<game>/songs/songlist` 是 JSON 文本，**顶层是对象**（不是数组）：

```json
{
    "songs": [
        {
            "id": "alea",
            "title_localized": { "en": "Alea jacta est!" },
            "artist": "BlackY fused with WAiKURO",
            "bpm": "162",
            "bpm_base": 162,
            "set": "base",
            "purchase": "",
            "audioPreview": 90740,
            "audioPreviewEnd": 104073,
            "side": 0,
            "bg": "maimai_light",
            "version": "",
            "date": 1613505257,
            "difficulties": [
                { "ratingClass": 0, "chartDesigner": "", "jacketDesigner": "", "rating": 0, "ratingPlus": false },
                { "ratingClass": 1, "chartDesigner": "", "jacketDesigner": "", "rating": 0, "ratingPlus": false },
                { "ratingClass": 2, "chartDesigner": "Arthas fused with Kai", "jacketDesigner": "", "rating": 10, "ratingPlus": true }
            ]
        }
    ]
}
```

---

## 六、歌曲条目字段详解

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 歌曲唯一标识；也是歌曲文件夹名，**必填且不能为空** |
| `title_localized` | object | 多语言标题，如 `{"en": "...", "ja": "..."}` |
| `artist` | string | 艺术家 |
| `bpm` | string | 如 `"175-230"`（可范围） |
| `bpm_base` | number | 基准 BPM |
| `set` | string | 所属曲包，默认 `"base"` |
| `purchase` | string | 付费标识，通常空字符串 |
| `audioPreview` | number | 试听起点（ms） |
| `audioPreviewEnd` | number | 试听终点（ms） |
| `side` | number | 0=光（light），1=对立（conflict） |
| `bg` | string | 背景图名（不含扩展名），引用 `img/bg/1080/<bg>.jpg` |
| `version` | string | 版本，如 `"3.0"` |
| `date` | number | Unix 时间戳 |
| `difficulties` | array | 难度列表（见下） |

### difficulties 难度条目

```json
{
    "ratingClass": 0,          // 难度槽位：0=PST 1=PRS 2=FTR 3=BYD
    "rating": 11,              // 定数（0~12+）
    "ratingPlus": true,        // 是否 +（如 11+）
    "chartDesigner": "xxx",    // 谱师
    "jacketDesigner": "xxx",   // 封面画师
    "jacketOverride": true     // 可选：使用自定义封面（如 3.jpg）
}
```

---

## 七、难度槽位规则（重要）

**一首歌必须补全 4 个难度槽位**（`ratingClass` 0/1/2/3），缺失会导致游戏显示 bug：

| ratingClass | 难度 | 说明 |
|---|---|---|
| 0 | PST | Past |
| 1 | PRS | Present |
| 2 | FTR | Future |
| 3 | BYD | Beyond |

缺失的槽位补入**空难度**：

```json
{ "ratingClass": 1, "rating": 0, "ratingPlus": false, "chartDesigner": "", "jacketDesigner": "" }
```

> 空难度 = `rating: 0`。即使只有 1 个难度，也要补全其他 3 个空槽位。

---

## 八、自制谱 songlist 片段

自制谱包里的 `songlist` 通常是**歌曲条目的片段**，不是完整文件。常见形态：

1. **裸对象**（从完整数组复制出来，可能带尾逗号）：
   ```json
   {
       "id": "echoes",
       "title_localized": { "en": "Echoes of Memoria" },
       ...
   },
   ```
2. **对象数组**：`[{...}, {...}]`
3. **带 songs 包装**：`{"songs": [{...}]}`

解析时要**宽容处理**：去掉 BOM、容忍尾逗号、兼容三种形态。

---

## 九、文件自动识别规则

从自制谱包解压后，按以下规则识别每个文件的用途：

| 识别条件 | 类型 |
|---|---|
| 文件名含 `songlist` 或 `slst`（任意后缀） | songlist 片段 |
| 扩展名 `.aff` | 谱面 |
| 扩展名 `.ogg/.mp3/.m4a/.wav/.flac` | 音乐 |
| 图片扩展名（jpg/png/webp/bmp）且 **base 名 = songlist 的 `bg` 字段** | 背景图 |
| 图片且 base 以 `_256`/`_thumb`/`_s` 结尾 | 封面缩略图 |
| 图片（已知 bg 时）| 封面（可多个） |
| 图片（无 bg 信息时）base 为 `base`/`jacket`/`cover` | 封面 |
| 图片（无 bg 信息时）其他 | 背景图 |
| 其他 | 未识别 |

**关键**：背景图根据 songlist 的 `bg` 字段识别（背景通常只有 1 个），
其余图片默认是封面（封面可以有多个，如 `base.jpg` + `3.jpg`）。

---

## 十、曲包（pack / set）规则

曲包由两个部分组成：**歌曲的 `set` 字段**（归属）和 **packlist 定义**（包的信息）。

### 10.1 歌曲归属曲包

每首歌的 `set` 字段 = 它所属曲包的 `id`：

```json
{ "id": "infb", "set": "base", ... }
```

示例中 set 取值分布：`vegchi`(39首)、`base`(10首)、`choukaguyahime`(6首)、`single`(1首)、`lowest`(1首)、`extra`(1首)。

### 10.2 packlist 文件（songs/packlist）

`packlist` 是 JSON，**顶层对象 `{"packs": [...]}`**，定义所有曲包：

```json
{
  "packs": [
    {
      "id": "vegchi",
      "section": "mainstory2",
      "is_extend_pack": true,
      "custom_banner": false,
      "cutout_pack_image": true,
      "plus_character": -1,
      "name_localized": { "en": "Vegchi's Collection" },
      "description_localized": { "en": "...", "ja": "..." }
    }
  ]
}
```

pack 对象字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 曲包唯一 id；歌曲 `set` 字段引用它 |
| `section` | string | 分区：`free` 免费 / `mainstory` 主线 / `mainstory2` 副线 / `archive` 归档 等 |
| `is_extend_pack` | boolean | 是否扩展包 |
| `custom_banner` | boolean | 是否自定义横幅 |
| `cutout_pack_image` | boolean | 是否使用抠图 |
| `plus_character` | number | 附带角色 id（-1 表示无） |
| `name_localized` | object | 包名（多语言） |
| `description_localized` | object | 包描述（多语言） |

> 注意：同一个 `id` 可能出现在多个 `section` 下（如 `base` 在 free 和 mainstory 各有一个条目），按 `section` 区分。

### 10.3 曲包图片（songs/pack/）

`songs/pack/` 目录存放曲包选择图片，命名规则：

```
songs/pack/
├── 1080_select_<packid>.png    # 1080p 高清选择图
├── select_<packid>.png         # 普通选择图
└── ...
```

示例：
- `1080_select_base.png`、`select_vegchi.png`、`select_choukaguyahime.png` 等

### 10.4 unlocks 文件（songs/unlocks）

解锁列表，格式：

```json
{ "unlocks": [] }
```

通常为空数组（自制谱默认全解锁）。

---

## 十一、导入整合规则

将自制谱导入游戏时：

1. **解压** 自制谱包到临时目录
2. **识别** 文件用途（见第九节），允许手动修正
3. **解析** songlist 片段 → 歌曲条目
4. **校验**：`id` 不能为空；解析失败要提示"异常"并允许编辑源文件
5. **补全难度**：每条目补全 4 个难度槽位（见第七节）
6. **复制文件**：
   - 音乐 → `songs/<id>/base.ogg`
   - 默认封面 → `songs/<id>/base.jpg`
   - 封面缩略图 → `songs/<id>/base_256.jpg`
   - 其他封面 → `songs/<id>/` 保持原名
   - 谱面 `.aff` → `songs/<id>/` 保持原名
   - 背景图 → `img/bg/1080/<原名>`，并保证条目 `bg` 字段 = 文件名（去扩展名）
7. **整合 songlist**：
   - 读取游戏 `songs/songlist`（`{"songs":[...]}`）
   - 若条目 `id` 已存在 → 替换旧条目（需用户确认）
   - 追加/替换后写回
   - **写回前备份**原 songlist 为 `songs/songlist.backup`

---

## 十二、已知坑与注意事项

1. **难度槽位**必须 4 个，否则游戏显示 bug
2. **`id` 不能为空**，空 id 条目会导致异常
3. **背景图分辨率**游戏有要求，标准约 1920x1440
4. **文件名约定**：`base.ogg` / `base.jpg` / `base_256.jpg` 不能改
5. songlist 是 `{"songs":[...]}` 包装，不是顶层数组（虽然 Arcaea 某些版本是顶层数组，兼容解析）
6. 自制谱 songlist 片段可能带尾逗号、BOM，需宽容解析
7. `audioPreview` / `audioPreviewEnd` 用毫秒；`date` 用 Unix 秒
8. 曲目 `bg` 引用的背景图若游戏目录中不存在，游戏背景可能异常——导入前应检查并允许用户修改 `bg` 字段
9. `base_256.jpg` 是封面缩略图（256x256），与 `base.jpg` 是同一封面的不同尺寸

---

*文档版本 v1.1 · 基于 Arc6/OpnArc_ 示例资源整理（补充曲包 pack 规则）*
