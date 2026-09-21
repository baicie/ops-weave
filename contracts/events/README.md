# Event contracts

共享信封在 `../schemas/v1/event-envelope.schema.json`。业务事件新增独立 data Schema；不要只验证信封。

分区键建议 `tenantId + aggregateId`。消息默认至少一次交付；跨库副作用需要幂等。重放必须携带 replay 标志并由消费者策略阻断副作用，不是信任发布者不触发。
