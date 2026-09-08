# 用户画像层

用户画像记录用户明确表达的长期稳定偏好，与 L2 业务任务记忆独立保存。当前实现只支持 CONFIRMED 偏好，不根据消费记录、对话频率或 Agent 推荐自动推断画像。

## 启用

1. 在业务 MySQL 中依次执行 `src/main/resources/db/task-memory.sql` 和 `src/main/resources/db/user-profile.sql`。两份脚本均为新增表，不会迁移或删除原始消息。
2. 为 Spring Boot 设置 `CUSTOMER_MEMORY_ENABLED=true` 和 `CUSTOMER_PROFILE_ENABLED=true`，然后重启服务。两个开关默认关闭；关闭画像开关不影响已启用的任务记忆。
3. 为 Python 服务设置 `DASHSCOPE_PROFILE_MODEL`，默认 `qwen-plus`，使用已有的模型凭据和 `AGENT_SERVICE_API_KEY`，然后重启或重新构建服务。画像模型可与任务记忆模型分别配置。

画像功能依赖新提交的 L2 记忆事件。启用画像以前已经处理完的消息不会自动重新挖掘；也不会将旧文本摘要直接转换为用户画像。

## 数据流

`TaskMemoryStore.complete()` 在同一个数据库事务中提交任务记忆 CAS、消息处理凭据和画像 Outbox 事件。只有本批实际处理的 USER 消息 ID 会进入画像事件，事件按 `user_id + memory_version` 唯一。

`UserProfileWorker` 使用独立后台线程处理事件，不在对话请求中调用画像模型。普通事件默认等待 60 秒以便合并，同一批最多合并同一用户的 3 个事件。“以后、记住、忘记、清除”等明确指令在 L2 提交后不额外等待合并窗口；这些关键词只影响调度，不直接决定画像内容。

Worker 从 MySQL 重新读取事件对应的原始用户消息，并校验任务记忆处理凭据。相关任务只作为辅助背景。Python 的 `/v1/customer-service/memory/profile-diff` 接口需要服务密钥，并输出受 Schema 约束的字段增量。

Java 再次验证字段白名单、值长度、引用消息归属和逐字原话；通过后进行字段合并，使用画像版本 CAS 原子提交画像及事件完成状态。版本冲突重新读取画像并重新提取，最多即时重试 3 次；其他失败按事件记录退避重试，默认最多领取 5 次。租约过期后可重新领取，模型调用期间不持有数据库事务。

## 字段范围

| 字段 | 含义 |
| --- | --- |
| cuisinePreference | 明确表达的长期菜系偏好 |
| tastePreference | 口味偏好 |
| dietaryPreference | 饮食偏好，不提取健康诊断 |
| budgetPreference | 平时适用的人均预算或消费范围 |
| areaPreference | 常去城市、商圈 |
| servicePreference | 长期预约、排队等服务要求 |
| environmentPreference | 安静程度等环境偏好 |
| communicationPreference | 回复详略、比较和表达方式 |

每个字段最多 6 个值，每个值不超过 120 字符。身份信息、画像置信度、生命周期和版本不能由模型写入。暂不支持 INFERRED 画像。

“今天预算 300 元”“这次帮朋友找川菜馆”等一次性或他人需求留在任务层；“平时聚餐人均 100 到 150 元”才属于长期预算候选。语义判断由提取提示词约束，服务端额外强制验证来源原话，不能将来源验证视为语义正确性的保证。

## 更新与遗忘

模型仅可输出 SET 或 CLEAR。同一批同一字段最多一个操作，无变化的字段不输出；SET 表示该字段完整的新值列表。

每个字段保存 CONFIRMED/CLEARED 状态、用户消息引用、原话证据、观察时间及消息 ID。已确认值默认从原始消息时间起生效 90 天，过期后不会注入 Agent，且无新证据不能刷新有效期。

CLEAR 删除字段值并保留清除屏障。字段更新按原始消息时间及消息 ID 比较，较旧事件和重复事件不能覆盖较新的值或清除屏障。清除屏障不随偏好 TTL 删除，防止延迟事件恢复已清除的偏好。它只表示停止使用该画像字段，不会删除原始聊天记录或独立任务记忆。

自然语言更新异步生效。收到用户明确停止使用某偏好的要求后，Agent 本轮应直接遵守当前要求，不得声称后台删除已经完成。

## Agent 读取

画像存储按 `user_id + PLATFORM + 0` 隔离，身份从服务端已验证的用户消息取得。Java 在发起 Agent 请求前读取并过滤到期字段，以独立 `userProfile` 传递。读取失败或开关关闭时注入空画像，正常对话继续。

Master 使用当前场景相关的画像；推荐 Agent 使用餐饮、预算、区域等偏好；FAQ Agent 只接收适配相关字段；售后、投诉和终稿生成器只使用沟通偏好。上下文有字节预算，不包含原话证据全文。画像不会参与场景权限判断，也不能作为商品规则、退款资格或实时业务状态的凭据。

使用优先级固定为：当前用户明确要求 > 当前任务约束 > 已确认画像。为他人咨询时不套用本人的偏好。

## 配置与运维

Spring Boot 的 `customer-profile` 配置包括 `enabled`、`poll-interval-ms`、`confirmed-ttl-days`、`batch-events`、`coalesce-seconds`、`max-attempts`。后台事件状态为 PENDING、RUNNING、DONE、FAILED；画像提取并发为每个 Java 实例 1 个批次，Python 画像提取通道并发为 1，并包含排队在内的 45 秒超时。

排查更新延迟时查看 `tb_customer_profile_job` 的状态、attempts、error_code、lease_until，以及用户画像 version。修复模型或服务配置后，可按明确的用户和记忆版本重置 FAILED 事件为 PENDING、attempts=0、next_attempt_at=NOW()；不要直接篡改画像版本或删除清除屏障。重试只引用事件已绑定的源消息。

部署必须先完成数据库迁移和 Python 新接口上线，再启用 Java 开关。画像事件写入失败会使该批 L2 事务回滚，避免产生无法补偿的记忆与画像事件缺口；此时正常对话仍使用原始消息和已保存的上下文。
