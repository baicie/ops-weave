# Cross-language contracts

这里是公开 JSON 契约的唯一手工维护源。`examples/` 全是合成数据。当前契约是起始子集，不是完整领域 Schema。

新增契约必须：Schema 验证、正反例、授权/租户测试、版本兼容评审。未来生成的 Java/Python/TypeScript 客户端放各应用 generated/；禁止手工修改生成代码。

`tools/*.tool.json` 的 implementationStatus 明确为 notImplemented，不代表可以调用真实系统。
