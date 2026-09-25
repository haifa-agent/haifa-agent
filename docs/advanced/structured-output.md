# Structured Output

SDK 可以把一个有界 Java record 作为某次调用的最终输出契约：

~~~java
public record TripPlan(
        String city,
        int days,
        List<String> activities) {}

var response =
        agent.chat("Plan a two-day trip.", TripPlan.class).await();

TripPlan plan = response.value();
~~~

## 保证什么

Record Schema 会冻结进本次 Run Requirement。

当所选 Model 声明 Structured Output Capability 时，Provider Adapter 会把这份 Requirement 映射到对应 Provider Protocol。

Runtime 会对 **最终** Model Answer 按 Frozen Schema 做校验，并在 Structured Result 持久化成功之后，SDK 才把它解码成 record。

SDK 不会直接从一段任意、未验证的 JSON 文本构造业务对象。

## Tool Loop

Structured Final Output 不会禁止 Tool 使用。

Model 可以先返回 Tool Call，这些 Tool Call 仍通过正常 Tool Pipeline 执行；只有 Model 最终给出 Final Answer 时，才应用最终输出 Schema。

## Failure Behavior

Invalid JSON、Schema mismatch、Provider rejection、Capability unsupported 与 Output truncated 都会显式失败。

当前不存在 typed partial stream；只有成功进入终态的 Typed Response 才能安全调用 value()。

## 支持范围

当前 Convenience API 只支持共享 Record Schema / Codec 已经覆盖的有界类型集合。

它不是任意 POJO Serialization Framework。
