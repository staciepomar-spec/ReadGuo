# HANDOVER.md — 接手须知

> 给下一个接手的 AI/人。**先读本文件，再动代码。**
> 功能清单、历史版本、每项的 UI 行为 → 看 `README.md`（75 KB，很全）。本文件只讲「怎么接手、哪里有雷」。

---

## 0. 三十秒版本

| 项 | 事实 |
|---|---|
| 项目 | 红果免费短剧 / 番茄小说 / 番茄畅听的 **LSPosed 界面精简模块** |
| 形态 | 纯 Java，约 30 个类，**无 Gradle**，界面全部代码构建（无 layout XML） |
| 构建 | `python module/build.py` → `out/RGTools-<版本>.apk` |
| 改动风险最高 | `UiController.java` / `SettingsPanel.java` / `Config.java` / `PickerOverlay.java` |
| 三条最危险的事实 | ①`HookBuilder.setId` 一出现会让**所有** hook 静默失效 ②点选隐藏选中大范围容器会让宿主黑屏甚至进不去，只能靠模块 App「功能 → 逐应用重置」自救 ③红果 7.4 视频容器锚点 `id=i62` 消失，透明度整层失效 |
| 当前状态 | **3.25 / 116，单一源码线**；红果、番茄小说已实机验证，畅听部分功能待回归 |

---

## 1. 交接物清单

| 路径 | 内容 | 入库 |
|---|---|---|
| `module/src/com/wodi/redguotools/` | 全部源码（唯一改代码的地方） | ✅ |
| `module/build.py` | 构建脚本（`VERSION_CODE`/`VERSION_NAME` 在第 37-38 行） | ✅ |
| `module/meta/META-INF/xposed/scope.list` | 4 个宿主包名（红果 / 红果GP / 番茄小说 / 番茄畅听） | ✅ |
| `module/meta/META-INF/xposed/module.prop` | `minApiVersion=100 targetApiVersion=101 staticScope=true` | ✅ |
| `module/res` `module/assets` | 只有一个启动图标，无布局 | ✅ |
| `_tools/` | aapt2 / d8 / zipalign / apksigner / jadx / android.jar / libxposed classes.jar | ❌ gitignore，**删了构建就废** |
| `out/` | 历史 APK（2.75→3.25），**勿清理**；`ReadGuo-3.20-番茄适配.apk` 是已废弃的混合包 | ❌ |
| `.workbuddy/memory/` | 逐日工作日志（**最有价值的隐性知识**，11-20/11-21 两篇必读） | ❌ |
| 签名密钥 | `C:\Users\Iss.WR\.workbuddy\keys\rgmod.jks`，pass `android`，alias `rgmod` | ❌ 仓库外 |
| GitHub | `https://github.com/staciepomar-spec/ReadGuo`（`origin`） | — |
| Gitee | `https://gitee.com/allray/ReadGuo`（镜像，已公开，10 个 release 已同步） | — |

**不在仓库里、必须问用户要的**：GitHub token（发版用，见 §11）、宿主 APK 样本、番茄/畅听安装包。

---

## 2. 环境（实测快照 2026-11-22）

| 项 | 值 |
|---|---|
| 设备 | 唯一在线：序列号 `JVPJJVV8J7HYZTS8`，`ro.product.model=22041216C`，Android 14 / SDK 34，KernelSU + LSPosed |
| 宿主 | `com.phoenix.read` **7.3.9.32**；`com.dragon.read` **7.4.5.32**；`com.xs.fm` **6.7.8.32** |
| 设备上模块 | `com.wodi.redguotools` **versionCode 116 / 3.25**（源码线，可直接覆盖安装） |
| 验证状态 | 红果：面板 / 折叠 / 点选 / 撤销已回归；番茄小说：入口、面板、点选已验；畅听：适配完成，部分交互待用户自测 |
| 第 2 台真机 | Xiaomi 15 / Android 17，本次未连（adb 里没有） |
| JDK | `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot` |
| build-tools | `_tools/sdk_tmp_bt/android-16`；platform jar `_tools/sdk_tmp_pf/android-35/android.jar`；libxposed `_tools/aar_x/classes.jar` |
| adb | `D:/platform-tools/adb.exe`，**不要加 `-P`**（本机投屏软件占着 5037，加 -P 会永远拿不到设备） |

