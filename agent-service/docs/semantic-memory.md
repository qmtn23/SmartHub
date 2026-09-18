# 任务生命周期记忆

本文对应 `semantic-memory.sql` 和 `SEMANTIC_MEMORY_ENABLED` 的增量实现。Java 负责身份、原始记录、结构化记忆及事务；Python 负责上下文编排、检索、模型提取和向量计算。默认关闭新功能，兼容已有工作记忆和画像接口。

## 任务开始：加载画像与检索背景

`hydrate_context` 在路由和业务执行前调用 `SemanticMemory.bootstrap`，以当前请求及可信商品上下文作为查询。Java 校验独立的 `memory:self:read` JWT，返回当前用户的有效画像、最近历史条目及实体精确匹配条目。用户身份只用于服务内部过滤，不作为模型工具参数。

历史召回组合三路：Milvus 用户过滤后的语义搜索、MySQL 最近 50 条历史的关键词排序、订单/商品/店铺数字 ID 精确匹配。向量召回最多 30 条，结果回查 MySQL 的归属、有效期、状态和版本。过期、删除或版本落后的向量不能进入上下文。最终最多返回 5 条，正文及证据引用共享 9000 字节预算。公共 FAQ 与用户记忆使用不同集合。

向量搜索失败降级为关键词和实体检索；启动读取超时或不可用时保留请求中原有的画像、任务摘要及近期消息。状态记录在 `memory_retrieval_status`，异常日志只包含错误类型。读取身份失败不会执行无用户过滤的向量搜索。

模型输入先保留固定系统规则，再传递标记为不可信背景的画像、历史记忆、近期对话及本次任务目标。当前明确要求优先于任务约束，任务约束优先于历史偏好；历史事实不能证明实时价格、库存或退款状态。

## 任务执行：持久事件与有限上下文

LangGraph State 维护消息、任务目标、领域任务结果与业务引用。独立的 `MemoryAuditHandler` 通过继承回调，记录包括 Router、Master、领域 Agent、FAQ 工作流、回复生成器在内的模型输入/输出与工具结果。回调记录写入 Java MySQL 的 `tb_customer_memory_event`，不记录运行上下文中的访问令牌。

事件以 `run_id + event_type + payload` 的 SHA-256 摘要作为幂等键。大事件拆分为带 `call_id/part/parts/json_fragment` 的片段；按 part 顺序拼接即可恢复完整 JSON。工具异常记录失败类型，不能被解释为成功结果。启用归档后，事件落库失败会使执行报错，不能先清空状态再忽略归档失败。

`MemoryContextMiddleware` 只改变单次模型调用所见的消息，不改写持久消息列表。默认使用 48000 UTF-8 字节预算作为保守的 token 成本代理，扣除系统消息。超过预算时保留任务输入、完整配对的最近工具调用与响应，并加入旧工具回执摘要。过大的最近工具响应以明确标记的片段传入，完整结果仍在事件表；无法安全装配时明确报错，避免产生孤立工具响应或损坏 JSON。

Master、推荐、售后和投诉 Agent 可调用 `search_memory(query, top_k=5)`，每个运行共享最多 3 次检索。完整检索结果先放在当前执行对象中，工具消息只保存 `result_ref` 和记忆 ID/版本。中间件在该 Agent 的下一次模型调用中展开正文；调用成功后移除正文。后续需要时重新检索，已经得出的结论应携带来源交给下游。该工具不能决定身份或写入记忆，也不改变业务工具权限及额度。

## 任务结束：可靠写回与释放

Java 保存成功回复或恢复后的操作回复时，在同一事务中调用已有 `TaskMemoryStore.enqueue`。会话结束、工作窗口裁剪及后台未处理消息发现机制继续作为补充触发。一次运行结束不等于退款等业务任务已解决，`ACTIVE/RESOLVED/ABANDONED` 仍根据有证据的任务进展提取。

后台任务依次执行：

1. 从 MySQL 读取尚未处理的原始消息；补充同一用户、会话和源消息相关的工具事件。
2. Python 生成字段增量，包含事实、约束、未解决问题及 `decisions`（选择、已说明的原因与范围）。
3. Java 校验源消息、工具事件与字段范围，在新版本上确定性合并。
4. 同一 CAS 事务中提交近期任务摘要、消息凭据、画像任务、长期条目及向量索引任务。
5. 独立索引 Worker 异步生成 embedding，更新 Milvus，失败按指数退避重试。

