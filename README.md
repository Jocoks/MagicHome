# MagicHome —— 让「导航栏上滑回桌面」进入第三方桌面

针对 **荣耀 MagicOS（Android 17 / SDK 37）** 的 LSPosed 模块（开发者在荣耀 2025 年机型上实测），
已在 Nova Launcher 上实测，2.3.0 起可指定**任意第三方桌面**。

荣耀系统不允许把第三方桌面设为默认桌面，而手势「上滑回桌面」在系统内部是
**把桌面任务置前**（不是发 HOME intent），所以改默认桌面/改 intent 都没有用。
本模块在荣耀桌面进程里做重定向，让用户看到的「主界面」变成你指定的桌面
（默认 `com.teslacoilsw.launcher`，Nova Launcher）。

核心做法不是「猜」，而是**听系统手势管线自己的结论**：钩住 Honor 自研 Quickstep 的
`GestureState.setEndTarget()`，只有终点真的是 `HOME`（而不是 `RECENTS` / `LAST_TASK`）
才允许跳转 —— 这是把误触发从「启发式门控」降到「语义级信号」的关键。

---

## 1. 原理

| 层 | 位置 | 做法 |
|---|---|---|
| 手势终点（精确信号） | 荣耀桌面进程 `com.hihonor.android.launcher`（Quickstep 同进程） | 钩 `com.android.quickstep.GestureState#setEndTarget`：终点 `HOME` → 写入 1.2s 令牌；`RECENTS`/`LAST_TASK`/`NEW_TASK` → 清令牌。`Activity.onTopResumedActivityChanged(true)` 与 StateManager 兜底都必须带令牌才跳转，因此「开 App 动画」「快速切应用」不会再误触发 |
| 桌面底部边缘上滑 | 同一进程 `TouchInterationManager#processGestureInputEvent` | 停在荣耀桌面时，从屏幕底部 240px 内快速上滑（≥220px、≤400ms）直接跳目标桌面。底部手势会被系统接管，Activity 只收到 DOWN 后 CANCEL，所以必须从手势管线取事件 |
| 预热 | 目标桌面的 exported ContentProvider | 开机后首次回桌面 +6s、以及每次上滑手势起手时，`acquireContentProviderClient` 静默拉起目标桌面进程（不显示界面），把冷启动的 0.3~0.5s 提前吃掉 |
| 原生层 | `system_server` | HOME intent 改写层保留但**默认关闭**（`ENABLE_SYSTEM_SERVER_LAYER=false`）：本机实测 home 手势走「任务置前」，不经过 `ATMS.startActivityAsUser*`，该层不会触发 |

### 踩过的坑（重要，改代码前先读）

1. **不能用 `CATEGORY_HOME + NEW_TASK` 拉 Nova**：系统会把 Nova 当成第二个
   `type=home` 任务塞进 home 根任务，叠在荣耀桌面之上。最近任务的界面由荣耀桌面
   绘制在下层，触摸却被上层 Nova 吃掉 → 表现为「最近任务卡死，只能上滑退出」。
   现在用普通任务启动（`NEW_TASK | CLEAR_TOP | EXCLUDE_FROM_RECENTS`，不带 HOME）。
2. **不能凭 `Activity.onResume` 就跳**：上滑「回桌面」和「上滑停顿进最近任务」
   都会让桌面 resume，无脑跳会把最近任务顶掉。
3. **不能凭 `Activity.onPause` 判断桌面离开前台**：Launcher3 的 Quickstep 会手动
   调 `onPause`（系统事件日志里并没有真的 pause），用它做门槛会导致「该跳的时候不跳」。
   现在完全由状态机决定。
4. **跳转必须等 Home 转场动画结束**：实测在转场中途（250ms）拉起 Nova，系统会在约 0.4s 后
   把荣耀桌面任务重新置前（`wm_task_to_front ... positionChildTaskAt`），表现为「Nova 闪一下就退回」。
   现在等 800ms 再跳，实测 t+2/5/8s 焦点稳定在 Nova。
5. **resume 时状态可能已经是 `NormalState`**，此时 `goToState` 不再回调，必须在 resume 里
   读一次 `StateManager.getState()`：Normal 才排跳转，Overview/Background 直接跳过。
