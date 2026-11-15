# Guard 安装与部署

本文说明如何构建 Android 孩子端、启动 Ktor 服务端、部署家长 PWA 与 LiveKit，并完成首次设备配对。当前仓库已达到可构建、可运行的开发/试点状态，但尚未完成真实 Device Owner 设备矩阵和媒体端到端验收。不要把本文当作已经完成生产认证的承诺。

## 1. 组件与端口

| 组件 | 目录 | 默认端口 | 说明 |
| --- | --- | --- | --- |
| 孩子端 Android | `android/` | 无 | Device Owner DPC、策略执行、通知、LiveKit、同意式截图 |
| 服务端 | `server/` | `8080` | Ktor、JWT、策略签名、应用清单、临时放行、截图、LiveKit 令牌 |
| 家长 PWA | `admin-web/` | `80` 容器内 | React 19 + Vite，生产由 nginx/Caddy 提供 |
| PostgreSQL | Compose | `5432` 容器内 | 账户、设备、策略、用量、审计、授权、截图 |
| LiveKit | Compose | `7880` 容器内、`7881/tcp`、`50000-50100/udp` | 语音、视频、屏幕共享 |
| Caddy | Compose | `80/443` | 自动 TLS、域名路由、反向代理 |

## 2. 前置条件

- Docker Engine 24+ 与 Docker Compose v2。
- 两个 DNS 记录：家长端域名、LiveKit 域名，均指向部署主机。
- 主机防火墙放行 `80/tcp`、`443/tcp`、`443/udp`、`7881/tcp`、`50000-50100/udp`。  
- Android Studio、JDK 17、Android SDK Platform 36、Build Tools、平台工具。
- Node.js 20+（本地构建 PWA 需要；Docker 构建已自带）。
- OpenSSL（生成策略密钥和部署 secrets）。
- 一台可恢复出厂的专用 Android 测试设备。不要在第一台个人主力手机上注册 Device Owner。

## 3. 生成部署密钥

Linux/macOS：

```bash
cd deploy
chmod +x scripts/generate-secrets.sh
./scripts/generate-secrets.sh
```

Windows PowerShell：

```powershell
cd C:\Users\pwn\Projects\guard-product\deploy
.\scripts\generate-secrets.ps1
```

脚本在 `deploy/secrets/` 生成：

- `policy_private.pem`：策略签名私钥，绝不能提交或发给设备。
- `policy_public.pem`：策略验签公钥。
- `jwt_secret`、`postgres_password`、LiveKit API key/secret。

脚本会输出 `Policy key id` 和 `Policy public key`，构建 APK 时需要固定这两个值。`deploy/secrets/` 已被 gitignore；仍需单独做好离线备份和权限控制。

## 4. Docker Compose 部署

### 4.1 配置域名

```bash
cd deploy
cp .env.example .env
```

编辑 `.env`：

```dotenv
GUARD_WEB_DOMAIN=guard.example.com
GUARD_LIVEKIT_DOMAIN=livekit.example.com
```

生产环境必须使用真实 HTTPS 域名。服务端已强制 APK 使用 HTTPS，仅 localhost/`10.0.2.2` 开发地址允许 HTTP。家长 PWA 的上传等写请求依赖同源 CORS；Compose 会从 `GUARD_WEB_DOMAIN` 生成允许源。未设置域名的本地 Compose 会自动回退到 `http://localhost:5173` 与 `https://localhost`，生产环境不要依赖该回退。

### 4.2 检查并启动

```bash
docker compose config --quiet
docker compose up -d --build
docker compose ps
docker compose logs -f server
```

Caddy 会为两个域名申请证书。DNS、端口或证书未就绪时，先查看：

```bash
docker compose logs -f caddy
curl -fsS https://guard.example.com/healthz
```

健康响应应包含 `"status":"ok"` 和 `"database":true`。

### 4.3 Flyway 数据库迁移

服务端启动时由 Flyway 自动执行 `server/src/main/resources/db/migration/` 下的迁移脚本。既有 Guard 数据库首次升级时会创建 `flyway_schema_history`，baseline 到 V0，再按顺序应用 V1–V8；全新数据库直接从 V1 开始。迁移失败时服务端不会带着旧 schema 继续启动。

