# 红果短剧修复（HongGuoTexFix）

针对 **红果免费短剧 `com.phoenix.read` 7.3.9.32** 的 LSPosed 模块。
基于 **Modern Xposed API（libxposed 102）**，部分实现移植自「白果助手」的成熟方案。

## 支持环境

| 项目 | 值 |
|---|---|
| 目标 App | `com.phoenix.read` 7.3.9.32（versionCode 73932） |
| 作用域 | `com.phoenix.read`（仅主进程，跳过 45 个 satellite 进程） |
| 模块 API | libxposed `minApiVersion=102` / `targetApiVersion=102` |
| Android | 8.0 (API 26) ~ 16 (API 36) |
| 框架 | LSPosed / Vector（支持 Modern API 102） |
| 验证设备 | 一加 Ace 2 Pro (PJA110)，ColorOS 16 / Android 16 |

## 功能

### 1. 修复播放灰屏

**根因**：`com.ss.ttm.player.TTPlayerViewHelper#isEnableSurfaceView(Context)`
只在「OPPO/一加/Realme **且** 联发科」机型返回 `false`：

```java
sDeviceEnableSurfaceView = (isDeviceOppoRealmeBrand() && isMediaTekChipset() && ...) ? false : true;
private static boolean isMediaTekChipset() { return Build.HARDWARE.matches("mt[0-9]*"); }
```

一加 Ace 2 Pro 为高通平台 → 条件不成立 → 返回 `true` → 走 SurfaceView →
独占 DPU 视频叠加层 → ColorOS 16 间歇性灰屏。手动「停用 HW 叠加层」有效，
是因为它把合成改走 GPU —— 即 TextureView 路径。

**四层防线**（逐层独立，任一层失败不影响其他层）：

| 层 | Hook 目标 | 稳定性 | 作用 |
|---|---|---|---|
| L0 | `fq4.a#a(Object,String,boolean)` | MEDIUM | 配置解析总入口，改写两个配置字段 |
| L1 | `TTPlayerViewHelper#isEnableSurfaceView(Context)` | **HIGH** | 强制返回 false，主防线 |
| L2 | `TTPlayerView#<init>(Context,int,float,int)` | **HIGH** | 渲染类型 1(SurfaceView) → 2(TTTextureView) |
| L3 | `ITTPlayerViewConfig$$Impl#getConfig()` | MEDIUM | 反射改写配置字段 |

### 2. 双击打开评论区（替代双击点赞）

调用链（7.3.9.32 已核实）：

```
VideoGestureDetectLayout → GestureDetector(e55.h) → onDoubleTap
  └ r65.o0 → t65.v#A4 → es4.a → wp4.e#onDoubleTap   ← 点赞(digg) 触发点
```

三层实现：

1. **dex 扫描**（移植自白果助手 `CleanScreen.findImplementations`）：
   反射遍历 BaseDexClassLoader → pathList → dexElements → dexFile，枚举
   `GestureDetector$OnDoubleTapListener` 的全部实现类并 hook —— **不依赖混淆类名**；
2. 直接 hook 已知目标 `e55.h` / `wp4.e`（快路径）；
3. 评论区打开走 `ko4.a#bizContext`（类型 `lo4.b`）。

**使用方法**：先点一次评论区按钮，让模块捕获 `v35.l` 参数
（该参数 20 多个字段，无法手工构造），之后双击即可打开。
打开失败会自动回退到原始点赞逻辑，不会吞掉手势。

### 3. 屏蔽红包悬浮窗与「赚钱」挂件

实现移植自「白果助手」`HidePendant`（对 7.3.9.32 实测有效），五层冗余：

| 层 | 手段 | 稳定性 |
|---|---|---|
| 层1 | hook `gb6.m` / `yf6.m` 的 `l()` → skip，挂件不被创建 | MEDIUM |
| 层2 | hook `com.dragon.read.goldcoinbox.pendant.video.f1` 的 `d()` | HIGH（未混淆） |
| 层3 | hook `PlanPendantServiceImpl#triggerEvent`，事件名含 pendant 才拦 | HIGH |
| 层4 | hook `com.dragon.read.pages.main.m` 的 b/a/c，参数含 **LuckyBenefit / ShopMall** 时反射扫 View 并隐藏 | HIGH（英文 key 不走混淆） |
| 层5 | Lottie 资源兜底：`setAnimation` 命中 `video_task_red_pack_lottie` 即隐藏 | HIGH |