6. **本机的 LSPosed 是加固版**，只支持新 **libxposed API**（`io.github.libxposed:api:102.0.0`），
   用旧 `de.robv.android.xposed:api:82` 编译出来的模块会在加载时报
   `ClassNotFoundException: Mj.Edis.xxx.IXposedHookLoadPackage`。模块格式为
   `META-INF/xposed/{module.prop,scope.list,java_init.list}`（与设备上
   `honorhook` 模块一致），入口类 `com.dsh.magichome.HookEntry` 继承 `XposedModule`。

---

## 2. 目录

```
magichome/
├── AndroidManifest.xml          # 包名 com.dsh.magichome
├── build.sh                     # 设备内构建（无需 Android Studio）
├── src/com/dsh/magichome/
│   ├── HookEntry.java           # 主逻辑（Quickstep 钩子 / 令牌 / 边缘上滑 / 预热）
│   ├── SettingsActivity.java    # 设置页：指定目标桌面包名（纯代码 UI，无 res）
│   └── SettingsProvider.java    # 给 HookEntry 提供配置（authority com.dsh.magichome.settings）
├── meta/META-INF/xposed/
│   ├── module.prop              # minApiVersion=102 / targetApiVersion=102
│   ├── scope.list               # 荣耀桌面两个包名 + android（预留层）
│   └── java_init.list           # com.dsh.magichome.HookEntry
└── tools/
    ├── pack.py                  # 把 classes.dex + META-INF/xposed 打进 APK
    ├── lsp_enable.py            # 直接写 LSPosed 配置库启用模块（备用路径）
    └── apkmeta.py               # 查看 APK 的 META-INF/xposed 元数据
```

## 3. 构建（设备内 Termux 环境）

依赖：`openjdk-17`、`aapt2`、`apksigner`、`d8`（Termux 源都有），
以及 `tools/android.jar`（Android 平台，取自 Google `platform-37.2` 包）
和 `tools/libxposed-api.jar`（`io.github.libxposed:api:102.0.0` 的 `classes.jar`）。

```sh
cd magichome
sh build.sh          # 产物: build/MagicHome.apk
```

## 4. 安装与启用

```sh
# 1) 安装（root shell / Shizuku shell）
cmd package install -r build/MagicHome.apk

# 2) 在 LSPosed 管理器里启用 MagicHome，作用域至少勾选：
#    com.hihonor.android.launcher
#    （可选）com.hihonor.hnofficelauncher
# 3) 让模块对新进程生效：重启荣耀桌面进程（不需要重启手机）
am force-stop com.hihonor.android.launcher
```

> 荣耀 MagicOS / Android 17 实操补充：
> - 安装只能用 `cmd package install -r -d <绝对路径>`；`pm install` 会被特权 shell 的危险检查拦掉。
> - 桌面进程跑在 `u:r:platform_app:s0` 域，`kill -9 <pid>` 会 `Operation not permitted`（root 也不行），
>   必须用 `am force-stop com.hihonor.android.launcher`；系统会立刻把桌面重新拉起。
> - 桌面进程 SELinux 域见 `ps -A -Z | grep hihonor.android.launcher`。

备用：直接写配置库（本机验证可用，改前会备份）：

```sh
python3 tools/lsp_enable.py            # 启用 + 写入 scope
am force-stop com.hihonor.android.launcher
```

> 装完 APK 后 LSPosed 会自动把它写进 `/data/adb/lspd/config/modules_config.db`
> 的 `modules` 表；`modules_state.enabled` 需要置 1 才真正加载。

## 5. 验证

```sh
# 模块日志（LSPosed 统一日志）
grep MagicHome /data/adb/lspd/log/modules_*.log | tail
# 关键行：
#   module loaded, systemServer=false process=com.hihonor.android.launcher
#   hooked Activity.onResume in launcher process
#   state machine hooks installed: 6
#   state -> NormalState
#   bounced -> Nova, from com.hihonor.android.launcher/.drawer.DrawerLauncher
#   state -> HnOverviewState
#   cancelled pending bounce: non-home state: HnOverviewState
```

手势预期：

| 动作 | 预期 |
|---|---|
| 任意 App 里快速上滑 | 约 0.25s 后落到 Nova 桌面 |
| 上滑并停顿 | 最近任务正常停留，卡片可滑动 |
| 最近任务里点一个 App | 进入该 App，不被 Nova 顶掉 |