升级前先备份数据库，然后只重建服务端容器：

```bash
docker compose build server
docker compose up -d server
docker compose logs --tail=100 server
```

日志应出现 `Successfully applied ... migration`，并最终进入 `Responding at http://0.0.0.0:8080`。随后确认历史表和关键业务表：

```bash
docker compose exec -T postgres psql -U guard -d guard -c \
  "select installed_rank, version, description, success from flyway_schema_history order by installed_rank;"
```

应看到 V0 `BASELINE success=t`，以及 V1–V8 的 `SQL success=t`。V4 创建求助请求表，V5 增加题库 Delta 字段和密钥转移表，V6 增加通话结束/房间撤销时间，V7 创建可撤销的家长浏览器会话表，V8 增加临时放行的撤销时间和操作者。升级到 V7 后，不含 `sid` 的旧家长 JWT 会被拒绝，家长需要重新登录；设备 token 不受影响。不要通过删除 PostgreSQL 数据卷来“修复”迁移；先保留日志和备份，再按迁移脚本处理。

### 4.4 启动后验证

```bash
curl -fsS https://guard.example.com/api/v1/config/policy-key
```

响应中的 `key_id` 和 `public_key` 必须与 `deploy/secrets/` 中的策略密钥一致。家长 PWA 地址为 `https://guard.example.com`。

## 5. 本地开发服务端

本地开发可以使用 H2 或本机 PostgreSQL。不要在开发模式中处理真实家庭数据。

```powershell
cd C:\Users\pwn\Projects\guard-product\server
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:GUARD_DEV_MODE='true'
.\gradlew.bat test
.\gradlew.bat run
```

开发模式若没有显式提供策略密钥，会在每次启动时生成临时密钥，设备下次同步会被 key pin 拒绝。需要稳定联调时，设置：

```powershell
$env:GUARD_POLICY_PRIVATE_KEY_PEM_FILE='C:\path\policy_private.pem'
$env:GUARD_POLICY_PUBLIC_KEY_PEM_FILE='C:\path\policy_public.pem'
$env:GUARD_JWT_SECRET='至少 32 个字符的本地测试密钥，不要用于生产'
```

服务端支持敏感环境变量的 `*_FILE` 形式，Docker/Kubernetes 挂载 secret 时优先使用文件形式：

- `GUARD_JWT_SECRET_FILE`
- `GUARD_DATABASE_PASSWORD_FILE`
- `GUARD_POLICY_PRIVATE_KEY_PEM_FILE`
- `GUARD_POLICY_PUBLIC_KEY_PEM_FILE`
- `GUARD_LIVEKIT_API_SECRET_FILE`

`GUARD_LIVEKIT_URL` 是返回给浏览器和 Android 的媒体 WebSocket 地址（例如 `wss://livekit.example.com`）。服务端删除房间时使用 `GUARD_LIVEKIT_API_URL` 直连 LiveKit HTTP/Twirp 接口；Docker Compose 默认设为 `http://livekit:7880`。若单独部署，必须保证服务端能访问该内部地址，且 API key/secret 与 LiveKit 实例一致。

## 6. 构建并运行家长 PWA

开发模式：

```powershell
cd C:\Users\pwn\Projects\guard-product\admin-web
npm install
npm run dev
```

生产构建：

```powershell
npm run build
```

构建产物在 `admin-web/dist/`。Docker Compose 中的 `web` 服务会执行相同的构建并由 nginx 提供静态文件。API 和媒体客户端已做按需加载；首次进入设备页时再加载 LiveKit 代码。

若 PWA 与服务端不同源部署，构建时设置 `VITE_API_URL`；同域反代部署不需要设置。

## 7. 构建 Android APK

### 7.1 普通 Debug 构建

```powershell
cd C:\Users\pwn\Projects\guard-product\android
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat testDebugUnitTest assembleDebug
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`。

如果 AGP 将 debug APK 标记为 test-only，安装时使用：

```powershell
adb install -r -t android/app/build/outputs/apk/debug/app-debug.apk
```

