# Guard 家长监护模式

[![Guard CI](https://github.com/MagicPwn/guard-product/actions/workflows/ci.yml/badge.svg)](https://github.com/MagicPwn/guard-product/actions/workflows/ci.yml)

Guard 是一个面向 Android 专用设备的家长监护系统，由孩子端 DPC、Ktor 服务端和 React 家长 PWA 组成。它复用成熟开源组件，不通过 root、漏洞或无障碍服务绕过 Android 安全边界。

## 已实现

- Device Owner、设备策略、应用白名单/阻断/始终允许、每日额度、时段控制和重启恢复。
- 前台监控、持久任务、失败关闭、健康状态和审计。
- 签名策略发布、设备验签、revision 防回滚、可查看历史并以新 revision 重新签名回滚、设备密钥证明和令牌自动续期。
- 应用清单同步、多应用额度/阻断/始终允许编辑、分类批量设置和一次性临时放行（可主动撤销）。
- 孩子端本地额度预警和连续使用提醒：家长可配置剩余分钟阈值与连续前台分钟阈值，提醒按应用每日一次、按前台会话一次去重，可点击查看说明；始终允许、始终阻断和系统保护应用不提醒。
- 签名题库、四类题型（单选/多选/判断/简答）、图文题、多题连续作答、按学科/年级/难度/语言/标签筛选、版本防回滚、一步回滚、答题奖励、每日次数与冷却。
- 四类自动触发（累计用量、进入应用、连续使用、时段开始）和家长即时手动触发、持久答题请求、全屏答题页、高优先级通知、答错冷却和每日上限校验。
- 额度耗尽时由前台监控为当前受限应用拉起全屏、可退出的任务页，显示触发原因、冷却/每日上限/奖励倒计时、求助说明和签名策略内的紧急联系人；关闭页面后应用仍保持阻断，同一策略版本同一天不会重复弹出。
- 答题结果离线 outbox、按 `attempt_id` 幂等上传；家长通知的创建、设备轮询、可见详情和确认审计。
- 家长 PWA 本地制作/编辑/签名题库、完整包或签名 Delta 发布、签名包导入、服务端验签托管与启停、孩子端自动拉取和失败回退完整包，以及最多 8 把题库公钥的显式信任集。
- 题库签名钥轮换与撤销：旧钥签署 `quiz-key-transition`，设备只接受本地已信任旧钥发起的转移；服务端拒绝撤销当前 active 题库所用签名钥。
- 家长级可信上游 URL 配置、完整 envelope 或 Delta bundle 拉取、手动立即同步、条件 GET、版本防回退、失败保留 active 题库和同步事件记录。
- 孩子主动求助：离线持久排队、`client_request_id` 幂等重试、家长端回复、孩子下一次轮询收到回复并自动确认已读；求助不会解锁应用或改变时间限制。
- React 家长 PWA、设备配对、策略、用量、审计、通话和截图页面。
- LiveKit 语音/视频/屏幕共享，孩子端通知 Accept/Decline 后才建立媒体；任一方结束会话时服务端删除 LiveKit 房间并停止后续 token 签发。
- 孩子端明确同意的单次截图：每次触发 Android `MediaProjection` 授权，只上传一帧 JPEG，不上传录屏；家长可随时结束请求，孩子端轮询到结束后立即禁止继续捕获或上传，服务端 15 分钟后删除。
- 家长会话撤销：每次登录创建服务端会话并写入 JWT `sid`，Account 页面可撤销单个/其他浏览器会话，Sign out 也会撤销当前会话；撤销后旧 JWT 立即失效。
- 管理员紧急访问与恢复台：二次 PIN、默认 15 分钟（上限 30 分钟）的可见紧急窗口、到期/重启/时钟异常自动恢复，以及逐步释放并回读确认的 Device Owner 解除。
- Docker Compose + Caddy + PostgreSQL + LiveKit 自托管部署模板，服务端数据库由 Flyway 版本化迁移。
- 家长账户及家庭数据删除：PWA 危险操作页要求精确邮箱、当前密码和 `DELETE`，服务端在单个事务中删除账户、设备、策略、用量、审计、题库、求助、通知和截图等数据；删除后旧家长/设备 token 与重新登录均失效。

## 当前边界

- 尚未在真实 Device Owner 设备、摄像头、麦克风和屏幕共享环境完成端到端验收。
- 仓库提供正式 applicationId、外部 keystore、更新通道和 `verifyProductionRelease` 门禁，但不包含任何正式签名密钥或证书；发布前仍需由组织配置并保管。
- 上游 Delta bundle 必须同时携带可恢复的完整目标 envelope；上游地址仍需要部署层 egress allowlist 和 DNS 变更监控配合，应用层校验不能单独消除 DNS rebinding 风险。
- 家长即时手动触发已打通服务端任务单、设备轮询、本地复核、答题页和完成审计；设备仍会执行冷却、每日次数、题库可用性和包范围校验。
- 自动触发、额度耗尽任务页、全屏答题页、通知和离线补传目前只在 Android 模拟器完成 E2E。Android 12–15、OEM 后台启动限制、full-screen intent 和锁屏状态仍需真实设备矩阵验证。
- 额度预警和连续使用提醒同样需要在真实 Android 12–15 设备上验证通知权限、勿扰模式、后台限制和重启后的去重行为。
- 孩子主动求助消息使用普通 `SharedPreferences` 保存本地队列和回复缓存；若威胁模型要求消息静态加密，应在发布前接入受保护存储或系统密钥库。
- 截图和媒体能力必须保留孩子端可见授权，不能作为隐蔽监控使用。
- 临时放行的主动撤销在下一次设备成功同步时生效；设备离线时本地授权仍受原到期时间和签名策略约束。
- 紧急访问只临时释放 Guard 管理的应用限制，不关闭系统策略、维护模式或 kiosk；解除 Device Owner 也不会自动抹除设备数据，必要时仍需恢复出厂。
- `android-lockdown-dpc` 基线、许可证和依赖许可见 `android/LICENSE`、`android/NOTICE`。
- 账户删除已通过 JVM 与本地 Docker PostgreSQL 端到端，但只覆盖在线数据库事务；备份/WAL/PITR 保留期和删除传播仍需运维演练，孩子设备本地缓存策略与队列不会被服务端删除自动抹除。详见隐私数据流和数据删除演练文档。

## 文档

- [安装与部署](docs/INSTALLATION.zh-CN.md)
- [技术方案](docs/TECHNICAL_SOLUTION.zh-CN.md)
- [开发目标](docs/DEVELOPMENT_GOALS.zh-CN.md)
- [第三方组件与许可证](docs/THIRD_PARTY_LICENSES.zh-CN.md)
- [隐私数据流](docs/PRIVACY_DATA_FLOW.zh-CN.md)
- [威胁模型](docs/THREAT_MODEL.zh-CN.md)
- [渗透测试计划](docs/PENETRATION_TEST.zh-CN.md)
- [数据删除演练](docs/DATA_DELETION_DRILL.zh-CN.md)
- [设备恢复演练](docs/DEVICE_RECOVERY_DRILL.zh-CN.md)
- [发布证据](docs/RELEASE_EVIDENCE.zh-CN.md)

## 持续集成与构建产物

`.github/workflows/ci.yml` 在 `main` 推送、Pull Request 和手动触发时执行完整流水线：

- Android：680+ JVM 单测、`lintRelease`、Debug APK、Release APK 和 `verifyChannelDisabledRelease`。
- 服务端：全量 JVM 测试、`distZip` 发行包和可运行的 `installDist` 目录。
- 家长 PWA：Vitest 单测、TypeScript 类型检查和 Vite 生产构建。
- 功能 E2E：用 Docker Compose 启动真实 PostgreSQL 与 server 容器，覆盖认证、配对、签名策略、回滚、设备证明、用量、通知、求助、手动答题、截图同意、租户隔离和账户删除。

成功构建后可从对应 Actions run 下载 `guard-android-debug-apk`、`guard-android-release-apk`、`guard-server-distribution`、`guard-admin-web` 和 `guard-functional-e2e-report`。

## 快速构建

```powershell
cd android
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat testDebugUnitTest assembleDebug
```

```powershell
cd server
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat test
```

```powershell
cd admin-web
npm ci
npm test
npm run build
```

功能 E2E（Linux 或已安装 Docker Compose 的环境）：

```bash
cd deploy
sh ./scripts/generate-secrets.sh
GUARD_WEB_DOMAIN=guard.example.test GUARD_LIVEKIT_DOMAIN=livekit.example.test \
  docker compose -f docker-compose.yml -f docker-compose.e2e.yml up -d --build postgres server
cd ..
python -m pip install -r tests/functional/requirements.txt
GUARD_BASE_URL=http://127.0.0.1:18080 python -m pytest tests/functional -vv
cd deploy
GUARD_WEB_DOMAIN=guard.example.test GUARD_LIVEKIT_DOMAIN=livekit.example.test \
  docker compose -f docker-compose.yml -f docker-compose.e2e.yml down -v
```

生产部署前请先生成部署密钥、设置域名、构建带服务端 URL 和策略公钥的 APK，并按安装文档完成真机验证。

正式包必须使用独立 init 脚本、外部 keystore 和更新通道密钥，并运行验证门禁；普通 `assembleRelease` 仅用于兼容已 provisioning 的 pilot 设备，不能作为生产包发布：

```powershell
cd android
.\gradlew.bat -I gradle\production.init.gradle.kts verifyProductionRelease
```