## 6. 已知限制 / 后续

- 跳转是在荣耀桌面已经显示后才发生的，**约 250ms 延迟**；个别机型上仍可能
  看到一瞬间荣耀桌面。要彻底消除需要打开 `system_server` 预留层（改 HOME intent），
  代价是**重启手机**后生效（重启由机主自行决定）。
- 荣耀桌面更新后类名/状态名可能变化（本机 `HnOverviewState` / `NormalState` /
  `HnBackgroundAppState`）。日志里的 `state -> xxx` 可直接用来适配。
- 模块只在荣耀桌面进程里工作，不修改系统文件、不改默认桌面配置，卸载即恢复。

## 7. 卸载

```sh
cmd package uninstall com.dsh.magichome
kill -9 $(pidof com.hihonor.android.launcher)
```


---

## 8. 版本记录 / 关键修复

| 版本 | 变更 |
|---|---|
| 2.6.0 | **长按卡片 → 权限设置**（机主反馈图标太丑，去掉 2.5.0 的按钮）：不再注入任何视图，改为在 `TaskView#dispatchTouchEvent` 里跟踪手势——原地按住 ≥460ms、位移不超过 touchSlop 就打开该任务的权限页，触发后吞掉后续 MOVE/UP（避免又变成「打开应用」）；卡片顶栏分屏/锁定按钮所在区域不接管。实测：长按 460ms 进「XX 权限」页、轻点仍正常打开应用 |
| 2.5.0 | **最近任务卡片「应用信息」按钮**（已废弃，见 2.6.0）：钩 `TaskViewHeader#onFinishInflate` 注入圆形按钮；触摸在 `TaskView#dispatchTouchEvent` 拦下，读 `TaskView.mTask` 的顶层 ComponentName 后 `startActivity(android.intent.action.MANAGE_APP_PERMISSIONS --es android.intent.extra.PACKAGE_NAME <pkg>)` 打开权限页 |
| 2.4.0 | **项目改名 MagicHome**：包名 `com.dsh.novahome` → `com.dsh.magichome`，配置 provider authority `com.dsh.magichome.settings`，SharedPreferences `magichome`，钩子 id 前缀 `magichome-`。**升级要卸载旧版**（包名不同，配置不迁移；装好后到设置页重选一次目标桌面）。功能与 2.3.0 一致 |
| 2.3.0 | **任意第三方桌面**：新增设置页（`SettingsActivity`）与配置 provider（`SettingsProvider`），首次进入由用户点选或输入目标桌面包名并保存，5 秒内自动生效、无需重启；跳转/手势预热/开机预热全部跟随配置。Nova 之外需该桌面有导出的 ContentProvider 才会预热，没有则跳过（只少了预热）。实测：切到荣耀桌面后上滑确实回落荣耀桌面，切回 Nova 后恢复正常 |
| 2.2.1 | **底部边缘上滑**：按用户更正，「停在荣耀桌面时快速上滑进 Nova」只认**屏幕最底部 240px 内起手**（屏高 3104 时 y ≥ 2864）；并改从 Quickstep 手势管线 `TouchInterationManager.processGestureInputEvent` 取事件（底部边缘上滑会被系统手势接管，Activity 只收到 DOWN 后 CANCEL，原路径永远不触发）。实测：底部边缘 550px/83ms → 跳 Nova；695ms 慢拖不触发；屏幕中部 400px 快滑不触发 |
| 2.2.0 | **桌面快速上滑进 Nova**：停在荣耀默认桌面（NormalState、抽屉未开）时，快、直、够长的上滑直接跳 Nova；判定在 ACTION_DOWN 抓状态（上滑过程中荣耀会切 HintState/抽屉动画态，抬手再判已经晚了），要求 dy ≤ -220px、dt ≤ 400ms、横向漂移 ≤ 纵向 60%、有窗口焦点、1.2s 冷却。实测：桌面快速上滑 1/1 命中；慢拖 701ms 不触发；最近任务里上滑 `ok=false` 不触发；从 App 上滑回桌面的原逻辑不受影响 |
| 2.1.0 | **预热 Nova**：① 开机后首次回桌面 +6s 静默拉起 Nova 进程（`content://com.teslacoilsw.launcher.api` provider，不显示界面，仅在 uptime < 10 分钟内生效）；② 每次从 App 上滑手势一起手即预热（`TouchInterationManager.createOtherActivityInputConsumer`），转场结束时 Nova 进程已就绪。实测 Nova 进程在手势 DOWN 后即 `am_proc_start ... content provider`，桌面转场后 +151ms 即 resumed（预热前冷启动需再等约 0.3~0.5s） |
| 1.4.3 | 跳转成功后 2s 兜底恢复荣耀桌面窗口透明度（极端失败场景不会留下隐形桌面） |
| 2.0.0 | **精准注入**：改钩 Quickstep 手势终点 `GestureState.setEndTarget(GestureEndTarget)`，只有终点是 `HOME` 才发 1.2s 生效的令牌；`topResumed` 与 StateManager 兜底都必须带令牌才跳转。日志实测：上滑回桌面 4/4 命中，进最近任务/快速切应用 3/3 不再误跳，从 Nova 点开 App 0 触发 |
| 1.9.1 | 探针版：只记录 Quickstep 管线方法调用（`setEndTarget`/`finishSwipeUp`/`onSettledOnEndTarget`/`calculateEndTarget`…），用于定位精确注入点 |
| 1.9.0 | 焦点门控 + 触屏取消（`hasWindowFocus` + `dispatchTouchEvent` 取消），降低「开 App 动画期间误跳」 |
| 1.4.2 | Home 转场等待期间把荣耀桌面窗口设为透明（看到的是壁纸过渡而不是荣耀桌面图标），进入最近任务/后台态立即恢复 |
| 1.4.1 | **关键修复**：`mPendingBounce` 执行后未清空，导致第一次跳转之后所有后续跳转都被 `bounce already pending` 挡掉（表现为重启后第一次有效、之后「像没装一样」） |
| 1.4.0 | 打开 system_server 原生层（HOME intent 改写） |
| 1.3.5 | 跳转延迟 250ms→800ms，避开系统 Home 转场把荣耀桌面重新置前 |
| 1.3.4 | resume 时读取 `StateManager.getState()`，补上「已是 NormalState 不回调 goToState」的漏跳 |