### 7.2 固定服务端与策略公钥

推荐在试点构建中固定服务端 URL、key id 和公钥，避免首次注册时被错误服务器替换：

```powershell
.\gradlew.bat assembleDebug `
  -PguardServerUrl=https://guard.example.com `
  -PguardPolicyKeyId=替换为生成的key_id `
  -PguardPolicyPublicKey=替换为生成的base64公钥
```

也可以使用环境变量：

```powershell
$env:GUARD_SERVER_URL='https://guard.example.com'
$env:GUARD_POLICY_KEY_ID='...'
$env:GUARD_POLICY_PUBLIC_KEY='...'
.\gradlew.bat assembleDebug
```

### 7.3 Pilot 与生产构建

普通 debug/release 构建保留 `com.example.lockdowndpc` 和 pilot 兼容签名，适用于已 provisioning 的测试设备与模拟器，不是正式客户发布包。正式构建必须显式使用 `gradle/production.init.gradle.kts`，它会切换到 `il.co.shalommaman.deviceguard`、要求外部 keystore、更新 manifest HTTPS URL、P-256 元数据公钥、公钥 SHA-256 和 APK 签名证书 SHA-256；缺少任一项会直接失败。

正式构建所需变量：

```text
DEVICE_GUARD_STORE_FILE
DEVICE_GUARD_STORE_PASSWORD
DEVICE_GUARD_KEY_ALIAS
DEVICE_GUARD_KEY_PASSWORD
DEVICE_GUARD_UPDATE_MANIFEST_URL
DEVICE_GUARD_UPDATE_PUBLIC_KEY
DEVICE_GUARD_UPDATE_PUBLIC_KEY_SHA256
DEVICE_GUARD_APK_SIGNER_SHA256
```

`DEVICE_GUARD_UPDATE_PUBLIC_KEY` 是更新 manifest 验签公钥的 X.509 DER Base64；`DEVICE_GUARD_UPDATE_PUBLIC_KEY_SHA256` 是 DER 字节的 SHA-256；`DEVICE_GUARD_APK_SIGNER_SHA256` 是 APK 签名证书 DER 的 SHA-256。密钥只放在 CI secret store、受控环境变量或未提交的 Gradle 属性中，不能写入仓库。

```powershell
cd C:\Users\pwn\Projects\guard-product\android
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat -I gradle\production.init.gradle.kts verifyProductionRelease
```

该任务会先构建 release，再用 `android/tools/verify_pilot_apk.py` 验证正式包名、versionCode/versionName、单一签名证书、批准证书指纹、编译进 APK 的更新 URL 和元数据公钥。任一不符即失败，不能人工忽略后继续发布。

### 7.4 模拟器本地 HTTPS 联调

本地模拟器可以通过 `adb reverse` 访问宿主机 Caddy，不需要把开发服务暴露到局域网。debug 网络配置只额外信任 `android/app/src/debug/res/raw/guard_dev_root_ca.pem` 中固定的 Caddy 本地根证书；release 构建不包含该信任。

1. 从当前 Caddy 容器导出本地根证书：

```powershell
$caddy = (docker compose -f .\deploy\docker-compose.yml ps -q caddy).Trim()
$rootCert = '{0}:/data/caddy/pki/authorities/local/root.crt' -f $caddy
docker cp $rootCert .\android\app\src\debug\res\raw\guard_dev_root_ca.pem
```

2. 重新构建并安装 debug APK。服务端地址可以预先写入，也可以在 Guard 设置页填写：

```powershell
$env:GUARD_SERVER_URL='https://localhost:8443'
cd .\android
.\gradlew.bat assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

3. 建立到宿主机 Caddy `443` 端口的反向代理，并在孩子端设置页保存同一地址：

```powershell
adb reverse tcp:8443 tcp:443
```

孩子端服务端地址填写 `https://localhost:8443`。模拟器重启或 ADB 重连后，`adb reverse` 通常需要重新执行；可用 `adb reverse --list` 检查。

