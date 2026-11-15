# Guard 发布证据与验收状态

更新日期：2026-11-13

本文档把“代码/JVM/模拟器已验证”和“真实硬件/正式密钥/生产安全已验收”严格分开。没有证据的项目不得改成通过。

## 1. 当前构建证据

| 项目 | 命令 | 状态 | 证据 |
| --- | --- | --- | --- |
| 服务端全量 JVM 测试 | `server\gradlew.bat test --console=plain` | **通过** | Gradle `BUILD SUCCESSFUL` |
| Android 单测 + Debug APK | `:app:testDebugUnitTest :app:assembleDebug` | **通过** | Gradle `BUILD SUCCESSFUL` |
| 家长 PWA 类型检查 + 构建 | `npm run build` | **通过**，仅有既有 chunk 体积警告 | Vite `dist/` |
| Docker Compose 配置 | `docker compose config --quiet` | **通过** | 无输出，退出码 0 |
| 服务端/家长端镜像重建 | `docker compose build server web` | **通过** | `guard-server`、`guard-web` 镜像生成 |
| 健康检查 | `/healthz`、`/api/v1/health` | **通过** | `{"status":"ok","database":true,"version":"0.1.0"}` |
| 本地 PostgreSQL 账户删除 E2E | Docker Compose + 真实 Postgres | **通过** | 删除后账户/设备/用量/审计计数 `0,0,0,0`，登录与旧 token 均 401 |
| 本地 PostgreSQL V8 迁移 | Docker Compose + 真实 Postgres | **通过** | Flyway 显示 V0 baseline 和 V1–V8 全部 `success=true`，`temporary_grants` 的 `revoked_at/revoked_by` 可用 |
| 本地 PostgreSQL 家长会话撤销 E2E | Docker Compose + 真实 Postgres | **通过** | 两个 active 会话；撤销其他会话后其 JWT 为 401，当前会话 200；账户清理 204，重新登录 401 |
| 本地 PostgreSQL 策略历史回滚 E2E | Docker Compose + 真实 Postgres | **通过** | 发布 revision 1/2 后，历史为 `2,1`；回滚 revision 1 生成 revision 3，恢复额度 60 分钟，`policy.rolled_back` 审计 1 条，账户清理 204 |
| 本地 PostgreSQL 临时放行撤销 E2E | Docker Compose + 真实 Postgres | **通过** | 跨家长撤销 404；撤销后 pending 为 0、apply 返回 409、设备撤销列表包含该 grant、家长列表显示 revoked、`grant.revoked` 审计 1 条；账户清理 204，重新登录 401 |
| 本地 PostgreSQL 数据保留任务 E2E | Docker Compose + 真实 Postgres | **通过** | 过期截图行 0、过期 pending 通话变为 `expired`、31 天前撤销的家长会话行 0 |
| 本地 PostgreSQL + LiveKit 会话结束 E2E | Docker Compose + 官方 LiveKit Server SDK | **通过（无真实 WebRTC 参与者）** | call 变为 `ended`，双方再次请求 token 均为 409，审计含 `call.ended`，LiveKit 返回的房间不存在 404 被按幂等成功处理 |
| 家长会话撤销 JVM 覆盖 | `server\gradlew.bat test` | **通过** | 单会话、当前会话、批量撤销、跨家长隔离和撤销后 401 |
| 策略历史回滚 JVM 覆盖 | `server\gradlew.bat test` | **通过** | 历史读取、历史 payload 重新校验签名、revision 递增、`policy.rolled_back` 审计 |
| 临时放行撤销 JVM 覆盖 | `server\gradlew.bat test` | **通过** | pending/applied 撤销、幂等重试、跨家长隔离、撤销列表投递、撤销后 apply 409、单次审计 |
| 数据保留清理 JVM 覆盖 | `server\gradlew.bat test` | **通过** | 过期截图、过期 pending 通话状态和 30 天前失效家长会话元数据由后台任务清理 |

构建通过只证明当前代码可编译、自动化断言通过，不证明真实 Device Owner、OEM 后台限制、摄像头、麦克风、LiveKit 或生产密钥可用。

## 2. 自动化覆盖摘要

- 家长注册/登录、密码错误和重复邮箱。
- 设备注册、配对码一次性、设备证明、nonce 防重放和 token 吊销。
- 设备所有权隔离、用量、审计、应用清单、临时放行创建/应用/撤销、通知、通话和截图。
- 签名策略、revision 防回滚、题库完整包/Delta、密钥轮换/撤销、上游同步。
- 手动答题、结果幂等、求助、已读和通知状态。
- 额度预警/连续使用提醒策略字段校验。
- 账户删除：错误密码/邮箱/确认词、跨表删除、token 失效、登录失败、另一账户隔离。
- 通话结束：未认证/跨家长拒绝、pending 禁止结束、accepted 原子结束、双方 token 失效、孩子端主动结束、LiveKit 删除失败可重试、官方 SDK Twirp 路径/房间名/鉴权与 404 幂等。
- 家长会话：列出、撤销单个、撤销当前、批量撤销其他、跨账号 404、撤销后旧 JWT 401。
- 策略历史：读取 revision 列表、回滚历史 payload、生成新的递增 revision、未知 revision 404、回滚审计。
- 数据保留：截图过期清理、pending 通话过期状态、家长会话元数据保留清理。