### 实测结论：本机 system_server 原生层不会触发
`home` 手势在本机走的是「把 home 任务置前」（`wm_focused_root_task ... bringingFoundTaskToFront`），
不经过 `ATMS.startActivityAsUser*`，因此 system_server 的 HOME intent 改写**实测没有日志命中**
（`rewrite HOME` 计数为 0），Nova 无法变成真正的系统 home。实际生效的是荣耀桌面层的重定向，
代价是约 0.8s 的转场等待。

---

## 9. 2.0.0 精确触发机制（为什么不再误跳）

荣耀手势管线在本机是 **Honor 自研 Quickstep**（`com.hihonor.android.launcher.quickstep.TouchInteractionService`，
跑在桌面进程内），从 App 上滑时的事件链（日志实测）：

```
TouchInterationManager.processGestureInputEvent(ACTION_DOWN)
  OtherActivityInputConsumer.onMotionEvent(ACTION_DOWN)
  ... 上滑 ...
  AbsSwipeUpHandler.onGestureEnded(velocity, point)
  AbsSwipeUpHandler.calculateEndTarget(...)            // 算出终点
  GestureState.setEndTarget(<终点>, false)             // ★ 精确信号
  HnLauncherSwipeHandlerV2.processForEndHome()         // 仅 HOME 时
  AbsSwipeUpHandler.maybeFinishSwipeToHome()           // 仅 HOME 时
  AbsSwipeUpHandler.onSettledOnEndTarget()
  桌面 Activity.onTopResumedActivityChanged(true)      // 约 30~100ms 后
```

`GestureState.setEndTarget` 的终点取值实测：
- `HOME` —— 真·上滑回桌面（4/4 命中）
- `RECENTS` / `LAST_TASK` —— 最近任务 / 快速切应用（3/3 不再误跳）

因此 2.0.0 的做法：
1. 钩 `setEndTarget(GestureEndTarget)` 与 `setEndTarget(GestureEndTarget, boolean)`；
   终点 `HOME` → 记 `sHomeGestureAt`（令牌，有效期 `HOME_GESTURE_TTL_MS = 1200ms`），其他终点 → 立即清令牌。
