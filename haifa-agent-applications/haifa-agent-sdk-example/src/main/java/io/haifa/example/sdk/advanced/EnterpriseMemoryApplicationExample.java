package io.haifa.example.sdk.advanced;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.memory.api.MemoryKind;
import io.haifa.agent.memory.api.MemorySourceRef;
import io.haifa.agent.memory.api.MemorySourceType;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelAdapterCoordinate;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicyRule;
import io.haifa.agent.policy.api.PolicyRuleMatcher;
import io.haifa.agent.policy.api.PolicyRuleRef;
import io.haifa.agent.policy.api.PolicyRuleSet;
import io.haifa.agent.policy.api.PolicyRuleSource;
import io.haifa.agent.policy.core.DefaultPolicyDecisionService;
import io.haifa.agent.runtime.core.model.continuation.AesGcmModelContinuationProtector;
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.api.HaifaAgents;
import io.haifa.agent.sdk.api.SdkCallerProvider;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.memory.AgentMemories;
import io.haifa.agent.sdk.memory.MemoryException;
import io.haifa.agent.sdk.memory.MemoryListQuery;
import io.haifa.agent.sdk.memory.MemoryScopeSpec;
import io.haifa.agent.sdk.memory.PutMemoryCommand;
import io.haifa.agent.sdk.product.ProductArtifactPolicy;
import io.haifa.agent.sdk.product.ProductId;
import io.haifa.agent.sdk.product.ProductMemoryPolicy;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.product.ProductRunProfileRef;
import io.haifa.agent.sdk.product.ProductVersion;
import io.haifa.agent.store.sqlite.SqliteSdkProductContributions;
import io.haifa.agent.store.sqlite.SqliteStoreConfiguration;
import io.haifa.example.sdk.support.DeterministicExampleSupport;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;

/**
 * Enterprise applications of Haifa Agent Memory:
 * <ul>
 *   <li><b>Scenario 1: Corporate Finance &amp; Expense Compliance</b> — Company-wide audit rules (INSTRUCTION),
 *       city lodging allowance standards (FACT), employee cost-center profile (USER FACT), verified invoice
 *       data (TOOL_OUTCOME), and sensitive payment-card protection.</li>
 *   <li><b>Scenario 2: E-Commerce Multi-Store Campaign Ops</b> — Advertising law compliance (INSTRUCTION),
 *       channel commission and margin floor (FACT), merchant store assignment and tone style (USER PREFERENCE),
 *       and real-time ERP inventory alert (TOOL_OUTCOME).</li>
 * </ul>
 */
public final class EnterpriseMemoryApplicationExample {
    private static final String VERSION = "1.0.0";

    private EnterpriseMemoryApplicationExample() {}

    public static void main(String[] args) throws Exception {
        Path tempDir = Files.createTempDirectory("haifa-enterprise-memory-example");
        try {
            runFinanceComplianceScenario(tempDir);
            System.out.println();
            runEcommerceOperationsScenario(tempDir);
        } finally {
            deleteDirectory(tempDir);
        }
    }