自动化覆盖不等于独立渗透测试，也不覆盖真实浏览器/真机交互和部署配置。

## 3. 真实硬件与 OEM 状态

| 项目 | 状态 | 说明 |
| --- | --- | --- |
| Device Owner provisioning | **未执行** | 需真实设备记录固件构建号 |
| Android 12–15 设备矩阵 | **未执行** | 所有单元格保持 `—` |
| 两家以上 OEM 后台限制 | **未执行** | 含省电、自启、通知和锁屏行为 |
| kiosk/导航逃逸 | **未执行** | 真机逐项验证 |
| 摄像头/麦克风 | **未执行** | 真实权限、回声、后台切换 |
| LiveKit 房间/TURN | **未执行** | 真实网络和双端房间 |
| MediaProjection 单帧截图 | 未在真实设备验收 | 模拟器/代码路径不等于硬件证据 |
| 紧急访问到期/重启 | **未执行** | 需专用可恢复设备 |
| 解除 Device Owner/恢复出厂/重新 provisioning | **未执行** | 见设备恢复演练 |
| 正式 keystore/applicationId/更新通道 | **未执行** | 仓库不含正式密钥或证书 |
| 生产数据库备份恢复 | **未执行** | 需隔离环境演练 |
| 账户删除生产演练 | **未执行** | JVM 与本地 Docker PostgreSQL E2E 已通过；备份传播和生产运维未演练 |
| 渗透测试 | **未执行** | 见渗透测试计划 |
| SBOM/法务许可证审查 | **未完成** | 见第三方组件清单 |

设备证据的权威清单是 `android/docs/device-matrix.md`。该文件当前明确写明所有单元格均为未测试，不能从代码支持推断兼容性。

## 4. 发布门禁

- [ ] 服务端、Android、PWA 全量验证在最终提交上通过。
- [ ] Docker 镜像重建并运行健康检查。
- [ ] 真实 Device Owner 设备完成 provisioning、重启、升级、回退、低电量和恢复出厂。
- [ ] Android 12–15 和至少两家 OEM 完成矩阵并归档固件构建号。
- [ ] 摄像头、麦克风、屏幕共享、LiveKit/TURN 和截图完成真实双端验收。
- [ ] 紧急访问、Device Owner 解除和重新 provisioning 演练通过。
- [ ] 正式 keystore、applicationId、更新通道和 `verifyProductionRelease` 门禁通过。
- [ ] 账户删除和备份保留期完成预发布演练。
- [ ] 渗透测试 Critical/High 清零或有正式风险接受。
- [ ] SBOM、依赖漏洞扫描、许可证审查和隐私政策完成。
- [ ] 所有未执行项在发布说明中披露，不宣称“生产验收通过”。

## 5. 最终验证记录

| 字段 | 值 |
| --- | --- |
| 日期 | 2026-11-13 |
| Android Git 基线 | `5a8e2b7`（工作区有未提交改动） |
| 根目录版本控制 | 非 Git 工作区 |
| 服务端测试 | 通过（全量 45 项） |
| Android 测试/APK | 通过 |
| PWA 构建 | 通过 |
| Docker 镜像 | `server`、`web` 构建通过 |
| 健康检查 | 通过，数据库 healthy |
| PostgreSQL 删除 E2E | 通过，目标家庭计数清零，旧 token/登录 401 |
| PostgreSQL 会话撤销 E2E | 通过，撤销目标会话 JWT 401、当前会话 200、账户清理 204、重新登录 401 |
| PostgreSQL 策略历史回滚 E2E | 通过，revision 1/2 → 回滚生成 3，历史 payload 恢复，审计存在，临时账户清理 204 |
| PostgreSQL 临时放行撤销 E2E | 通过，pending 0、apply 409、撤销列表命中、家长列表 revoked、审计 1 条、账户清理 204、重新登录 401 |
| PostgreSQL 数据保留 E2E | 通过，过期截图 0、pending 通话 expired、31 天前撤销会话 0 |
| PostgreSQL + LiveKit 结束 E2E | 通过，状态 `ended`，双方 token 409，审计存在；未执行真实双端 WebRTC 房间 |
| 发布结论 | 代码级候选可继续验证；真实硬件、正式密钥和生产安全未验收 |

## 6. 证据归档模板

每次发布候选至少归档：源码版本/工作区哈希、依赖锁文件、APK/PWA/服务端镜像哈希、测试报告、设备矩阵记录、固件构建号、截图/日志、问题单、风险接受、渗透测试报告、SBOM、许可证审查、删除演练和恢复演练记录。