2. `onTopResumedActivityChanged(true)` 只有 **带有效令牌** 才排 16ms 跳转。
3. `StateManager.goToState(Normal*)` 的兜底也要求令牌，且延迟降到 400ms（原 900ms 盲兜底已删）。
4. 跳转成功后 `consumeHomeGesture()` 消费令牌，同一次手势不会跳两次。
5. 仍然保留：overview 跳过、失焦取消、`dispatchTouchEvent(DOWN)` 取消。

排查用探针：把 `HookEntry.QUICKSTEP_PROBE` 改成 `true` 重新构建，即可打印
`probe setEndTarget(...)` / `probe ash.settledOnEndTarget(...)` 等调用链（日志上限 4000 行）。

---

## 10. 2.1.0 预热机制（为什么进 Nova 不再等冷启动）

痛点：Nova 进程是被系统按 cached（`oom_score_adj=900`、standby bucket 5）回收的常客，
每次上滑回桌面都要重新 fork + Application.onCreate + 加载资源，表现为「进去要等、还卡」。

两个预热点，**都只拉起进程、不显示任何界面**：

```
# 开机路径（用户要的“开机自动唤醒”）
桌面 Activity.onResume（仅刚开机 10 分钟内）
  → postDelayed(6000ms)
  → ContentResolver.acquireContentProviderClient("content://com.teslacoilsw.launcher.api")
  → 系统静默 fork Nova 进程（am_proc_start reason=content provider, ApiProvider）
  → 立即 close()，界面从不出现

# 手势路径（真正消掉那 0.3~0.5s 等待）
TouchInterationManager.createOtherActivityInputConsumer(...)   // 上滑手势起手
  → mHandler.post(warmNovaSilently("gesture"))
  → 同上 provider 预热
  → ... 手势结束 setEndTarget(HOME) → 令牌 → 16ms 后跳转 ...
```

实测（Nova 进程被 `am kill` 掉后从 App 短滑上滑）：

```
T+0ms     am_proc_start [.., <pid>, com.teslacoilsw.launcher, content provider, {ApiProvider}]
T+110ms   wm_on_resume_called(<launcher pid>) Honor DrawerLauncher   <- 桌面转场收尾
T+261ms   wm_on_resume_called(<nova pid>) NovaLauncher               <- Nova 上线
```

- 预热只是 `acquireContentProviderClient` 再 `close()`：不弹界面、不抢焦点、不改变前台任务。
- 进程起来后跟平时一样仍是 cached，不会常驻占内存（用户明确不要「常驻后台」）。
- `BOOT_WARM_WINDOW_MS = 600_000`：只有开机 10 分钟内才排开机预热；模块中途重载不会白跑。
- 不想要手势预热：把 `HookEntry.GESTURE_PREWARM` 改成 `false` 重新构建（开机预热不受影响）。
- 不想要开机预热：把 `BOOT_WARM_DELAY_MS` 改大或注释掉 `maybeBootWarm` 调用。

---

## 11. 2.2.1 桌面底部边缘上滑 → Nova

适用场景：**已经停在荣耀默认桌面**（例如刚开机、从最近任务按 home、或系统把桌面置前），
此时想「从屏幕最底部快速上滑直接去 Nova」，而不是留在荣耀桌面。

### 为什么落在 Quickstep 手势管线，而不是 Activity

实测（探针版，屏高 3104）：

- 从底部边缘 `input swipe 720 3050 720 2500 90` 上滑时，`Activity.dispatchTouchEvent`
  **只收到 ACTION_DOWN，随后被系统手势接管**（收到的是 CANCEL，没有 UP）→ 在 Activity 里判位移/速度
  永远等不到抬手，2.2.0 的 Activity 路径对这种起手位置**根本不会触发**。
- Quickstep 侧能看到完整的一对事件：
  `probe tim.processGesture(ACTION_DOWN@720,3050)` → `probe tim.processGesture(ACTION_UP@720,2500)`。
  但又没有 `oac.motion` / `endTarget` 记录 —— 因为从桌面自身起手不产生「app → home」的手势结算，
  2.0.0 的 `GestureState.setEndTarget(HOME)` 令牌也不适用。