⚠️ README「已验证环境」表现已同时列出 7.3.9.32 与 7.4.x。改代码前先确认设备当前宿主版本 —— 两版的视图锚点不同（7.4 起 `id=i62` 消失、播放设置弹层变 Compose）。

---

## 3. 构建与安装

```bash
cd /d/CEBIANLAN/File/RedGuo
python module/build.py            # 约 40s，产出 out/RGTools-<VERSION_NAME>.apk
```

构建链：`aapt2 link` → `javac`(source 8) → `d8`(走 argfile，勿改回命令行传参) → zip 塞 dex/assets/meta → `zipalign -p 4` → `apksigner`(v1+v2+v3)。签名密钥不存在时脚本会自动 `keytool` 生成。

**装到设备**（直装常被拦，走 su）：

```bash
export PATH="/usr/bin:/bin:$PATH"
A="D:/platform-tools/adb.exe"
MSYS_NO_PATHCONV=1 "$A" push out/RGTools-3.25.apk /data/local/tmp/rg.apk
MSYS_NO_PATHCONV=1 "$A" shell "su -c 'pm install -r -t /data/local/tmp/rg.apk'"
MSYS_NO_PATHCONV=1 "$A" shell "monkey -p com.phoenix.read -c android.intent.category.LAUNCHER 1"
```

三个必踩的坑：

1. **版本号太低装不上**：设备上是 116，新包 `VERSION_CODE` 必须 ≥117 才能覆盖安装，否则 `INSTALL_FAILED_VERSION_DOWNGRADE`。降级只能先 `pm uninstall`（会丢 prefs，等于清配置）。发版前先确认设备当前的 versionCode。
2. **设备路径必须 `MSYS_NO_PATHCONV=1`**：否则 git bash 把 `/sdcard/...` 改写成 Windows 路径，命令**静默无效**（`rm -f` 不报错，最容易误判「已经删了」）。
3. **APK 字节数常不变**（对齐填充会吸收），别用文件大小判断是否重新打包 —— 验版看 dex 里的版本串。

---

## 4. 代码地图

### 入口与分流

`RGModule.onPackageReady()` 按包名三路分流，**互不共用**：

| 宿主 | 走的类 | 装了什么 |
|---|---|---|
| `com.phoenix.read` / `...oversea.gp`（红果） | `hookActivity` + `UiController` 等全套 | 状态栏 / 透明度 / 槽位 / 清单 / 播放器 / 去广告 / 暗色 / DPI / 定时 / 手势 / 面板 |
| `com.dragon.read`（番茄小说） | `Fanqie` + `FanqiePanel` + `FanqiePopup` + `ScanHide` | 底栏精简（返回 null 让宿主不创建）、启动浮层拦截、组件扫描隐藏 |
| `com.xs.fm`（番茄畅听 6.7.8） | `Ct` + `FanqiePanel` + `ScanHide` | 老架构 `AbsBottomTab`+`RadioGroup`，逻辑另写 |

### 文件职责

