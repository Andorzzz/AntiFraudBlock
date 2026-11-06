# AntiFraudBlock · 反诈上报阻断

> 🚀 **本项目基于 [eta 的 AntiFraudBlock](https://github.com/yooma1/qufz) 重构修改。**
> 已适配最新版 OPPO/ColorOS 手机管家，修复了原版拦截失效的问题。请直接前往 **[Releases](../../releases)** 页面下载最新 `.apk`。

一个只做一件事的 LSPosed 模块：**掐断手机管家 AI 反诈的遥测与云端上报，本地风险提醒照常工作。**

作用域仅包含 `com.coloros.phonemanager`（OPPO 手机管家），不碰系统框架，不影响其他应用。

## ✨ 核心特性

- **适配新版管家**：修复因官方混淆导致拦截失效的问题，精准拦截 `AiVoiceDetectDCSUtils` 和 `TrackApi`。
- **新旧全兼容**：底层出口同时抓取新版 `track` 系列方法和旧版 `E` 方法，新老系统通吃。
- **动态兜底机制**：官方若再次改名，模块会自动退化为“静默该类所有静态方法”，防止彻底失效。
- **保留本地功能**：本地风险弹窗、通知栏警报、反诈记录落库全部正常，只杀云端上报。
- **现代化构建**：全面拥抱 Gradle，废弃旧版构建脚本。支持中英文双语显示。

## 📲 安装与使用

1. 前往 **[Releases](../../releases)** 页面下载最新的 `.apk` 并安装。
2. 在 LSPosed 中启用模块，**作用域勾选「手机管家」**。
3. 强制停止手机管家并重新打开（或在终端执行 `am force-stop com.coloros.phonemanager`）。
4. 查看日志确认是否成功：`logcat -s AntiFraudBlock:*`

### 🛡️ 逃生阀（紧急关闭）
如果你遇到任何异常，可以通过以下命令瞬间关闭模块的所有拦截（无需卸载）：
```bash
touch /data/local/tmp/afb.disable
am force-stop com.coloros.phonemanager