所以 2.2.1 把底部边缘上滑的判定挂到
`com.hihonor.android.launcher.quickstep.TouchInterationManager#processGestureInputEvent(MotionEvent)`（钩子 id `magichome-edge-swipe`）：

```
ACTION_DOWN  ── 起点 y ≥ 屏高-240px（本机 3104-240=2864）才继续
             ├─ 记坐标/时间
             ├─ isSittingOnHomeWorkspace(): state 属 home、非 overview、抽屉未开
             └─ warmNovaSilently("home-swipe")  // 起手即预热
ACTION_UP    ── dy ≤ -220px、dt ≤ 400ms、|dx| ≤ 0.6·|dy|、起手时在桌面、
                窗口有焦点、抽屉未开、距上次跳转 ≥1.2s
                → bounceToNova()  日志 "home swipe-up -> Nova (src=gesture, …)"
```

Activity 路径（`dispatchTouchEvent`）仍保留同一套判定（日志 `src=activity`），作为
「系统手势没接管、桌面自己收到完整 DOWN/UP」场景的兜底；两条路径共用
`handleHomeSwipeUp(...)` 的门控与冷却，不会重复跳。

关键设计点：

1. **必须限定底部边缘**。不限定的话，屏幕中部的普通上滑也会被 Quickstep 看到并误跳；
   实测中部 `input swipe 720 2600 720 2200 90` 在加了 `isInBottomEdge` 后无任何日志、不跳转。
2. **状态在 DOWN 抓，不在 UP 抓**。荣耀在快速上滑过程中会把状态切到 `HintState`（抽屉/提示动画），
   到 UP 再读 `getState()` 会误判成「不是桌面」而漏触发。
3. **抽屉判定用反射**：只认 `DrawerLauncher.isAppsViewVisible()` / `isDisplayAppsView()`，
   避免在抽屉里滚动列表时误跳。
4. 参数（`HookEntry` 顶部常量，按手感调）：
   - `HOME_EDGE_BOTTOM_PX = 240f`（起手必须在底部 240px 内）
   - `HOME_SWIPE_MIN_PX = 220f`（本机 density 600，约 84dp）
   - `HOME_SWIPE_MAX_MS = 400L`
   - `HOME_SWIPE_MAX_DX_RATIO = 0.6f`
   - `HOME_SWIPE_COOLDOWN_MS = 1200L`
   - 总开关 `ENABLE_HOME_SWIPE_TO_NOVA`
5. 不消费事件（照常 `chain.proceed()`）：荣耀桌面自己的手势处理仍会跑，但 Nova 会在下一帧盖上去。
6. 从 App 上滑回桌面不受影响：那种手势起手时桌面不在前台（`startedOnHome=false`），
   由 2.0.0 的 `endTarget == HOME` 令牌路径负责。

排查日志：

```sh
grep MagicHome /data/adb/lspd/log/modules_*.log | tail -20 | sed 's/.*MagicHome,[^]]*\] //'
# home workspace check: state=NormalState appsViewOpen=false ok=true
# edge gesture start y=3050 home=true
# home swipe-up -> Nova (src=gesture, dy=-550, dx=0, dt=83ms)
# bounced -> Nova, from com.hihonor.android.launcher/.drawer.DrawerLauncher state=NormalState
```

---

## 12. 2.3.0 可配置目标桌面（不止 Nova）

首次安装后，从应用列表打开 **MagicHome** 设置页：

1. 列表会枚举本机所有 `MAIN + CATEGORY_HOME` 的桌面应用（荣耀抽屉桌面、Nova、以及你装的任何第三方桌面），点一下自动填入包名；也可以**手动输入任意包名**。
2. 点「保存并生效」→ 写入 `SharedPreferences("magichome").target_pkg`。
3. 模块侧 5 秒内自动刷新（`HookEntry.refreshTargetIfStale`），跳转、手势预热、开机预热全部切到新目标，**不需要重启，也不需要重启桌面进程**。

目标组件解析顺序（`SettingsProvider` 的 `query` 返回三列）：