不要把该 CA 或 debug 网络配置复制到 release/生产设备。生产构建必须使用受信任的公共证书。若删除或重建 Caddy 数据卷，本地根证书会变化，需要重新执行第 1、2 步并重新安装 APK；不要使用 `-k`、抓包代理或全局信任绕过 TLS。

## 8. Device Owner Provisioning

### 8.1 ADB 测试设备

仅适用于空白、可恢复出厂的测试设备：

```powershell
adb uninstall com.example.lockdowndpc
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell dpm set-device-owner com.example.lockdowndpc/.admin.LockdownAdminReceiver
adb shell dpm list-owners
```

如果设备已有 Device Owner、工作资料、账户或锁屏凭据，先按设备厂商流程恢复出厂，不要覆盖现有管理配置。

### 8.2 Android Enterprise QR

正式试点应使用 Android Enterprise QR provisioning、零接触注册或厂商企业注册。需要：

- 正式签名 APK；
- 受信任 HTTPS 下载地址；
- 设备恢复出厂后的 provisioning 流程；
- 在至少两台真实设备上验证下载、Device Owner handover、首个策略和重启恢复。

正式包名的 Device Owner 组件为 `il.co.shalommaman.deviceguard/com.example.lockdowndpc.admin.LockdownAdminReceiver`。Pilot 包名的组件仍为 `com.example.lockdowndpc/.admin.LockdownAdminReceiver`，两者应用身份不同，不能原地互相升级。

### 8.3 紧急访问与解除 Device Owner

管理台的“紧急访问与恢复”页面提供两个受控出口。两者都只能从已解锁的管理员会话进入，并会再次要求管理员 PIN。用一次性恢复码解锁管理台时会写入恢复码使用审计（`audit_recovery_used` 资源文案），随后必须设置新的管理员 PIN。

紧急访问的预期行为：

- 默认窗口为 15 分钟，启动前显示警告，启动后通知栏持续显示结束时间；窗口上限为 30 分钟，管理台不会通过重复点击叠加或延长。
- 窗口内临时释放 Guard 管理的应用 suspension、关闭 Guard HOME 并暂停答题触发，但不会关闭系统策略、维护模式或 kiosk。它不是隐藏监控入口，也不会获取额外 Android 权限。
- 可从页面提前结束，或等待 `AlarmManager`/前台监控自动恢复。重启设备、墙上时钟回拨超过 2 分钟、单调时钟倒退或无法读取本次开机身份时，窗口立即失效并重新执行普通策略。
- 测试时检查本地审计出现 `guard-emergency-started:15m`，以及 `guard-emergency-ended:administrator`、`guard-emergency-ended:expired` 或 `guard-emergency-ended:rebooted` 等结束原因；通知栏应恢复普通 Guard 提示。

解除 Device Owner 前必须满足：

- 维护模式已关闭，且没有待恢复的维护策略；
- kiosk 已退出，设备不处于 containment 状态；
- 当前管理员会话和二次 PIN 均验证通过；
- 已接受“重新纳入管理需要重新 provisioning”的后果。

解除流程会先释放并回读 Guard 管理的全部 suspension，再暂停受管策略并释放系统控制，最后才调用 `clearDeviceOwnerApp()`。任一步回读失败都会保留 Device Owner，管理台显示失败原因；成功后写入 `device-owner-released` 审计。可用以下命令确认：

```powershell
adb shell dpm list-owners
```

解除成功后输出中不应再有 `com.example.lockdowndpc`。解除 **不等于** 删除照片、账户、应用数据或服务端记录；如果要交付一台干净设备，仍应按厂商流程恢复出厂。需要重新管理时，按 8.1 或 8.2 重新 provisioning。

建议先在可恢复出厂的专用测试设备上验证三条拒绝路径：活跃 kiosk、活跃维护窗口、Guard suspension 释放失败。只有这些路径都按预期保留 Device Owner，才执行一次完整的成功解除并重新 provisioning。

ADB registration 不是面向家长的正式安装方式。

## 9. 首次配对

