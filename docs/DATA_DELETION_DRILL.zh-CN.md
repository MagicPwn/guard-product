# Guard 数据删除演练

更新日期：2026-11-13

## 1. 状态

- 自动化 JVM 测试：**已通过**。覆盖错误认证、错误邮箱、错误确认词、跨表删除、token 失效、登录失败、另一账户隔离和设备数据保留。
- 本地 Docker Compose + 真实 PostgreSQL 端到端：**已通过**。创建测试家长和设备、写入用量和审计后删除，目标账户/设备/用量/审计计数为 `0,0,0,0`，旧家长 token、旧设备 token 和重新登录均为 401。
- 生产或预发布环境真实数据库演练：**未执行**。
- 备份/WAL/PITR 删除传播演练：**未执行**。

自动化测试证明当前代码路径符合测试断言，不等于真实部署、备份和运维流程已经验收。

## 2. 接口语义

`POST /api/v1/me/delete`

```json
{
  "email": "parent@example.test",
  "password": "current password",
  "confirmation": "DELETE"
}
```

- 需要有效家长 JWT，且账户当前仍存在。
- 邮箱规范化后必须等于当前账户邮箱。
- 密码必须重新通过 PBKDF2 校验。
- 确认词必须精确为 `DELETE`，不接受大小写变体。
- 限流为每账户少量尝试；错误统一返回 `403 Account deletion confirmation failed`，不泄露其他账户信息。
- 成功返回 `204 No Content`。
- 删除在单个数据库事务中完成；失败回滚，不留下部分删除。

## 3. 删除范围

事务按依赖顺序删除：

1. 目标家长所有设备的截图、答题结果、通知、临时放行、应用清单、通话请求、审计、用量、策略历史、手动答题任务和求助。
2. 目标家长剩余的求助和手动答题任务。
3. 题库密钥转移、上游事件、上游配置和题库包。
4. 目标家长的设备。
5. 家长账户。

未配对设备的 `parent_id` 为 `NULL`，不属于任何家长，不能被本流程误删。另一家长的设备、策略、用量、审计、题库和求助必须保持不变。

## 4. 预发布演练步骤

使用独立测试数据库和两个测试家庭 A、B，禁止使用真实家庭数据。

1. 为 A、B 注册账户、配对至少一台设备，并创建策略、用量、审计、题库、密钥转移、上游、求助、通知、截图、通话请求和临时放行数据。
2. 记录 A、B 的账户 ID、设备 ID、各表行数和设备 token。
3. 对 A 分别尝试未认证、错误密码、错误邮箱和 `delete` 确认词，确认均失败且数据行数不变。
4. 对 A 使用正确邮箱、密码和 `DELETE` 调用删除，确认 `204`。
5. 立即验证 A 的 `/api/v1/me`、家长 token、设备 token 和重新登录全部失败。
6. 逐表验证 A 的关联行数为 0，B 的行数和设备访问保持正常。
7. 检查服务端日志、数据库事务日志和监控，确认无敏感正文或 secrets。
8. 检查备份/WAL/PITR 保留策略：删除请求是否传播、旧备份何时过期、恢复旧备份是否会复活数据。
9. 若设备仍在手，验证其缓存策略和本地队列行为；服务端删除不会自动抹除设备本地数据。
10. 归档执行人、时间、版本、数据库快照标识、查询结果、回滚/风险接受结论。

## 5. 行数核验模板

对目标设备 `device_id` 和账户 `parent_id` 执行只读查询：

```sql
SELECT COUNT(*) FROM screenshots WHERE device_id = :device_id;
SELECT COUNT(*) FROM quiz_attempt_results WHERE device_id = :device_id;
SELECT COUNT(*) FROM device_notices WHERE device_id = :device_id;
SELECT COUNT(*) FROM temporary_grants WHERE device_id = :device_id;
SELECT COUNT(*) FROM device_apps WHERE device_id = :device_id;
SELECT COUNT(*) FROM call_requests WHERE device_id = :device_id;
SELECT COUNT(*) FROM audit_events WHERE device_id = :device_id;
SELECT COUNT(*) FROM usage_reports WHERE device_id = :device_id;
SELECT COUNT(*) FROM policy_revisions WHERE device_id = :device_id;
SELECT COUNT(*) FROM manual_quiz_triggers WHERE device_id = :device_id;
SELECT COUNT(*) FROM help_requests WHERE device_id = :device_id;
SELECT COUNT(*) FROM quiz_packs WHERE parent_id = :parent_id;
SELECT COUNT(*) FROM quiz_key_transitions WHERE parent_id = :parent_id;
SELECT COUNT(*) FROM quiz_upstream_sources WHERE parent_id = :parent_id;
SELECT COUNT(*) FROM quiz_upstream_events WHERE parent_id = :parent_id;
SELECT COUNT(*) FROM devices WHERE parent_id = :parent_id;
SELECT COUNT(*) FROM parents WHERE id = :parent_id;
```

预期：目标家庭全部为 0；对照家庭仍大于 0。

## 6. 证据表

| 项目 | 记录 |
| --- | --- |
| 演练日期 | 待填 |
| 环境/版本 | 待填 |
| 服务端镜像哈希 | 待填 |
| 数据库备份标识 | 待填 |
| 自动化测试结果 | 已通过（JVM + 本地 Docker PostgreSQL E2E） |
| A 删除响应 | 待填 |
| A 数据行数 | 待填 |
| B 隔离结果 | 待填 |
| token/登录失效 | 待填 |
| 备份删除传播 | 待填 |
| 设备本地数据行为 | 待填 |
| 执行人/见证人 | 待填 |
| 结论 | 待填：通过 / 有条件通过 / 不通过 |

## 7. 已知边界

- 删除事务不包含外部备份、对象存储快照、代理日志或监控系统；这些必须在运维层单独设置保留期和删除传播。
- 服务端删除账户不会远程擦除孩子设备，也不会自动清除本地缓存策略、答题 outbox 或求助队列。丢失/退回设备应执行受管擦除或恢复出厂。
- 删除不可撤销。生产演练前必须确认使用的是测试账户和可丢弃数据库。