| 文件 | 行 | 职责 | 改它之前必须知道 |
|---|---|---|---|
| `UiController.java` | 3678 | 红果核心引擎：1.2 s 循环、槽位隐藏、透明度、手势、长按面板入口、扫描、日志/树导出 | 同组件多实例（ViewPager 预加载），判当前页只能用 `y∈[0,屏高)`；隐藏一律 INVISIBLE 保占位 |
| `SettingsPanel.java` | 1631 | 红果内设置面板（Dialog 卡片，92%×82%） | 返回键必须覆写 `Dialog.onBackPressed()`；只在一级页/`slots` 页显示固定操作行 |
| `Config.java` | 811 | 全部配置读写，`rg_tools_cfg` SharedPreferences | 宿主进程与模块 App 共用；改默认值**不覆盖**设备已存值 |
| `Theme.java` / `MiUi.java` | 530/720 | 视觉层两套：宿主内面板 / 模块 App | 签名不同（`Theme.round()` ≠ `MiUi.round()`），别混用 |
| `Fanqie.java` / `Ct.java` | 458/668 | 番茄小说 / 畅听底栏精简 | 同一枚举值两边含义不同（番茄=书城，红果=剧场），策略不能共用 |
| `FanqiePanel.java` | 900+ | 番茄+畅听共用面板（番茄长按「书城」，畅听长按「首页」左侧或顶部中央） | 与红果面板无共享代码；内容必须套 `ScrollView`，窗口固定 92%×82% |
| `ScanHide.java` | 371 | 番茄/畅听的组件扫描+隐藏引擎（900 ms 循环） | 已藏的 View 尺寸归零，**必须让 `sHidden.containsKey` 的继续参与匹配**，否则一闪一闪 |
| `PickerOverlay.java` | 832 | 悬浮球点选（红果/番茄/畅听都用） | 三重防吞触摸 `sTouching`/`sRootDown`/`insidePanel` 不可删 |
| `PlayerTweaks.java` | 527 | 播放器画质/倍速/暂停 | 速度上限 **2.0x**，native 档位只有 {1.0,1.25,1.5,2.0} |
| `SleepTimer.java` | 245 | 定时关闭 → 熄屏 | 熄屏只能 root：`input keyevent 26`，先查 `PowerManager.isInteractive()` |
| `AdBlock.java` | 329 | 广告总闸 + 四道闸门 | 别碰 `AttributionManager.hasHitAttribution()` |
| `NoUpdate.java` | 361 | 拦改版升级弹窗 | 判据是**内容签名**（hook `Dialog.show()` 后延迟一帧判文案），不是类名 |
| `Danmaku.java` | 239 | 强制显示弹幕 | 锚点用未混淆类 `com.dragon.read/video/danmaku/i#h()` |
| `DarkMode.java` / `DpiTweak.java` | 169/88 | 换肤 / 密度 | DPI 必须在 `onCreate` proceed **之前**改，改完必须 `recreate()` |
| `Hosts.java` | 127 | 4 个包名 + 运行环境快照 | **纯数据类**：`RGModule` 继承 `XposedModule`，在模块 App 进程引用会 `NoClassDefFoundError` |
| `SheetEntry.java` | 319 | 往播放设置弹层插「ReadGuo 设置」行 | ⚠️ 红果 7.4 起该弹层被重写成 Compose，**注入方式已失效**；7.3.9.32 仍可用 |
| `MainActivity.java` | 1900+ | 模块 App（主页 / 功能 / 关于三页） | 无设置页（v3.08 起唯一入口是宿主内面板）；功能页提供逐应用「重置」；`Palette` 只能在 `onCreate` 取 |
| `CrashGuard.java` | 132 | 崩溃取证（宿主+模块 App 双装） | 写 `rgtools_crash.txt`，**不吞异常** |
| `ModuleState.java` | 91 | 「模块是否激活」判定 | 只读日志 mtime（30 min 内=RUNNING），无 root 一律 UNKNOWN，禁止写死「已激活」 |
| `Names.java`(92) / `RootShell.java`(164) | — | 规则→中文名 / su 封装 | — |

---

## 5. 配置与日志（调试全靠这两处）

| 用途 | 位置 |
|---|---|
| 配置 | `/data/data/<宿主>/shared_prefs/rg_tools_cfg.xml`（红果/番茄/畅听**各自独立**） |
| 运行日志 | `/sdcard/Android/data/<宿主>/files/rgtools_log.txt` |
| 视图树 | 同目录 `rgtools_tree.txt`，长按底部「首页」→ 面板「输出日志」才会导出**完整**树（自动写的那份常只有启动首屏 137 节点） |
| 崩溃 | 同目录 `rgtools_crash.txt`；系统那份 `su -c 'cat /data/system/dropbox/data_app_crash@<ts>.txt'` |
| 用户可见导出 | `/sdcard/Documents/rgtools_log.txt`、`rgtools_tree.txt`（走 MediaStore，targetSdk 35 才落得下去） |

免 UI 改配置（调试提速）：

```bash
MSYS_NO_PATHCONV=1 adb shell "su -c 'cp /sdcard/xxx.xml /data/data/com.phoenix.read/shared_prefs/rg_tools_cfg.xml'"
# 然后强停重开 App；注意 shared_prefs 需同 uid 属主，必要时 chown
```

---

## 6. 产品线：已收敛为单一源码线

3.21 起只有一条线：`python module/build.py` → `out/RGTools-<版本>.apk`，可复现构建。