用于提取的工具证据只选业务/知识只读工具，排除 Agent 总结、回复生成器和查记忆工具。当前每批最多 12 条、每条仅使用完整的单片事件且序列化内容不超过 8000 字符；更大的事件完整归档，但不会自动用于长期提取。模型引用工具事实需提供 `sourceEventIds`，Java 再次校验事件来源；该引用校验不等于模型语义判断一定正确。

长期条目按任务组织，包含完整结构化内容、字段证据、源时间、版本、内容摘要和有效期。它们在近期工作集裁剪之前归档，所以任务从最多 8 条工作集退出后仍可搜索。重复内容不升版本；历史条目默认从最新来源时间起保留 90 天，不因读取续期。不是把每句话独立做 embedding，也不会把未确认的模型建议当作用户偏好。

`RUN_FINALIZED` 事件确认落库后，图节点清空当前运行的 messages 与召回正文。等待确认的中断不会到达此节点，Checkpoint 继续保存。恢复请求携带新签发的记忆令牌；缺少令牌的旧客户端保留原 Checkpoint TTL 清理行为。较早的 Checkpoint 仍按现有 TTL 过期，原始消息、业务审计及记忆来源不会随活跃消息列表清空而删除。

## 画像更新与异步可见性

画像白名单扩展为 10 项，增加 `languagePreference` 和 `codingPreference`。例如“以后函数都加类型注解”可形成明确编码偏好；某项目的一次技术选型留在任务决策中。

语义记忆开启后，Java 可附带最近 7 天未被画像任务处理、包含明确偏好/遗忘提示词的用户原话，作为 `pendingStatements`。原话保留来源和时间，与画像共享上下文预算；模型只采纳其中明确的长期变更，不能把一次性要求推广到新任务。画像任务完成时在同一事务提交处理凭据，已完成旧任务中的消息也会被排除，避免旧偏好再次进入待处理视图。提示词匹配用于候选选择，不代表已经完成语义提取或数据库更新。

画像的逐字证据、来源时间排序、TTL、CLEAR 屏障和 CAS 继续生效。清除画像字段不会自动删除独立任务历史。长期条目的 `SemanticMemoryStore.forget(userId, ids)` 是应用服务操作，按当前用户校验后提交删除屏障和索引事件，不注册为模型工具。

## 启用与迁移

1. 执行已有 `task-memory.sql`、`user-profile.sql`，然后执行 `src/main/resources/db/semantic-memory.sql`。新脚本仅新增表。
2. 部署新 Java 和 Python 代码，先保持双方语义记忆开关关闭。
3. 在已配置 Milvus 和 embedding 凭据的 Python 服务环境中运行 `python -m app.semantic_memory` 初始化集合。不要在普通聊天请求中建集合。
4. Java 开启 `CUSTOMER_MEMORY_ENABLED=true`、`CUSTOMER_PROFILE_ENABLED=true`、`CUSTOMER_SEMANTIC_MEMORY_ENABLED=true`。
5. Python 开启 `SEMANTIC_MEMORY_ENABLED=true`。双方就绪前保持流量关闭或开关关闭；Python 索引接口在关闭时返回 503，Java 索引任务可重试。

后台以每批最多 10 个用户回填现有结构化任务摘要，使用 `tb_customer_memory_backfill` 记录进度，不从旧聊天重新推断偏好。已被旧系统裁剪的任务无法由这次摘要回填恢复。

Milvus 集合默认 `smarthub_customer_memory_v1`，向量主键为 `memory_id:version`，延迟任务无法覆盖新版本。索引带 embedding 模型标识；更换模型或维度应使用新集合、重新创建索引任务并完成回填，再切换读取。MySQL 始终是可重建索引的权威来源。

配置入口：Java `customer-semantic-memory`（默认关闭，保留 90 天、最多 5 次索引尝试）；Python `.env.example` 中的 `SEMANTIC_MEMORY_ENABLED`、`MEMORY_COLLECTION`、检索超时和上下文预算。

## 运维与当前验证范围

关注 `tb_customer_memory_job`、`tb_customer_profile_job` 和 `tb_customer_memory_index_job` 的 FAILED、attempts、lease_until、error_code。修复依赖后按明确任务重置 FAILED 为 PENDING；不要删消息凭据、画像清除屏障或长期记忆 tombstone。过期长期条目会提交删除索引事件。归档事件暂不自动删除，后续配置保留策略时应同时检查长期记忆来源引用。

本次没有新增或修改测试代码。数据库迁移、Milvus 初始化以及真实模型端到端验收需要在具备对应服务和凭据的环境执行；本地编译和既有测试结果不能替代这部分验收。
