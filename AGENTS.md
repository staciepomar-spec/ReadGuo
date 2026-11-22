# AGENTS.md — ReadGuo 目录约定

> 本文件是 AI 与协作者的工作约定。**任何 AI 在读写本文件夹前必须遵守以下规则**，保持根目录整洁。

## 一、项目本体（白名单，禁止移动/删除）

根目录只允许存在以下条目，除此之外的文件/目录一律视为临时产物：

| 路径 | 职责 |
|---|---|
| `README.md` | 项目说明（功能、构建、目录结构） |
| `HANDOVER.md` | **交接手册**（接手必读：环境、代码地图、产品线、雷区、待办） |
| `AGENTS.md` | 本约定文件 |
| `.gitignore` | git 忽略规则 |
| `module/` | Xposed 模块源码与构建脚本（`src/` Java 源码、`res/`、`assets/`、`meta/`、`build.py`） |
| `out/` | 构建产物 APK 与发布说明（**勿清理**历史版本 APK） |
| `_tools/` | **构建依赖工具链**（aapt2、d8、android.jar、libxposed classes.jar、jadx 等）。`build.py` 硬依赖此目录，**严禁删除或移动** |
| `.workbuddy/` | AI 工作记忆笔记（按日期的 md 文件） |
| `.git/`、`.vscode/`、`.zcode/` | git 仓库与编辑器/会话数据（勿动） |
| `_tmp/` | 临时产物统一区（见下） |

## 二、临时文件规则（强制）

调试 / 逆向过程中产生的临时产物**禁止散落根目录**，一律按类型放入 `_tmp/`：

| 类型 | 目标目录 |
|---|---|
| 截图 `*.png` | `_tmp/img/` |
| 日志 / 界面树 dump `*.txt` | `_tmp/log/` |
| 配置快照 `*.xml` | `_tmp/xml/` |
| 逆向分析批次目录（jadx 输出、dex 解包等，如 `_rv*`、`_j*`） | `_tmp/rev/` |

文件名保留 `_<用途><序号>` 风格（如 `_ad1.png`、`_c0.png`），便于追溯。

## 三、清理规则

`_tmp/` 内**修改时间超过 2 天**的文件 / 目录视为过期，可直接永久删除，无需确认。项目本体（白名单条目）不适用此规则。

## 四、构建

```
python module/build.py      # 输出 out/RGTools-<版本>.apk
```

- 版本号在 `module/build.py` 的 `VERSION_CODE` / `VERSION_NAME` 维护
- 构建依赖 `_tools/`（勿删）与用户目录下的签名密钥 `.workbuddy/keys/rgmod.jks`

## 五、git 规则

- `_tmp/`、`out/`、`_tools/`、`.workbuddy/`、`.zcode/` 已 gitignore，**临时产物不要 add**
- 新功能源码只放 `module/src/com/wodi/redguotools/`
- 提交前用 `git status` 核对：不应出现 `_*` 开头的临时文件