- 曾经的「补丁线」（第三方 APK + apktool 二次修改，versionCode 111）与「混合包」（`ReadGuo-3.20-番茄适配.apk`）**均已废弃**，不要再基于它们改。
- 两级折叠、编辑模式、撤销、逐应用重置等原补丁线功能，已全部用源码重写进 `SettingsPanel` / `PickerOverlay` / `EditGuard`。
- 发版只发源码构建产物；`out/` 里的旧混合包仅作历史留档。

---

## 7. 铁律（违反过，都出过线上问题）

1. **`HookBuilder.setId` 是 `@SinceApi(API_102)`**，模块 `minApiVersion=100` → 任何一处 setId 会让**该文件的 hook 全部静默失效**。现有代码里一个都不能有。
2. 隐藏一律 `INVISIBLE` 保占位（自定义清单除外，用 `GONE`）；判「真显示」用 `isShown()`，**不能看 tag**（切集复用实例、只改回 VISIBLE，tag 还在）。有效性只看实际 visibility。
3. 规则 `cls:` 后缀匹配要求长度 ≥4；`id:` **必须先全树统计出现次数**，多份就降级 `@文案`（四个顶栏标签共用 `id:cx`，用 id 会一藏一排）。点选走 `UiController.ruleForPicked()`，计数必须走全树（点中的叶子不在 `scanBlocks` 的 blocks 里）。
4. 先 `applyDesired` 再 `applyLayerAlpha`；已在层内不乘全局（`underAnyLayer`）；暂停还原要同时 `restoreLayerAlpha()`。透明度写入是**乘性**的 `WeakMap<View,float[]>`。
5. 暂停期间**每轮**都要还原 slot alpha + `showHiddenForPause()` 重显；清屏模式不强制显示。清屏 `cs` 瞬时 false 用 `IDLE_CS_LOCK` 兜，onTouch 里要 remove 锁。
6. 构建时源码里的中文特殊串要写成 `"\uXXXX"`；**Edit 工具会把转义还原成真中文** → 这类改动必须用 Python 写盘。同理，`NODE_TLS_REJECT_UNAUTHORIZED` 等环境变量别乱改。
7. 宿主包名常量放 `Hosts`，**不能放 `RGModule`**（App 进程会 `NoClassDefFoundError`）。
8. 面板配色跟随宿主：`Theme.of()` → `DarkMode.hostIsNight()` → `SkinManager.isNightMode()`（宿主深色是**换肤**，不是 uiMode）。
9. 长按面板是**唯一**配置入口（模块 App 已无设置页）→ 入口宁可多放开，不可误封。判定三级：底栏第一格 → 按文案「首页」上溯可点祖先 → 底部左侧 1/5（该分支要求同屏有「剧场/我的」，否则二级页会误触进度条）。
10. **不要过度交付**：只做用户明确要求的那一件事。用户会实时改面板配置，下结论前重拉配置。

---

## 8. 已知问题 / 待办（按优先级）

| # | 问题 | 影响 | 线索 / 建议 |
|---|---|---|---|
| P1 | ~~版本线分裂~~ **已解决** | 3.21 起单一源码线，设备已覆盖安装 112→116 | 无需处理；发版只发源码构建产物 |
| P2 | **畅听仍有部分交互未回归** | `Ct` 的底栏 / 我的页已验；面板滚动、已隐藏清单、撤销待在 6.7.8 上确认 | 重点验：面板能否上下滑动、逐条恢复是否即时生效、「长按首页左侧 / 顶部中央」是否稳定 |
| P3 | **视频容器锚点 `id=i62` 在红果 7.4 已消失** | 7.4 上「界面透明度」整层失效，只剩槽位/清单生效 | 换锚点：从播放器 View 上溯找页面根，**别再用混淆 id**。7.3.9.32 上 i62 仍有效，所以当前没炸 |
| P4 | **播放页长按注入失效（7.4）** | 7.4 把播放设置弹层改成 Compose，`SheetEntry` 插的行不出现 | 面板还有底部「首页」长按入口保底，不致命。若要在 7.4 恢复，需另找 Compose 侧入口 |
| P5 | 旧计数规则不会自愈（`@10.8万` 之类） | 用户历史清单里这类规则永远匹配不到 | 面板「组件显隐 → 清空」后重扫一次即可，无法自动迁移 |
| P6 | 状态栏隐藏是周期压制（跟 1.2 s 循环） | 开关关掉时若逻辑写错会主动 show() | 已修：关闭时只恢复 `SB_HIDDEN_MARK` 记过的，绝不主动 show |
| P7 | 熄屏依赖 root + 守护进程 | 非 root 设备定时关屏只能停播 | 宿主 su 失败时写 `files/rg_guard/off.<uptime>.req`，由 `/data/adb/service.d/` 守护发键；KernelSU 只对白名单 uid 暴露 su |

