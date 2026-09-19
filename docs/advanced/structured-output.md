# Structured output

The SDK can request a typed final result by using a bounded Java record as the output contract.

~~~java
public record TripPlan(
        String city,
        int days,
        List<String> activities) {}

var response =
        agent.chat("Plan a two-day trip.", TripPlan.class).await();

TripPlan plan = response.value();
~~~

## What is guaranteed

The record schema is frozen into the Run requirement.

Provider adapters map that requirement to the provider protocol when the selected model declares structured-output capability.

Runtime validates the **final** model answer against the frozen schema and persists the structured result before the SDK decodes the record.

The SDK does not construct the business object from an arbitrary unvalidated JSON string.

## Tool loops

Structured final output does not prevent Tool use.

A model may first return Tool Calls. Those Calls execute through the normal Tool Pipeline. The schema requirement applies when the model eventually produces the final answer.

## Failure behavior

Invalid JSON, schema mismatch, provider rejection, unsupported capability, and output truncation fail explicitly.

There is no typed partial stream. value() is meaningful only for a successful terminal typed response.

## Scope

The convenience API intentionally supports the bounded type set already supported by the shared record schema/codec implementation. It is not a general arbitrary-POJO serializer.