    /**
     * Scenario 1: Corporate Finance &amp; Expense Compliance.
     * Demonstrates strict policy enforcement, employee profile anchoring, tool outcome caching, and card protection.
     */
    private static void runFinanceComplianceScenario(Path tempDir) throws Exception {
        System.out.println("================================================================================");
        System.out.println("  场景一：企业财务费用报销合规助手 (Corporate Finance Expense Compliance)");
        System.out.println("================================================================================");

        String agentId = "finance-compliance-agent";
        AtomicReference<List<ModelMessage>> interceptedMessages = new AtomicReference<>();

        AgentChatModel model = request -> {
            interceptedMessages.set(request.messages());
            return new AgentChatResponse(
                    "finance-response-01",
                    request.model().providerModelId(),
                    "李雷您好，已为您草拟上海出差报销单（成本中心：CC-SALES-EAST-02）：\n"
                            + "⚠️ 合规提醒：上海住宿标准为 600元/晚，您当前房费为 680元/晚（3晚超标共计 240元）。"
                            + "系统已自动勾选【超标特殊审批】并追加抄送直属审批人张总监。发票 03300268 已完成税务核验，确认无误可直接提交。",
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(35, 120),
                    "",
                    Map.of());
        };

        try (HaifaAgent agent = openAgent(tempDir, agentId, "Finance Compliance Copilot", model)) {
            AgentMemories memories = agent.memories().orElseThrow();

            // 1. 企业财务审计红线 (AGENT 作用域 -> INSTRUCTION)
            memories.put(PutMemoryCommand.of(
                    MemoryScopeSpec.agent(agentId),
                    MemoryKind.INSTRUCTION,
                    "expense-audit-rules",
                    "财务初审准则：1. 餐饮及招待费单张发票超过 500 元，必须在说明中提供外部拜访客户全称及我方陪同人员列表；"
                            + "2. 普票抬头必须为本企业全称，且税号必须为 91330100MA2XXXXXX；"
                            + "3. 住宿费超额部分必须勾选特殊审批并追加部门直属审批人。"));
            System.out.println("[已存入企业制度] AGENT INSTRUCTION: expense-audit-rules");

            // 2. 差旅住宿补贴标准事实 (AGENT 作用域 -> FACT)
            memories.put(PutMemoryCommand.of(
                    MemoryScopeSpec.agent(agentId),
                    MemoryKind.FACT,
                    "hotel-allowance-standard-2026",
                    "2026年度出差住宿补贴上限标准：北京、上海、广州、深圳及直辖市 600元/天；新一线城市 450元/天；其他地级市 350元/天。超额部分需直属领导特殊审批。"));
            System.out.println("[已存入差旅标准] AGENT FACT: hotel-allowance-standard-2026 (上海上限 600元/天)");

            // 3. 员工身份与成本中心事实 (USER 作用域 -> FACT)
            memories.put(PutMemoryCommand.of(
                    MemoryScopeSpec.user(),
                    MemoryKind.FACT,
                    "employee-profile",
                    "员工姓名：李雷；所属部门：华东大区大客户销售二部；成本中心代码：CC-SALES-EAST-02；直属审批人：张总监。"));
            System.out.println("[已存入员工画像] USER FACT: employee-profile (成本中心 CC-SALES-EAST-02)");

            // 4. 税务查验 Tool 运行后沉淀事实 (AGENT 作用域 -> TOOL_OUTCOME)
            memories.put(new PutMemoryCommand(
                    MemoryScopeSpec.agent(agentId),
                    MemoryKind.TOOL_OUTCOME,
                    "invoice-verified-03300268",
                    "全国增值税发票查验平台返回：发票代码 03300268 查验为真，销售方为上海浦东嘉里大酒店，金额 2040元，税率 6%。",
                    Optional.of(new MemorySourceRef(MemorySourceType.TOOL_CALL, "ocr-vat-verify-run-882")),
                    Optional.empty()));
            System.out.println("[已存入工具产物] AGENT TOOL_OUTCOME: invoice-verified-03300268 (发票已验真)");

            // 5. 安全防御验证：尝试写入明文员工银行卡号，由 SensitiveMemoryFilter 自动硬拦截
            try {
                memories.put(PutMemoryCommand.of(
                        MemoryScopeSpec.user(),
                        MemoryKind.FACT,
                        "personal-bank-card",
                        "员工私人报销收款银行卡号: 6222021234567890123"));
                throw new IllegalStateException("Sensitive card memory should have been rejected");
            } catch (MemoryException expected) {
                System.out.println("[安全防护拦截] 员工私人银行卡号被 SensitiveMemoryFilter 成功拦截: " + expected.code());
            }

            // 6. 发起用户对话，验证运行时自动唤醒并组装相关记忆到模型上下文
            System.out.println("\n[用户发起报销] 李雷: '我上周在上海出差住了 3 天酒店，每晚 680 元，发票号是 03300268，帮我填张报销单。'");
            var started = agent.conversations()
                    .start(new StartConversationCommand(
                            "finance-turn-1", "Hotel Expense", "我上周在上海出差住了 3 天酒店，每晚 680 元，发票号是 03300268，帮我填张报销单。"));
            var runSnapshot = agent.runs().await(started.runId());

            // 验证模型接收到的 SYSTEM 上下文中包含已注入的记忆块
            List<ModelMessage> messages = interceptedMessages.get();
            long memorySnippetCount = messages.stream()
                    .filter(m -> m.content().startsWith("[memory "))
                    .count();
            System.out.println("[Prompt 注入结果] 运行时自动召回记忆片段数量: " + memorySnippetCount);
            messages.stream()
                    .filter(m -> m.content().startsWith("[memory "))
                    .forEach(m -> System.out.println("   -> " + m.content().replace("\n", " -> ")));

            System.out.println("\n[Agent 答复输出]:\n" + runSnapshot.output().orElseThrow());
        }
    }