| 列 | 内容 |
|---|---|
| `target_pkg` | 目标桌面包名 |
| `target_act` | 优先 `PackageManager.resolveActivity(MAIN+HOME, setPackage)`；无则退回 `getLaunchIntentForPackage` |
| `warm_authority` | 预热用的 ContentProvider authority：目标桌面若有一个 `exported=true` 且 `read/writePermission` 都为空的 provider 就取它；Nova 固定用 `com.teslacoilsw.launcher.api`；找不到返回空串 |

- 空 `warm_authority` 不报错：预热调用打 `warm(… ) skipped: no exported provider`，跳转照常。
- 校验：保存前用 `resolveHomeComponent` 解析，解析不到会 Toast 提示「该包名没有桌面 Activity」并拒绝保存。
- 模块声明了 `QUERY_ALL_PACKAGES`（仅用于枚举桌面与解析组件，不做其他用途）。
- 设置页是纯代码 UI（模块不带 res），样式跟随系统 `Theme.DeviceDefault`。

排查：

```sh
# 看当前生效配置（root/shell）
content query --uri content://com.dsh.magichome.settings/target
# 日志里每次跳转都会打印目标
grep -E "target launcher =|bounced ->" /data/adb/lspd/log/modules_*.log | tail
```

---

## 13. 2.6.0 长按最近任务卡片 → 打开该应用的权限设置

机主最初要的是「卡片顶栏放个应用信息按钮」，2.5.0 做完后反馈「图标太丑」，于是改成
**长按卡片**触发（2.6.0 起不再注入任何按钮视图）。

实现（`RecentsAppInfo.java` + `HookEntry.armRecentsLongPressHook`）：

1. **手势识别**：钩 `com.android.quickstep.views.TaskView#dispatchTouchEvent`（回退
   `BaseTaskView`）。DOWN 记录坐标/时间并用 `Handler` 起一个 460ms 的定时器；
   MOVE 位移超过 `touchSlop` 或 UP/CANCEL 就取消；定时器到期说明是「原地长按」，
   调 `performHapticFeedback(LONG_PRESS)` 给个振动反馈，然后打开权限页。
   LONG_PRESS_MS 取 460ms（略慢于系统 400ms），减少滑动手势误触。

2. **不破坏原有交互**：DOWN/MOVE/UP 默认都 `return false` 交给桌面处理，
   所以轻点开应用、左右滑、拖拽都不受影响；只有长按触发之后，才把紧随其后的
   MOVE/UP 吞掉（返回 true），避免抬手时又被当成一次点击去打开应用。

3. **避开顶栏按钮**：DOWN 落在卡片 `button_group`（分屏 / 锁定）范围内时不接管。

4. **认应用 + 开权限页**：从被长按的 `TaskView` 读 `mTask`，依次尝试
   `getTopComponent()` / `getComponent()` / `getBaseComponent()`、字段
   `component|mComponent|baseComponent|topComponent`、`getBaseIntent()` / `getIntent()`，
   取 `ComponentName.getPackageName()`；再 `startActivity(android.intent.action.MANAGE_APP_PERMISSIONS`
   + `android.intent.extra.PACKAGE_NAME <pkg>)`。本机该 action 由
   `com.android.permissioncontroller/.permission.ui.ManagePermissionsActivity` 应答，
   直达「XX 权限」页；异常时回退 `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`。

验证（本机，versionCode 32 / 2.6.0）：

```
# 长按 900ms（卡片内空白处，改由 input swipe 同点模拟）
I MagicHome: [appinfo] long press (460ms) task=[id=3873 windowingMode=1 ...] DeepCode pkg=com.dsharnessmobile.shell
I MagicHome: [appinfo] open permissions page for com.dsharnessmobile.shell
mCurrentFocus=... com.android.permissioncontroller/...permission.ui.ManagePermissionsActivity   # 「DeepCode 权限」页
# 对照：轻点同一位置 → 仍正常打开应用
mCurrentFocus=... com.dsharnessmobile.shell/com.dsharnessmobile.shell.MainActivity
```

安装方式同前（`sh build.sh` → 装 `build/MagicHome.apk`，LSPosed 作用域保持
`com.hihonor.android.launcher`，改完 `am force-stop com.hihonor.android.launcher` 生效）。

> `Probe.java` 的视图树探测默认关闭（`HookEntry.PROBE_OVERVIEW=false`）：
> 一次打几百行 `Log.i` 会让最近任务界面明显卡顿，排查时临时打开、看完就关。
