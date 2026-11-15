# Guard 第三方组件与许可证清单

更新日期：2026-11-13

本文档记录当前仓库直接声明的主要第三方组件，用于发布前许可证审查。它不是法律意见，也不替代组织法务或开源合规团队的结论。发布候选包必须在锁定依赖、生成完整传递依赖清单后再审查一次。

## 1. 当前结论

- 孩子端基线来自 `android-lockdown-dpc`，`android/LICENSE` 与 `android/NOTICE` 已保留，基线声明为 Apache-2.0。
- 本项目直接依赖以 Apache-2.0、MIT、BSD、EPL/LGPL 和 Public Domain 为主，未发现需要购买商业授权的直接依赖。
- **尚未生成完整 SBOM，尚未完成传递依赖和法务签字，不能宣称“许可证审查已完成”。**
- Logback、H2 等双许可证组件以及 EPL/LGPL 条款必须在分发前由法务确认具体选择、通知义务和源码提供方式。

## 2. Android 孩子端

| 组件 | 版本 | 许可证 | 用途 |
| --- | --- | --- | --- |
| AndroidX Core KTX | 1.16.0 | Apache-2.0 | Android 基础扩展 |
| AndroidX AppCompat | 1.7.1 | Apache-2.0 | 兼容 UI 与系统组件 |
| AndroidX Activity Compose | 1.10.1 | Apache-2.0 | Compose Activity 集成 |
| AndroidX Lifecycle | 2.8.7 | Apache-2.0 | 生命周期和状态 |
| Compose BOM | 2025.04.01 | Apache-2.0 | Compose 版本对齐 |
| Compose UI / Material 3 / Icons | 由 BOM 锁定 | Apache-2.0 | 管理界面 |
| OkHttp | 4.12.0 | Apache-2.0 | HTTPS 通信 |
| LiveKit Android | 2.30.0 | Apache-2.0 | WebRTC 音视频与屏幕共享 |
| JUnit | 4.13.2 | EPL-1.0 | JVM 单元测试，不分发到运行时 |
| org.json | 20251224 | Public Domain | JVM 单元测试 JSON，不分发到运行时 |

Android 构建还使用 Android Gradle Plugin、Kotlin Gradle Plugin 等构建工具；它们通常不随应用运行时分发，但 CI 和构建镜像仍需按组织政策审查。

## 3. Ktor 服务端

| 组件 | 版本 | 许可证 | 用途 |
| --- | --- | --- | --- |
| Kotlin / kotlinx.serialization | 2.2.0 / 1.8.1 | Apache-2.0 | 语言运行时与 JSON |
| Ktor Server | 3.2.3 | Apache-2.0 | HTTP、路由、CORS、日志、测试宿主 |
| java-jwt（Auth0） | 4.5.0 | MIT | 家长/设备 JWT |
| LiveKit Server SDK（Kotlin/Java） | 0.10.0 | Apache-2.0 | 服务端删除 LiveKit 房间、撤销已签发 token 所在会话 |
| HikariCP | 6.2.1 | Apache-2.0 | JDBC 连接池 |
| PostgreSQL JDBC | 42.7.7 | BSD-2-Clause | 生产数据库驱动 |
| Flyway Core / PostgreSQL | 10.20.1 | Apache-2.0 | 数据库迁移 |
| Logback Classic | 1.5.18 | EPL-1.0 或 LGPL-2.1（双许可证） | 日志实现 |
| H2 Database | 2.3.232 | MPL-2.0 或 EPL-1.0（双许可证） | 仅测试数据库，不进入生产运行时 |

Logback 的分发义务取决于组织选择的许可证；如果选择 EPL/LGPL 路径，应保留相应通知并评估修改和源码提供要求。H2 只用于测试，不随生产镜像分发，但仍应保留测试依赖记录。

## 4. React 家长 PWA

| 组件 | 版本 | 许可证 | 用途 |
| --- | --- | --- | --- |
| React / React DOM | 19.1.0 | MIT | PWA UI |
| react-router-dom | 7.6.0 | MIT | 客户端路由 |
| livekit-client | 2.15.0 | Apache-2.0 | 家长端 WebRTC |
| TypeScript | 5.8 | Apache-2.0 | 类型检查，构建期 |
| Vite | 7.0.0 | MIT | 构建与开发服务器 |
| @vitejs/plugin-react | 4.5.0 | MIT | React 构建插件 |
| @types/react / @types/react-dom | 19.1.0 | MIT | 类型声明，构建期 |

`package-lock.json` 是实际版本和传递依赖的锁定依据；发布构建必须使用锁文件，不得使用未审计的浮动版本。

## 5. 发布前许可证门禁

1. 在锁定依赖上导出 Android、服务端和 PWA 的完整依赖树。
2. 生成 SPDX 或 CycloneDX SBOM，并与本文档的直接依赖表交叉检查。
3. 检查新增的传递依赖、原生库、字体、图标、示例图片和测试素材是否有独立许可证。
4. 保留 Apache-2.0 的 `LICENSE`、`NOTICE` 和必要归属信息，不抹除第三方版权声明。
5. 对 EPL/LGPL、双许可证、Public Domain 和任何 copyleft 组件做书面法务判断。
6. 将最终 SBOM、审查日期、审查人、例外清单和发布包哈希归档到发布证据。

可用的人工检查入口：

```powershell
cd android
.\gradlew.bat :app:dependencies --configuration releaseRuntimeClasspath

cd ..\server
.\gradlew.bat dependencies --configuration runtimeClasspath

cd ..\admin-web
npm ls --all
```

这些命令只提供依赖视图，不会自动完成许可证判断。当前仓库没有安装 SBOM 插件，也没有法务签字记录；该项状态应继续标为**未完成**。