1. 在家长 PWA 注册家长账户。
2. 孩子端打开 Guard 设置，填写 HTTPS 服务端地址并保存。
3. 点击“Register this device”，记录显示的 8 位配对码。
4. 在家长 PWA 输入配对码。
5. 孩子端点击“Sync now”，设备会确认配对、上传应用清单并拉取签名策略。
6. 首次注册时固定服务端策略公钥；之后服务端 key 变更必须由管理员重新确认，不能静默接受。
7. 家长端选择应用，设置每日分钟数、始终阻断、始终允许或时段，然后发布签名策略。
8. 孩子端同步后执行策略；只有 Device Owner、Usage Access、策略校验和执行回读都通过时，健康状态才应显示正常。

## 10. 通话、屏幕共享与截图

- 家长发起请求后，孩子端会收到可见通知。
- 孩子必须点击 Accept；服务端只有 accepted 状态才签发 LiveKit token。
- 家长或孩子点击 End 都会调用服务端结束接口。服务端立即禁止新 token，并调用 LiveKit `DeleteRoom` 撤销房间；删除失败会返回可重试错误，不能仅凭本地断开就视为已结束。
- 语音、视频、屏幕共享均需孩子端系统权限；屏幕共享还要 Android 的 MediaProjection 授权。
- 单次截图需要孩子再次点击同意按钮并批准系统截屏对话框；服务端只接受一帧 JPEG，不写孩子端本地文件，15 分钟后从服务端删除。
- 不记录媒体内容，不提供后台静默采集。未授权时不能启动摄像头、麦克风或屏幕采集。

## 10.1 孩子主动求助

1. 孩子在额度耗尽任务页或答题页打开“求助”，可填写最长 500 字符留言；页面会说明求助不会解锁应用，也不会改变时间限制。
2. 在线时请求立即出现在家长端设备详情的 “Child help requests”；离线或服务端不可达时孩子端显示“已排队”，恢复网络后由同步任务按 `client_request_id` 幂等重试。
3. 家长回复后，孩子端在下一次轮询或同步时收到 `answered` 状态和回复文本；打开回复后自动确认，服务端记录已读和审计。
4. 同一设备在 60 秒内提交相同原因、包名和留言时服务端会去重，避免重复提示。

## 11. 数据库与备份

```bash
docker compose exec -T postgres pg_dump -U guard guard > guard-backup.sql
```

恢复前先停止服务、验证备份和目标数据库，再按组织的数据删除/恢复流程执行。策略私钥、JWT secret、数据库密码和 LiveKit secret 必须分别备份；不要把私钥和数据库备份放在同一位置。

### 11.1 家长账户与数据删除

1. 家长在 PWA 的 `Account` 页面输入账户邮箱、当前密码和精确确认词 `DELETE`，提交 `POST /api/v1/me/delete`。
2. 服务端重新校验 JWT、账户存在性、邮箱、PBKDF2 密码和确认词；错误统一返回 `403`，不会泄露其他账户信息。
3. 删除在单个事务中清理目标家庭的设备、策略、用量、审计、应用清单、临时放行、通话、截图、通知、题库、密钥转移、上游配置、求助和手动答题任务，再删除设备和家长。
4. 成功返回 `204`；旧家长 token、旧设备 token、重新登录和 `/api/v1/me` 均失效，另一家长不受影响。
5. 生产演练必须对照 `docs/DATA_DELETION_DRILL.zh-CN.md` 核验各表行数、账户隔离和备份保留期。删除事务不会自动清除备份/WAL/PITR、代理日志或孩子设备本地缓存。

## 12. 验证清单

### 构建

- [ ] `android/app/build/outputs/apk/debug/app-debug.apk` 生成。
- [ ] `android` 单元测试通过。
- [ ] `server` 测试通过。
- [ ] `admin-web` 的 `tsc --noEmit` 与 Vite build 通过。
- [ ] `docker compose config --quiet` 通过。

### 设备

