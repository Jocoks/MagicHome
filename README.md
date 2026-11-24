# MagicHome · 让荣耀的上滑回桌面进入第三方桌面

> **使用声明**
>
> 本项目仅供个人在自己设备上备份、修改自己拥有的主题包，以及学习文件格式。
> 禁止在国内社交平台宣传、推荐、引流本项目，包括但不限于：B 站、抖音、快手、贴吧、酷安、小红书、微博、知乎、微信公众号/视频号。

荣耀 MagicOS 不允许把第三方桌面设为默认桌面，「上滑回桌面」在系统内部是**把荣耀桌面任务置前**，
不是发 HOME intent，所以改默认桌面、改 intent 都没用。

本模块在荣耀桌面（自研 Quickstep）进程里做重定向：手势终点确实是 `HOME` 时才把你带到指定的桌面
（默认 Nova）。已在荣耀 MagicOS 11 / Android 17 实机测试。

## 功能

- **上滑进去你指定的桌面**：App 内上滑回桌面、桌面底部边缘快速上滑，都进目标桌面；进最近任务、切应用不误跳。
- **离开应用/最近任务同上**：最近任务点空白、再上滑，或从 App 按返回键退出，都进目标桌面（设置页可关）。
- **最近任务增强**：底部显示运存占用；长按卡片进「应用信息」页。
- **第三方桌面可自定义**：设置页列出已装桌面，点选或输入包名，几秒生效。
- **冷启动预热**：回桌面前静默拉起目标桌面进程。

---

## 1. 原理

| 场景 | 钩子 | 做法 |
|---|---|---|
| 上滑回桌面 | `GestureState#setEndTarget` | 终点 `HOME` 才写 1.2s 令牌，`onTopResumedActivityChanged(true)` 带令牌才跳；`RECENTS`/`LAST_TASK` 清令牌 |
| 桌面底部边缘上滑 | `TouchInterationManager#processGestureInputEvent` | 停在桌面时，底部 240px 内快速上滑（≥220px、≤400ms）直接跳目标桌面 |
| 最近任务退出 / 按返回退出 | `StateManager#goToState` + `onTopResumedActivityChanged` | ① `Overview → Normal`（点空白 / 再上滑）② 桌面从后台回前台（应用根页面按返回）。都由 `BouncePolicy` 判定，50ms / 40ms 起跳，没退出最近任务就补试 3 次 |
| 长按最近任务卡片 | `TaskView#dispatchTouchEvent` | 原地按住 460ms（不移动、不落在分屏/锁按钮上）→ 振动反馈 → 打开「应用信息」页；点按/滑动不受影响 |
| 上滑偏高 | 同手势管线 | 结算时若当前在最近任务、或 900ms 内刚进过（`wasOverviewRecently()`），该手势直接忽略 |
| 最近任务运存 | 桌面 DecorView | `addView` 文字（不需悬浮窗权限），每秒读 `ActivityManager.MemoryInfo`，离开自动移除 |
| 预热 | 目标桌面导出的 ContentProvider | 开机首次回桌面 +6s、上滑起手时静默拉起目标桌面进程，吃掉冷启动等待 |

### 踩过的坑

1. **不能用 `CATEGORY_HOME + NEW_TASK` 拉目标桌面**：系统会把它当多余 home 任务塞进 home 根任务，
   表现为「最近任务卡死，只能上滑退出」。现在用普通任务启动（`NEW_TASK|CLEAR_TOP|EXCLUDE_FROM_RECENTS`）。
2. **不能凭 `onResume` 就跳**：上滑回桌面和上滑进最近任务都会让桌面 resume，无脑跳会把最近任务顶掉。
3. **不能凭 `onPause` 判断离开前台**：Quickstep 会手动调 `onPause`（系统事件里并没有真 pause）。
4. **跳转要等转场动画结束**：转场中途拉起目标桌面，系统约 0.4s 后会把荣耀桌面重新置前（闪一下退回）。
5. **resume 时状态可能已经是 `NormalState`**：这时 `goToState` 不再回调，必须在 resume 里主动读状态。
6. **只支持 libxposed 新 API**：`io.github.libxposed:api:102.0.0`，模块格式 `META-INF/xposed/{module.prop,scope.list,java_init.list}`。

## 2. 目录

