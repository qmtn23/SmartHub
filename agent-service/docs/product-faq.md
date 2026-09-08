# 商品 FAQ Agent 与证据工作流

## 能力与边界

`answer_product_faq` 是售前 Master 可调用的受限 FAQ Agent-as-Tool。它不是自由回答模型：内部将固定 `ProductFaqWorkflow` 作为商家知识证据工具，并可调用不接收 ID 参数的当前优惠券、当前店铺以及平台优惠券规则工具。商品身份由可信 consultationContext 注入，店铺仍以 shopId 隔离，默认品类为 ShopType；选店由 recommendation_agent 负责。旧 `faq/workflow.py` 仅保留平台工作流单元测试参考，不在生产 Graph 中注册。

Agent 内部流程：问题分解 → 按需调用商家 FAQ 证据工作流、当前优惠券/店铺或平台规则 → 证据引用校验 → `submit_faq_result` 结构化提交。商家 FAQ 证据工作流始终使用 Master 委派的原始任务，Agent 只能声明 requiredFacets，不能替换检索问题。固定流程保持确定性边界：上下文校验 → 同商家/同商品范围混合召回 → 结构化证据选择 → 冲突检查 → 有界 Query 改写与补检索 → 有效版本复核 → 导购规则匹配。最多两轮召回、两次证据选择模型调用；所有内部业务调用共用全局工具预算。服务失败返回可重试错误，不自动转人工。

没有有效商家 FAQ 时，FAQ 证据和导购建议都为空。商品价格、库存、销售有效期由 FAQ Agent 调用上下文绑定的 Java 实时工具，不能靠 FAQ 推断。规则模板只能在相关 FAQ 已命中后触发；同主题存在不同答案时保守拒答，不自行推定哪个条目覆盖哪个条目。

客户端应将 FAQ 和建议作为纯文本显示，不能执行/渲染商家正文中的 HTML 或指令。引用合法不等于语义正确，需真实问答评测。

## 部署顺序

1. 先备份数据库，在已完成 phase5 迁移的库上执行 `src/main/resources/db/product-faq.sql` **一次**。新增表可重复创建，但 ALTER TABLE 不可重复执行。
2. 由运维向 `tb_shop_manager(shop_id,user_id)` 绑定实际授权关系。没有默认商家账号，没有自助认领接口；撤销关系立即阻止后续管理请求。
3. Java 与索引 worker 注入同一个独立的 `FAQ_INDEX_SERVICE_KEY`（至少 32 字符）。不得与 AGENT_SERVICE_API_KEY 或 AGENT_TOOL_JWT_SECRET 复用。Python 不配置 MySQL 账号。
4. 保留原平台知识索引，并执行 `python -m app.rag.merchant_worker --setup-only`，创建 `smarthub_merchant_faq`。也可使用仓库根 Compose 的 merchant-faq-indexer 服务执行该命令。
5. 部署 Java，启动 `docker compose -f compose.agent.yml --profile merchant-faq up -d merchant-faq-indexer`。Java 业务服务需可从 worker 访问；默认 host.docker.internal:8081。
6. 商家新建并发布 FAQ，检查详情接口 indexJobs 为 DONE，active_revision 为预期版本，enabled 为 1。
7. 部署新版 Agent Service，`/health/ready` 同时检查 Redis、平台 collection、商家 collection 及模型/服务认证配置。

不在服务启动时自动执行数据库迁移或批量重建向量。回滚应用时保留新增列与表；停用 worker 即可暂停新发布的索引处理。原平台 collection 不受影响。

## 商家管理 API

使用现有用户登录凭证，路径 `/merchant/shops/{shopId}/faqs`。

| 方法/路径后缀 | 用途 |
|---|---|
| GET 空路径，page=1 | 分页列表，每页 50 |
| GET /{faqId} | 草稿、活动版本、发布审计及索引状态 |
| POST 空路径 | 创建草稿，返回 faqId/revision |
| PUT /{faqId} | 新建不可变版本，必须带 expectedRevision |
| POST /{faqId}/publish | 请求索引并发布，正文 `{"revision":1}` |
| POST /{faqId}/disable | 停用，正文 `{"revision":1}`；不物理删除历史 |

创建示例（替换为当前商家真实商品 ID）：

```json
{
  "scope": "PRODUCT",
  "voucherId": 7,
  "question": "需要预约吗？",
  "aliases": ["要提前预约吗"],
  "answer": "请提前一天联系商家预约。",
  "topic": "RESERVATION",
  "validFrom": "2026-09-01T00:00:00",
  "validUntil": "2027-09-01T00:00:00"
}
```

时间按 Asia/Shanghai 解释。scope 为 PRODUCT/CATEGORY/SHOP；CATEGORY 要传 categoryId，SHOP 不传商品/品类 ID。主题为 RESERVATION、USAGE_DATE、SUITABILITY、CONTENTS、RESTRICTIONS、GENERAL。问题 200 字、答案 2000 字，相似问法最多 10 条、每条 200 字；不接受客户端指定 shopId/userId/发布状态等隐藏字段。价格、库存禁止作为 FAQ 维护的实时事实；发布前商家应核对正文。