- [ ] Device Owner 已注册，`dpm list-owners` 显示 Guard。
- [ ] Usage Access 已授权，额度和时段生效。
- [ ] 目标应用被 suspend 后，恢复、重启、断网和重新同步都能正确处理。
- [ ] 应用清单能在家长端显示，多应用额度、阻断、始终允许和分类批量操作生效。
- [ ] 始终允许应用可绕过每日额度、允许时段和答题闸门；始终阻断应用仍不可使用，二者不能同时选中。
- [ ] 家长端设置额度预警和连续使用提醒后，受限应用在阈值处出现一次本地通知；点击通知可打开孩子端说明页，且提醒不会改变额度或阻断状态。
- [ ] 同一应用在当天不会重复出现额度预警；同一前台会话不会重复出现连续使用提醒；切换应用、锁屏或重启后会话按设计重置，已发送提醒仍保持去重。
- [ ] 始终允许、始终阻断和系统保护应用不会出现普通使用提醒；紧急访问窗口内不产生普通提醒。
- [ ] 服务端拒绝为未管理、始终阻断或始终允许应用创建临时放行；有效授权只对已管理应用生效，始终阻断名单不可绕过。
- [ ] 家长可撤销 pending 或已应用临时放行；撤销后该授权不再出现在 pending 列表、不能再 apply，孩子端下一次同步移除本地 override，且 `grant.revoked` 审计只写一次。
- [ ] 家长 PWA 的 `Account` 页面在邮箱、密码或 `DELETE` 确认词不匹配时禁用危险按钮；服务端对错误组合返回 `403` 且账户保留。
- [ ] 两个浏览器分别登录同一账户，`Account` 页面列出两个 active 会话；撤销其中一个后，该浏览器下一次 API 请求返回 401，另一个继续可用；撤销当前会话或 Sign out 后当前 JWT 立即失效。
- [ ] 设备策略页显示 revision 历史；选择旧 revision 回滚后生成更高的新 revision，payload 恢复为旧配置，审计出现 `policy.rolled_back`。
- [ ] 使用测试家庭完成账户删除：全部关联表清零、另一家庭不变、旧家长/设备 token 和重新登录失效；备份保留期与设备本地缓存边界已记录。
- [ ] 紧急访问需要二次 PIN；启动后通知栏显示结束时间，提前结束会恢复 Guard suspension 并写入对应审计。
- [ ] 紧急窗口到期、重启或时钟异常后不会继续放行；普通 Guard 策略和通知自动恢复。
- [ ] 活跃 kiosk 或维护窗口存在时，解除 Device Owner 被拒绝且管理权保留。
- [ ] 在可恢复出厂的设备上完成一次完整解除：先释放并回读 suspension/受管策略，再清除 Device Owner，之后能重新 provisioning。
- [ ] 服务端不可用时，本地已验证策略继续执行，不会被放宽。
- [ ] 自动触发策略中的目标应用进入后生成持久答题请求；全屏页和高优先级通知均可打开。
- [ ] 家长端“立即触发答题”创建 `pending` 任务单，孩子在下一次远程轮询（通常 1 分钟内）看到全屏题；完成后服务端状态为 `completed`，结果携带 `quiz-manual-*` 的 `trigger_id`。
- [ ] 对未管理、始终阻断、始终允许、冷却中或达到每日上限的应用发起手动触发时，状态为 `rejected` 且原因可见，不会出现不可用答题页。
- [ ] 当前目标应用额度耗尽时自动出现全屏、可退出的任务页；页面显示额度原因、冷却或每日上限重置倒计时。关闭后应用仍保持阻断，同一策略版本同一天不重复自动弹出。
- [ ] 答错冷却或达到每日答题上限时，自动触发器不会开始新的答题；额度耗尽任务页仍只显示原因和等待时间，不消耗次数。
- [ ] 家长 PWA 可保存最多 3 个任务页紧急联系人；孩子端可见标签与号码，点击只打开系统拨号界面，不自动拨号。未配置联系人或紧急联系人不可用时，页面显示明确的安全提示。
- [ ] 孩子主动求助在线发送后出现在家长端；断网提交显示已排队，恢复网络后只入库一次。家长回复能在孩子下一次轮询后显示，打开回复后状态变为已确认，且求助全程不解锁应用或改变额度。
- [ ] 断网答题结果保留在本地 outbox，恢复网络后上传成功且 `attempt_id` 不重复入库。
- [ ] 家长通知在孩子端可见，打开详情并确认后服务端状态为 `acknowledged`，审计出现 `notice.acknowledged`。
- [ ] 完整题库包可发布并激活；Delta 包在基线版本匹配时只下发 Delta，基线不匹配、Delta 损坏或签名错误时孩子端回退完整包且 active 包不被破坏。
- [ ] 家长端可发起旧钥签署的新钥转移；设备只接受本地已信任旧钥发起的转移。撤销未用于 active 题库的新钥成功，撤销 active 题库签名钥返回 409；未知旧钥、错误签名和错误新钥指纹均被拒绝。
- [ ] 使用“清空信任集和本地签名题库”后，客户端回退到内置题库，且不会继续接受旧签名包。
- [ ] 家长 PWA 可保存公共 HTTPS 上游、固定公钥、15–1440 分钟间隔和启用状态；“保存”“立即同步”“删除配置”均返回明确结果。
- [ ] 上游手动同步在完整 envelope 上得到 `updated-full`，在匹配基线的 Delta bundle 上得到 `updated-delta`；重复内容或 304 得到 `no_update`/`not_modified`；基线不匹配、坏 Delta、独立 Delta 和版本回退得到明确失败事件，且原 active 题库仍可下载和作答。
- [ ] 后台调度在到期后更新 `last_checked_at`，事件列表可看到成功或失败记录；停用后不再自动检查。
- [ ] HTTP、私网、loopback、链路本地、带凭据或 fragment 的上游地址被拒绝，重定向不会被跟随。

