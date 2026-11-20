# Android 工程

当前为 0.1.10 主页面滑动切换版工程，最低 Android 8.0（API 26）。在记账、明细和设置的主页面可左右滑动或点击底部标签切换；从明细的日历日期格中开始横滑仍用于翻月。启动继续只读取分类和本月汇总。

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

已编写分类创建及改名、大类长按拖动排序、大类/小类停用与重新启用、无历史记录分类删除、记账输入、自适应换行的分类弹层、首页累计与结余、明细查看与记录编辑。记录编辑在事务内检查原记录未变化，保留 ID、创建时间和原时刻，只更新可编辑字段；原停用分类可保留，改选分类需处于启用状态。大类排序一次事务保存，停用大类保持原槽位；空分类删除由数据层再次检查历史记录。普通金额按整数分存储；用户账目只在手机本地数据库中保存，不内置虚构账目。

账本备份与恢复界面、小类排序仍按后续里程碑推进。Excel 导出通过安卓文件选择器保存，沿用固定的「收支明细／导出说明」两张工作表。

## 验证入口

```powershell
.\gradlew.bat :core:test
```

测试结果：`core/build/reports/tests/test/index.html`。
测试用 Excel：`core/build/verification/export-fixture.xlsx`，其中均为测试数据。

体验 APK 输出：`app/build/outputs/apk/debug/app-debug.apk`，须以构建成功日志与实际文件为准。
