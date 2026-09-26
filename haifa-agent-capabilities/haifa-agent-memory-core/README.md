# Haifa Agent Memory Core

框架中立实现：

- `DefaultMemoryService`：写入前做 owner 授权与 `SensitiveMemoryFilter` 硬过滤（凭据、支付卡/CVV、精确证件号），
  ID 与时间由注入端口生成；`put` 以 scope + kind + subject 为身份，同内容重复写入不增加 revision，不同内容替换正文；
  `update`/`delete` 使用 revision CAS，已提交的重试更新返回当前状态；
- `DefaultMemoryRetriever`：只读取请求的 USER/AGENT/SESSION 桶，确定性关键词 + 新近度排序，最多 8 条并受 Token
  预算约束，不使用向量检索；
- `InMemoryMemoryStore`：测试与本地装配用的线程安全 `MemoryRepository`，语义与 SQLite 实现一致
  （删除保留无正文的墓碑，清空记录 scope 水位线）。

捕获时机与提取策略属于产品层，不在本模块内。