### 媒体与截图

- [ ] 未接受请求时设备不会采集。
- [ ] 接受后音频、视频、屏幕共享分别可用。
- [ ] MediaProjection 取消后不产生媒体、不错误显示成功。
- [ ] 截图请求只有孩子明确同意后才上传一帧。
- [ ] accepted 截图请求在家长 PWA 可直接 End；结束后孩子端轮询到 `ended` 并禁止继续捕获/上传。
- [ ] 截图 15 分钟后不可读取，后台保留任务在一个清理周期内删除过期行。
- [ ] 会话结束、令牌过期和进程重启后资源被清理。

### 部署

- [ ] `https://家长域名/healthz` 返回 200。
- [ ] Caddy 证书有效，HTTP 重定向 HTTPS。
- [ ] PostgreSQL 启动，`flyway_schema_history` 显示 V0 baseline 和 V1–V8 迁移成功。
- [ ] 重建服务端后既有家庭、设备、策略、答题结果和通知记录仍存在。
- [ ] 非法 JSON 请求返回 400，而不是 500。
- [ ] LiveKit 两个域名和 UDP 端口可访问。
- [ ] 接受一次测试通话后，家长端或孩子端点击 End，通话状态变为 `ended`，双方再次请求 token 返回 409，LiveKit 房间已删除；若人为让服务端无法访问 LiveKit，结束接口返回 502，恢复后重试可成功。
- [ ] 审计中可看到策略发布、执行、通话/截图授权和失败原因。

## 13. 生产前必须完成

- 配置并离线备份正式 release keystore、APK 签名证书指纹、更新 manifest 密钥和生产 applicationId；只通过 `verifyProductionRelease` 门禁发布，不发布普通 debug-signing release。
- 完成至少两家厂商、Android 12-15 的 Device Owner、重启、升级、回滚、低电量和恢复出厂测试。
- 在专用设备上完成紧急访问到期/重启失效和 Device Owner 解除/重新 provisioning 演练，并保存审计与验收记录。
- 为可信上游配置网络层 egress allowlist、DNS 变更监控和上游内容审核；不能只依赖应用层地址检查来防 SSRF/DNS rebinding。
- 对策略私钥、JWT、数据库、LiveKit secret 建立轮换、吊销和备份机制。
- 完成威胁建模、依赖/许可证审查、数据删除演练和安全测试。
- 评估孩子求助队列和回复缓存的静态加密需求；当前实现使用普通 `SharedPreferences`，未达到高敏感消息静态加密要求时不得宣称已满足该威胁模型。
- 只有真实设备验收通过后，才能对外承诺“已保护”或“生产可用”。