    /**
     * Scenario 2: E-Commerce Multi-Store Campaign Ops.
     * Demonstrates brand advertising compliance, platform margin rules, merchant copywriting style, and inventory awareness.
     */
    private static void runEcommerceOperationsScenario(Path tempDir) throws Exception {
        System.out.println("================================================================================");
        System.out.println("  场景二：全渠道电商大促运营助手 (E-Commerce Multi-Store Campaign Ops)");
        System.out.println("================================================================================");

        String agentId = "ecommerce-ops-agent";
        AtomicReference<List<ModelMessage>> interceptedMessages = new AtomicReference<>();

        AgentChatModel model = request -> {
            interceptedMessages.set(request.messages());
            return new AgentChatResponse(
                    "ecommerce-response-01",
                    request.model().providerModelId(),
                    "小美您好，针对天猫旗舰店冲锋衣大促方案与文案如下：\n"
                            + "⚠️ 风控与库存拦截：\n"
                            + "1. 黑色 L 码总仓仅剩 25 件，已在文案中定向置顶【极地军绿】与【雾霾灰】主推款；\n"
                            + "2. 满300减100券后终端价为 219元（毛利率 39.2%），高于 38% 控价红线，准予提报！\n\n"
                            + "📝 预热文案草案：\n"
                            + "🏔️ 奔赴山野，风雨无阻！\n"
                            + "⚡️ 搭载暴雨级科技三合一面料，外层御风拒水，内层锁温蓄热。\n"
                            + "🧥 通勤山系机能剪裁，轻盈无束缚。\n"
                            + "🎁 天猫大促限时礼遇：领券立减 100 元，解锁深秋户外穿搭新色！",
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(40, 160),
                    "",
                    Map.of());
        };

        try (HaifaAgent agent = openAgent(tempDir, agentId, "E-Commerce Operations Copilot", model)) {
            AgentMemories memories = agent.memories().orElseThrow();

            // 1. 广告法与品牌合规红线 (AGENT 作用域 -> INSTRUCTION)
            memories.put(PutMemoryCommand.of(
                    MemoryScopeSpec.agent(agentId),
                    MemoryKind.INSTRUCTION,
                    "ad-compliance-and-forbidden-words",
                    "广告法与品牌合规准则：1. 严禁在文案中使用极限词（如：最、第一、顶级、神级、全网首发）；"
                            + "2. 功能性宣传必须基于客观检测，禁止夸大为'极地防冻'；3. 赠品必须明确规格，禁止模糊宣传。"));
            System.out.println("[已存入合规红线] AGENT INSTRUCTION: ad-compliance-and-forbidden-words");

            // 2. 平台佣金与毛利控价底线 (AGENT 作用域 -> FACT)
            memories.put(PutMemoryCommand.of(
                    MemoryScopeSpec.agent(agentId),
                    MemoryKind.FACT,
                    "channel-fees-and-margin-floor",
                    "全渠道价格治理底线：天猫平台综合扣点 5.5%；抖音直播渠道综合分成 20%；全渠道终端销售毛利率不得低于 38%；双11活动券叠券禁止低于破价红线 199 元。"));
            System.out.println("[已存入控价底线] AGENT FACT: channel-fees-and-margin-floor (毛利率不低于 38%)");

            // 3. 运营小美的店铺职责与文案风格 (USER 作用域 -> FACT & PREFERENCE)
            memories.put(PutMemoryCommand.of(
                    MemoryScopeSpec.user(),
                    MemoryKind.FACT,
                    "store-assignment",
                    "运营人员：小美；负责店铺：天猫官方旗舰店主理人；主推类目：三合一冲锋衣与高海拔登山服。"));
            memories.put(PutMemoryCommand.of(
                    MemoryScopeSpec.user(),
                    MemoryKind.PREFERENCE,
                    "copywriting-tone-style",
                    "推广文案风格习惯：面向年轻轻户外群体，突出‘防风暴雨科技面料’与‘通勤机能穿搭’，多用 🏔️、⚡️、🧥 等 Emoji 分段。"));
            System.out.println("[已存入运营画像] USER FACT & PREFERENCE: store-assignment, copywriting-tone-style");

            // 4. ERP 库存监控 Tool 扫描到短缺后沉淀长效记忆 (AGENT 作用域 -> TOOL_OUTCOME)
            memories.put(new PutMemoryCommand(
                    MemoryScopeSpec.agent(agentId),
                    MemoryKind.TOOL_OUTCOME,
                    "inventory-alert-sku-storm-jacket",
                    "ERP 实时库存预警：黑色三合一冲锋衣 L 码华东总仓仅存 25 件，已进入补货排期。大促文案禁止做秒杀爆推，引导主推备货充裕的军绿色与雾霾灰。",
                    Optional.of(new MemorySourceRef(MemorySourceType.TOOL_CALL, "erp-inventory-task-409")),
                    Optional.empty()));
            System.out.println("[已存入库存预警] AGENT TOOL_OUTCOME: inventory-alert-sku-storm-jacket (黑色L码断码)");

            // 5. 展示结构化记忆列表查询
            var agentMemoriesList = memories.list(MemoryListQuery.of(MemoryScopeSpec.agent(agentId), 10));
            System.out.println("\n[记忆治理列表] 当前 AGENT 作用域已生效记忆条数: "
                    + agentMemoriesList.items().size());
            agentMemoriesList
                    .items()
                    .forEach(m ->
                            System.out.println("   • [" + m.kind() + "] " + m.subjectKey() + " -> " + m.content()));

            // 6. 发起用户对话，生成合规的大促营销方案
            System.out.println("\n[运营发起咨询] 小美: '双11大促马上开始了，帮我针对主打的三合一冲锋衣出个天猫微淘大促预热推文，做个满300减100的券。'");
            var started = agent.conversations()
                    .start(new StartConversationCommand(
                            "ecommerce-turn-1",
                            "Autumn Campaign",
                            "双11大促马上开始了，帮我针对主打的三合一冲锋衣出个天猫微淘大促预热推文，做个满300减100的券。"));
            var runSnapshot = agent.runs().await(started.runId());

            List<ModelMessage> messages = interceptedMessages.get();
            long memorySnippetCount = messages.stream()
                    .filter(m -> m.content().startsWith("[memory "))
                    .count();
            System.out.println("[Prompt 注入结果] 运行时自动召回记忆片段数量: " + memorySnippetCount);
            messages.stream()
                    .filter(m -> m.content().startsWith("[memory "))
                    .forEach(m -> System.out.println("   -> " + m.content().replace("\n", " -> ")));

            System.out.println("\n[Agent 答复输出]:\n" + runSnapshot.output().orElseThrow());
        }
    }

