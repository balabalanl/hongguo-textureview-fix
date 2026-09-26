# 红果灰屏修复 (HongGuo TextureView Fix)

一个 LSPosed 模块：修复 **一加 Ace 2 Pro (ColorOS 16)** 上刷《红果免费短剧》时
**整屏偶发灰蒙蒙**的问题。原理是强制播放器使用 TextureView 渲染（与抖音同款
路径），让视频帧经 GPU 合成进普通界面图层，从而绕开系统显示硬件 (DPU) 的
"视频专用叠加层"状态机 bug。

> 仅在 OnePlus Ace 2 Pro (PJA110) + ColorOS V16.0.0 + 红果 7.3.9.32 上验证通过。
> 理论上同样症状的其他一加/OPPO 高通机型 + ColorOS 16 也可尝试。

## 问题现象

- 刷短剧时屏幕偶发（数分钟~数十分钟一次）**整个屏幕**变灰：对比度/饱和度明显下降，状态栏等系统 UI 一并受累，声音和播放正常
- 以下任一操作可**立即恢复**：按一下音量键、上滑触发多任务手势、弹出任意通知横幅
- 通知横幅消失后画面**再次变灰**；音量键恢复后可维持较长时间

## 诊断结论（为什么是系统 bug）

| 实验 | 结果 | 结论 |
|---|---|---|
| 灰态截屏 vs 清晰态截屏像素对比 | 状态栏纯白像素 RGB 均值 (255,254,254) vs (254,254,254) | 送显数据完全正常，灰发生在数据离开系统之后 |
| `dumpsys display` / `dumpsys SurfaceFlinger` 两态对比 | 色彩模式、亮度、HDR 状态、图层数据空间全部一致 | Android 框架层零差异 |
| 内核面板节点 + 面板驱动 trace | 灰/清两态读数一致，切换瞬间内核零命令 | 状态由 DPU 硬件内部持有 |
| 开发者选项「停用 HW 叠加层」 | **灰屏完全消失** | 实锤：DPU 硬件叠加（视频专用 pipe）合成场景的状态机 bug |

## 修复原理

Android 视频有两条渲染路径：

- **SurfaceView**：视频绕过 App 界面渲染，由 DPU 专用硬件叠加管线直接合成（省电，但触发本 bug）
- **TextureView**：视频帧作为纹理由 App 的 GPU 画进界面，整屏一张普通图层（DPU 看不到独立视频管线，bug 场景不存在）

本模块 hook 红果播放器的渲染视图工厂，把所有决策点钉死在 TextureView 上：

| 层 | Hook 点 | 说明 | 跨版本存活 |
|---|---|---|---|
| L1 | `com.ss.ttm.player.TTPlayerViewHelper.isEnableSurfaceView` → false | **字节 SDK 自带的降级开关**：返回 false 时 TTPlayerView 内部自动用 TTTextureView（原逻辑用于 OPlus+联发科机型规避同类 DPU/OSIE 问题，高通机型未覆盖）。SDK 类名不混淆 | ★★★ |
| L2 | `com.ss.ttm.player.TTPlayerView.<init>(Context,int,float,int)` | 渲染类型参数强制 2 = TTTextureView。SDK 标准类名 | ★★★ |
| L3 | `ITTPlayerViewConfig$$Impl.getConfig()` | 配置单例强制 `enableTTPlayerView=true / onlySurfaceView=false`，保证工厂走 TTPlayerView 分支。生成类名跟随稳定接口名 | ★★ |
| L4 | `j65.g` / `j65.h` | 当前版本的混淆名工厂钩子，仅对 7.3.9.32 有效，更新后自动失配（无害冗余） | ★ |

模块每次加载会输出状态行（LSPosed 日志或 `logcat | grep HongGuoTexFix`）：
`HongGuoTexFix: L1(ok) L2(ok) L3(ok) L4a(ok) L4b(ok)`。红果更新后若灰屏复发，
先看这行日志哪层失配：L1/L2 失效才需要重新定位 hook。

## 安装

1. 已 Root（KernelSU / Magisk）并装有 LSPosed
2. 从 [Releases](../../releases) 下载 APK 安装
3. LSPosed 管理器 → 模块 → 启用「红果灰屏修复」→ 作用域勾选「红果免费短剧」
4. 强制停止红果后重新打开

## 自行编译

需要：Git Bash（Windows）或 WSL、JDK 17+（Android Studio 自带 JBR 即可）、Android SDK（build-tools 34.0.0、platform android-34）。

```bash
# Git Bash，仓库根目录：
export JAVA_HOME="D:/Android/Android Studio/jbr"   # 按需修改
export ANDROID_SDK="D:/Android/Sdk"                # 按需修改
./build.sh
```

脚本会自动生成签名 keystore（首次），产物输出到 `build/hongguofix-<version>.apk`。

> 注意：build-tools 34.0.0 自带的 d8 存在一个 NPE bug（编译含匿名内部类的
> class 时崩溃），因此本仓库所有 hook 回调均写成顶层类，请保持这个结构；
> 另外建议用 build.sh 里的方式直接 `java -cp d8.jar` 调用，避开 d8.bat 的
> 静默失败问题。

## 已知限制

- L4 的混淆类名（`j65.*`）与红果 7.3.9.32 绑定；红果更新后 L4 自动失配，
  L1–L3 继续工作。若 L1/L2 也失配（字节更换播放器栈），需对新版 APK 重新定位
- TextureView 渲染会让红果内的 GPU 开销略增；进出全屏的转场动画有细微差别
  （SurfaceView 的"共享 Surface 无缝转场"优化在 TextureView 下自动退回普通动画）

## 免责声明

本项目仅供个人设备调试与学习使用，与字节跳动、OPPO / 一加官方无关。
请勿用于商业用途。使用本模块产生的任何后果由使用者自行承担。