无登录 401、无店铺权限 403、版本冲突 409、非法字段 400。PUT 不修改历史正文，失败索引不会替换已发布版本。停用后编辑成新版本再发布。

## 商品上下文与结果

现有聊天请求增加可选 `consultationContext`：

```json
{"shopId":1,"voucherId":7,"categoryId":1}
```

Java 根据 ID 查真实商品，校验店铺与品类关系并补齐事实；客户端不能提交价格、用户身份或知识过滤表达式。省略该字段时恢复当前短会话最近用户消息的上下文；传 `{}` 显式清空。传新店铺而不传商品不会继承旧商品。没有唯一商品而问“这个券”时必须澄清。

校验后的上下文保存在用户消息 consultation_context 中，与 AgentRun 创建共用原短事务。重试读取同一消息快照。请求签发独立 `faq:read`、`voucher:read` 和 `shop:read` JWT。当前商品/店铺端点根据签名中的用户、会话、消息 ID 重新读取并解析对应上下文，模型不传业务 ID；FAQ Agent 不获得订单、退款、转人工或写操作凭证。JWT 不进 Graph state、checkpoint、模型消息或 DTO toString 日志。

响应与历史消息保存 `structuredContent.productFaq`，主要字段包括 status、questionType、answerPoints、canonicalAnswerPoints、evidence、missingInformation、clarificationQuestion、conflicts、rejectedEvidenceRefs、queryVariants、workflowVersion 和 ruleVersion，并保留 faqMatches、shoppingAdvice 兼容字段。status 为 ANSWERED、PARTIAL、NEEDS_CLARIFICATION、NO_EVIDENCE、CONFLICT、OUT_OF_SCOPE 或 FAILED；每条 evidence 带 sourceType、sourceRef、revision、content 和 liveData。模型提交的 sourceRef 不在本轮真实工具结果中时会被剔除，回答状态会按需降级为 NO_EVIDENCE。

同轮包含普通查询时，`structuredContent.businessResults` 保存业务工具的规范结果。统一回复生成后还有确定性守卫：纯 FAQ 的无证据、冲突、澄清结论直接采用系统保守文案；混合任务会补入该结论；商家 FAQ 的 canonicalAnswerPoints 被遗漏时会原样补回。前端需要适配该可选字段才能展示完整卡片；本次不包含管理页面或客户端卡片 UI。

## 索引、失效和幂等

MySQL 发布状态与规范问答是真相源；Milvus 只存 FAQ 版本向量。管理事务写 Outbox；worker 领取有 120 秒租约的事件，按 FAQ ID/版本/checksum 幂等 upsert，确认新版本可读后回调 Java CAS 激活。最多领取十次，失败可在修复服务后重新调用 publish 重试；过期租约和重复回调不可激活已停用/过时版本。

召回在店铺/商品范围、有效时间、Java 快照中的已发布版本内过滤。稠密召回 12 条、同范围词法扫描最多 500 条，本地 BM25 融合排序；不是 Milvus 原生全文检索。融合分数、向量分数、词法分数和召回通道会回传给证据工作流，再叠加 PRODUCT/CATEGORY/SHOP 范围优先级进行业务重排。融合分数 0.55 只作候选门槛，不是答案正确概率。Java 规范校验后最多选择四条，输出前再次校验版本。

后台只清理严格早于活动/待发布版本的旧向量；最新停用向量暂留，避免与再发布竞争，但 MySQL 校验立即阻止新请求引用它。历史消息和已成功的同一请求幂等结果不随停用被篡改，新消息使用新发布状态。

专家缓存键包含 workflow/rule 版本、商品上下文及知识版本指纹，不复用旧平台 FAQ 结果。不共享跨用户答案缓存。

## 测试与评测

本地无外部依赖测试：在仓库根运行 `agent-service/.venv/Scripts/python.exe -m pytest agent-service/tests -q`；Java 运行客服、FAQ、权限与工具相关单元测试。真实 MySQL 事务隔离与 Milvus 索引回调仍需非生产环境验收。

`evals/product_faq_cases.json` 包含 **120 条合成标注用例**，覆盖两家商户、使用说明、预约、人数、限制、无依据、上下文缺失及注入；不是 120 条线上真实用户数据。配置模型密钥后，在 agent-service 目录运行：

```shell
python -m app.evals.product_faq_eval
```

默认仅评测工作流和证据选择，使用固定证据，不测 Milvus 召回。真实检索评测需在独立测试环境发布 FAQ、准备真实 ID 标注集，再以 `--dataset real-cases.json --chat-url <现有发送消息URL> --im-chat-id <测试长会话> --chat-id <测试短会话>` 运行，并设置 CUSTOMER_SESSION_TOKEN。此模式会保存测试聊天消息；禁止针对生产会话运行。脚本拒绝直接将内置合成 ID 数据发送到在线聊天接口。

目标为 FAQ Recall@4 ≥90%、规则适用判断准确率 ≥95%，无依据建议与跨商家引用为零。未运行真实评测前不得宣称达到目标；脚本中规范答案一致性检查也不能代替语义正确性人工评审。