    private static HaifaAgent openAgent(
            Path dataDirectory, String agentDefinitionId, String description, AgentChatModel model) {
        Path database = dataDirectory.resolve(agentDefinitionId + ".sqlite");
        var key = new SecretKeySpec(new byte[32], "AES");
        var sqlite = SqliteSdkProductContributions.initialize(
                SqliteStoreConfiguration.defaults(database),
                Clock.systemUTC(),
                new AesGcmModelContinuationProtector(key, new SecureRandom()),
                ProductMemoryPolicy.safeDefault(),
                new ProductArtifactPolicy(
                        1_048_576,
                        16,
                        16L * 1_048_576,
                        Set.of("application/json", "text/markdown"),
                        false,
                        64L * 1_048_576,
                        128L * 1_048_576,
                        false));

        ResolvedModelSnapshot snapshot = DeterministicExampleSupport.snapshot();
        ModelContribution models = new ModelContribution(
                Map.of(ModelAdapterCoordinate.from(snapshot), model),
                snapshot,
                Map.of(snapshot.modelId().value(), snapshot));

        ProductProfile profile = ProductProfile.create(
                new ProductId("enterprise-memory-example"),
                new ProductVersion(VERSION),
                new AgentDefinitionId(agentDefinitionId),
                new AgentDefinitionVersion(1, 0, 0),
                description,
                new ProductRunProfileRef(snapshot.modelId().value(), VERSION),
                new AgentRunBudget(65_536, 8_192, 65_536, 16, 16, 0, "USD", 100),
                new AgentRunLimits(16, 0, 1, 120_000, 60_000, 16, 16, 0),
                Set.of(),
                Set.of());

        PolicyRule defaultRule = new PolicyRule(
                new PolicyRuleRef("sdk-example-default-deny", "1"),
                PolicyRuleSource.MANAGED,
                0,
                PolicyRuleMatcher.any(),
                PolicyEffect.DENY,
                Optional.empty(),
                "SDK_EXAMPLE_TOOL_DENIED",
                "The durable SDK example does not enable public tools");

        PolicyPlatformContribution policy = new PolicyPlatformContribution(
                PolicyRuleSet.of(List.of(), Optional.of(defaultRule), ApprovalMode.DENY),
                new DefaultPolicyDecisionService());

        try {
            return HaifaAgents.builder(profile)
                    .callerProvider(SdkCallerProvider.defaultPublicUser())
                    .model(models)
                    .persistence(sqlite.persistence())
                    .conversation(sqlite.conversation())
                    .memory(sqlite.memory())
                    .policy(policy)
                    .artifacts(sqlite.artifact())
                    .build();
        } catch (RuntimeException | Error exception) {
            sqlite.persistence().close();
            throw exception;
        }
    }

    private static void deleteDirectory(Path directory) {
        if (!Files.exists(directory)) return;
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // Best effort cleanup for temp directory
        }
    }
}
