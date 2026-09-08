# 三级记忆架构

SmartHub 客服采用工作记忆、情景记忆和用户画像三层架构。三层数据相互独立，由 Master 统一读取并向领域 Agent 分发相关上下文。

## 工作记忆

当前会话工作记忆使用 Java 业务 Redis 中的 ZSet，键为 `customer:working-memory:v1:{imChatId}`。成员保存消息 ID、短会话 ID、角色、正文和时间，按消息时间排序。

消息在 MySQL 事务提交后进入有界后台线程池，再异步写入 Redis；写入、续期和滚动裁剪由 Lua 脚本原子执行。默认 TTL 为 24 小时，消息超过 30 条时裁剪旧消息并保留最近 20 条。裁剪会触发情景记忆更新任务，原始消息仍保存在 MySQL，因此 Redis 写入失败、队列拥塞或缓存过期不会造成消息丢失。

Agent 请求优先从 Redis 读取同一 `imChatId` 的近期消息。缓存尚未完整预热或 Redis 异常时，从 MySQL 查询最近 20 条并异步回填；独立的 ready 标记防止把部署后形成的局部缓存误认为完整窗口。

Redis Stack 中的 LangGraph Checkpoint 仍负责 Agent 运行状态、中断和恢复，与 Java Redis 中的工作记忆窗口是两个不同用途的数据结构。

## 情景记忆

MySQL 的 `tb_customer_task_memory` 保存跨会话的结构化历史摘要。它以用户的选店、商品咨询、退款和投诉等业务任务组织意图、实体、约束、事实、待解决问题和任务状态，因此代码中称为 Task Memory，架构层称为情景记忆。

情景记忆由后台 Worker 从 MySQL 原始消息增量提取。消息处理凭据避免重复消费，字段来源记录用于追踪证据，版本 CAS 防止并发会话覆盖。已完成和过期任务按生命周期退出 Agent 上下文。实时价格、库存、订单和退款状态仍必须调用业务工具确认。

## 用户画像

MySQL 的 `tb_customer_user_profile` 保存用户明确表达的长期稳定偏好，包括菜系、口味、饮食、常用预算、常去区域、服务、环境和沟通偏好。一次性需求继续属于情景记忆。

画像事件与情景记忆更新在同一事务提交，独立 Worker 异步调用画像模型。SET 和 CLEAR 操作必须引用用户原话，服务端再次校验字段白名单、消息归属和逐字证据；字段支持 TTL、清除屏障和版本 CAS。当前不自动推断用户画像。

## 向量库线程隔离

Milvus 客服知识和商家 FAQ 检索使用同步客户端，但所有在线 `search`、`query`、集合检查以及后台 `upsert`、`delete` 操作都通过专用 `milvus-io` 线程池执行，避免阻塞 FastAPI 的异步事件循环。线程数由 `VECTOR_THREAD_POOL_WORKERS` 配置，默认 4。

## 启用顺序

1. 执行 `src/main/resources/db/task-memory.sql`。
2. 执行 `src/main/resources/db/user-profile.sql`。
3. 部署包含任务记忆和画像接口的 Python Agent 服务。
4. 设置 `CUSTOMER_WORKING_MEMORY_ENABLED=true`。工作记忆默认开启，无需数据库迁移。
5. 设置 `CUSTOMER_MEMORY_ENABLED=true`，再设置 `CUSTOMER_PROFILE_ENABLED=true`。

上线顺序保证 Redis 工作记忆可单独启用；情景记忆或画像异常时，客服回复仍可通过 Redis 窗口和 MySQL 原始消息继续运行。