配套机制（同样移植自白果助手）：

- **40ms 看护线程**：被 App 重新显示就再关；WeakReference 防泄漏，空闲自动停
- **2s/5s/10s/20s/40s 退避重试**：主 App 挂件懒加载，启动时类可能还没 load

> 层4 使用英文业务 key（`LuckyBenefit` / `ShopMall`）而非中文文案，
> 避免早期版本因匹配「赚钱」字样而误伤其他界面的问题。

## 安装

1. 下载 [release/hongguofix-2.0.0.apk](release/hongguofix-2.0.0.apk)
2. 在 LSPosed 管理器中启用模块
3. **勾选作用域 `com.phoenix.read`** ← 最容易漏，漏了就完全没效果
4. 强制停止红果短剧后重新打开

> LSPosed 侧模块注册状态可能丢失。若功能失效，先确认模块在 LSPosed 列表中处于启用状态。

## 日志

```bash
adb logcat | findstr HongGuoTexFix     # Windows
adb logcat | grep HongGuoTexFix        # Linux / macOS
```

日志前缀：`[INIT]` `[PROCESS]` `[RESOLVE]` `[HOOK]` `[CALL]` `[WARN]` `[FAIL]`

生效标志示例：

```text
# 功能一
[RESOLVE] Feature1 L1 com.ss.ttm.player.TTPlayerViewHelper#isEnableSurfaceView(Context) stability=HIGH
[CALL]    L2 render type 1 -> 2 (TTTextureView)

# 功能二
[RESOLVE] Feature2 dex 扫描到 OnDoubleTapListener 实现 N 个
Feature2: 已缓存评论参数 ...                ← 需先点一次评论按钮

# 功能三
层1 挂件分发 gb6.m.l xN stability=MEDIUM
层4 赚钱Tab 关键词=LuckyBenefit/ShopMall stability=HIGH
PendantBlocker: 全部层级已绑定
```

## 构建

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

需要 JDK 17+、Android SDK（compileSdk 36、build-tools 36）。
在 `local.properties` 中配置 `sdk.dir` 指向本机 Android SDK。

## 工程结构

```text
app/src/main/java/com/zcode/hongguofix/
├─ ModuleMain.java               入口，功能注册与 FeatureGuard 隔离
├─ HookHelper.java               hook 工具（移植自白果助手 HookKit）
├─ FeatureGuard.java             功能隔离，单个失败不影响宿主
├─ ProcessRouter.java            进程判定，只处理主进程
├─ ReflectUtils.java             反射封装，失败带完整日志
├─ LogUtils.java                 统一日志
└─ hooks/
   ├─ RenderPathHook.java        功能一：灰屏（四层）
   ├─ DoubleTapCommentHook.java  功能二：双击评论区（三层）
   └─ PendantBlocker.java        功能三：挂件屏蔽（五层，移植自白果助手）

app/src/main/resources/META-INF/xposed/
├─ java_init.list                入口类
├─ scope.list                    作用域
└─ module.prop                   Modern API 元数据
```

## 设计原则

- **Modern API 优先**：libxposed 102，不使用 legacy `de.robv.android.xposed`
- **FeatureGuard 隔离**：任一功能失败不影响其他功能，也不影响 App 启动
- **Fail-safe**：目标找不到 / 指纹不符 → 不 Hook，而非乱 Hook
- **多级冗余**：功能一 4 层、功能二 3 层、功能三 5 层
- **不依赖短混淆名**：优先稳定签名，辅以业务关键词与 dex 扫描
- **最小作用域**：仅主进程，跳过 45 个 satellite 进程
- **内存安全**：WeakReference / WeakHashMap 缓存反射结果 / 硬上限

## 已知限制

- 功能二需先点一次评论按钮以捕获参数（`v35.l` 字段过多，无法手工构造）
- 功能三的层1 / 层3 依赖混淆类名，App 大版本更新后可能失配（层2 / 层4 / 层5 仍有效）
- App 更新后需重新验证各层命中情况（日志会打印各层 hook 数量）

## 致谢

- 功能三与 dex 扫描实现移植自「白果助手」
- 感谢 balabalanl 的原始灰屏修复模块（Legacy API 版 v1.1）

## 免责声明

本模块仅用于个人设备问题诊断与技术学习，请勿用于商业或侵权用途。