---

## 9. 调试方法论（硬性）

**先拿运行时证据，再做静态分析。** 这是本项目最省时间的一条。

1. 视图树：长按底部「首页」→ 面板「输出日志（视图树+运行日志）」→ 拉 `/sdcard/Documents/rgtools_tree.txt`。**`uiautomator dump` 不可信**：播放中必失败、不含 0 尺寸节点、类名一律报 `android.widget.TextView`（自定义 TextView 子类不反映）。
2. 判类名只能靠模块自己的 `logTree`。
3. 截图：`adb exec-out screencap -p > x.png`（`shell screencap` 会因路径改写失败）；点击坐标换算 `y_full = y_thumb/980*2460`。
4. 崩溃：`rgtools_crash.txt` > dropbox > 猜。
5. 反编译：`_tools/jadx/bin/jadx.bat -d out --no-res --no-imports`（单 dex）；查字符串 `_tools/dmkw.py <kw>`；解 class_defs `_tools/dexdef.py`。
6. 卡住超过 2~3 次 → **停手先搜**（LSPosed / Frida / GitHub issue / 踩坑记录），关键词用技术特征而不是复述问题。

---

## 10. 最贵的 10 个坑（都真实踩过）

1. `setId` 让整个文件的 hook 静默失效，且没有任何日志 —— 排查了两轮。
2. `uiautomator dump` 给出的类名全是 `TextView`，据此写的规则全错。
3. `adb shell` 里的 `/sdcard/...` 被 git bash 改写 → `rm` 不生效，以为配置已清空，实际没清。
4. 隐藏「只当时有效，离开页面就回来」——根因是规则退化成了计数文案 `@10.8万`。
5. 暂停还原把清单一起撤了（`TAG_WEHID` 分不清槽位和清单）→ 新增 `TAG_LIST`。
6. 点选「关注」生成 `id:cx`，一次藏掉整排四个标签。
7. 在 `Activity` 构造器里取 `Palette` → 早于 `attachBaseContext`，直接崩。
8. `targetSdk 35` 强制 edge-to-edge，`setStatusBarColor` 失效 → 只能 `OnApplyWindowInsetsListener` 补内边距。
9. d8 用命令行传 class 列表 → Windows 8191 字符上限，必须走 `@argfile`。
10. apktool 打包 `resources.arsc` 用 deflate → targetSdk 35 装不上，必须 STORED + 4 字节对齐。

---

## 11. 发版

主仓 GitHub `staciepomar-spec/ReadGuo`，镜像 Gitee `allray/ReadGuo`（公开）。本地 git 现在是干净的，改动正常 commit 后双端推送：

```bash
git push origin main
git push https://allray:<token>@gitee.com/allray/ReadGuo.git main:master
```

**Gitee 发版**（GitHub 协议被墙时的主力渠道）：

- REST API 建 Release：`POST /repos/allray/ReadGuo/releases`（`tag_name` / `name` / `body` / `target_commitish`）
- 上传 APK：`POST /repos/allray/ReadGuo/releases/{id}/attach_files`（multipart）
- token 从 `%APPDATA%\Trae CN\User\mcp.json` 的 `mcpServers.gitee.env.GITEE_ACCESS_TOKEN` 取，**不要写进仓库**
- Gitee「发行版列表」侧栏按版本号固定排序，接口改不了顺序；新版本创建后自然排在末尾

验包：`aapt2 dump badging` 核 versionCode/versionName + `apksigner verify` 三项全 true + 直链匿名下载核字节数。

---

## 12. 接手后的第一个动作建议

1. 读 `.workbuddy/memory/` 最近几篇（11-20 / 11-21 / 11-22），再读 `README.md` 的「更新日志」。
2. `python module/build.py` 跑一遍，确认工具链完好（不改版本号，能出包即合格）。
3. 连设备确认：宿主版本、模块版本、`rgtools_log.txt` 是否有新的 `install: ok`。
4. 改动前先看 §8 待办，尤其是畅听的待回归项。
