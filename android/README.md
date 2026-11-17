# Android 工程

当前为 M1 功能体验版工程，最低 Android 8.0（API 26）。最终调试以用户后续连接的安卓手机为准，不限定品牌。

## 构建

已经准备好本项目便携式工具链的当前电脑，从项目根目录执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build-android.ps1
```

脚本使用项目 `.tools/` 下的 JDK、Android SDK、Gradle 和缓存，过程级设置环境变量；通过临时目录的 Junction 访问工具和工程，以处理 Windows 中文路径。不会设置系统级 JAVA_HOME 或 PATH。

其他电脑可用 Android Studio 打开本目录，准备 JDK 17 和 Android SDK 35 后执行：

```powershell
.\gradlew.bat :core:test :app:assembleDebug
```

已固定：AGP 8.9.2、Gradle 8.11.1、Kotlin / Compose 插件 2.1.20、Compose BOM 2025.04.01、Room 2.7.1。便携 JDK 为 Adoptium 17，SDK Build Tools 35.0.0。

## 模块

- `app`：原生 Compose 界面、ViewModel、Room 数据库与 Repository。
- `core`：纯 Kotlin 金额规则、统计、备份编解码和固定模板 Excel 写出。提取该小模块用于在设备未连接时先验证数据规则。

当前包名 `cn.localledger.app.debug`，名称“本地记账”为工作名。调试签名用于阶段体验，正式签名与升级路径在发布阶段验证。

## 当前范围

已编写分类创建、记账输入、分类弹层、首页累计与结余、明细查看。普通金额按整数分存储；用户账目只在手机本地数据库中保存，不内置虚构账目。

编辑、筛选、排序、停用、删除撤销及文件操作界面仍按后续里程碑推进。`core` 文件模块的本机验证不代表文件功能已在手机上开放，也不代表 Room 全量恢复事务已经通过真机验证。

## 验证入口

```powershell
.\gradlew.bat :core:test
```

测试结果：`core/build/reports/tests/test/index.html`。
测试用 Excel：`core/build/verification/export-fixture.xlsx`，其中均为测试数据。

体验 APK 输出：`app/build/outputs/apk/debug/app-debug.apk`，须以构建成功日志与实际文件为准。