```
magichome/
├── AndroidManifest.xml
├── build.sh                     # 设备内构建
├── src/io/github/jocoks/magichome/
│   ├── HookEntry.java           # 只装钩子，把回调转给 ui
│   ├── feature/                 # 功能层：纯判定，不碰 View
│   │   ├─Constants  LauncherState  GestureRules  BouncePolicy
│   │   └─TargetLauncher  SettingsContract  MemoryStats  Logs
│   └── ui/                      # 交互层：Activity / View / 触摸 / 状态机
│       ├─LauncherBridge  SwipeTracker  RecentsTouchHandler
│       └─OverviewOverlay  SettingsActivity  SettingsProvider
├── res/                         # 自适应图标（独角兽剪影）
├── meta/META-INF/xposed/
└── tools/                       # pack.py（打 APK）、make_icon.py（生成图标）等
```

## 3. 构建

容器内（无需 Android SDK）：

```sh
aapt2 compile --dir res -o build/res.zip
javac -source 8 -target 8 -classpath tools/android.jar:tools/libxposed-api.jar -d build/classes $(find src -name '*.java')
java -cp d8.jar com.android.tools.r8.D8 --lib tools/android.jar --min-api 29 --output build/dex $(find build/classes -name '*.class')
aapt2 link -o build/base.apk -I tools/android.jar --manifest AndroidManifest.xml build/res.zip
python3 tools/pack.py build/base.apk build/unsigned.apk build/dex/classes.dex meta
zipalign -f -p 4 build/unsigned.apk build/aligned.apk
apksigner sign --ks tools/debug.keystore --ks-pass pass:android --ks-key-alias androiddebugkey \
  --min-sdk-version 29 --out build/MagicHome.apk build/aligned.apk
```

设备内也可以 `sh build.sh`。依赖 `tools/android.jar`、`tools/libxposed-api.jar`（102.0.0 的 classes.jar）。

## 4. 安装与启用

1. 安装 APK。
2. LSPosed 管理器 → 模块 → 启用 **MagicHome**，作用域勾选 `com.hihonor.android.launcher`（可选 `com.hihonor.hnofficelauncher`）。
3. `am force-stop com.hihonor.android.launcher` 让新配置生效（不用重启手机）。
4. 打开设置页：选目标桌面（会列出已安装的桌面）、开关「最近任务退出」、看响应速度提示。

## 5. 验证

```sh
grep -E "state ->|bounced ->|home swipe|recents exit|topResumed" /data/adb/lspd/log/modules_*.log | tail
```

关键行：`state -> HnOverviewState`、`recents exit ... bounce to target`、`bounced -> <目标桌面>`、
`home swipe ignored: already in overview`（上滑偏高被忽略）。

## 6. 已知限制

- 跳转发生在荣耀桌面已经显示之后，个别机型可能瞥见一瞬间的荣耀桌面。
- 响应速度受荣耀转场动画影响：**设置 → 无障碍 → 关闭动画**会更快。
- 荣耀桌面大版本更新后，状态类名可能变化，看日志里的 `state -> xxx` 适配。
- 不修改系统文件、不改默认桌面配置，卸载即恢复。

## 7. 更新日志

只列用户能感知的关键版本，更细的见 git 提交记录。

| 版本 | 变更 |
|---|---|
| 2.9.8 | 换主题 / 桌面重启后慢速上滑不再误跳；最近任务里锁屏再解锁也会落到你的桌面 |
| 2.9.6 | 慢速上滑进最近任务不再被打断成目标桌面 |
| 2.9.5 | 设置页最底下加「调试日志」开关，默认关闭 |
| 2.9.4 | 最近任务里点锁定按钮、锁屏再解锁，不再落回系统桌面 |
| 2.9.1 | 上滑偏高（本来要进最近任务）不再被弹到目标桌面 |
| 2.9.0 | 重构为「功能层 / 交互层」，删掉从未启用的死代码 |
| 2.8.1 | 滑掉最后一个后台任务也进目标桌面 |
| 2.8.0 | 最近任务底部显示运存占用 |
| 2.7.6 | LSPosed 模块列表补上说明（新 API 走 `android:description`） |
| 2.7.5 | 按返回键退出也进目标桌面；图标换成独角兽 |
| 2.7.4 | 最近任务退出提速（400ms → 50ms） |
| 2.7.2 | 最近任务里点空白 / 再上滑也进目标桌面，并加开关 |
| 2.7.1 | 包名改为 `io.github.jocoks.magichome` 并进入 LSPosed 仓库 |
| 2.3.0 | 可指定任意第三方桌面 |
| 2.0.0 | 改钩手势终点，消除误触发 |

## 8. 图标

独角兽剪影来自 [game-icons.net](https://game-icons.net/)（Delapouite，CC BY 3.0），
本项目做了描边、圆角与配色调整；生成脚本 `tools/make_icon.py`。
