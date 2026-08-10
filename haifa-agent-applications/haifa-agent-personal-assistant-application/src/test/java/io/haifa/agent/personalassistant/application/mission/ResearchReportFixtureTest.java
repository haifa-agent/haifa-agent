package io.haifa.agent.personalassistant.application.mission;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ResearchReportFixtureTest {
    private final ReportQualityGate gate = new ReportQualityGate();

    @Test
    void coversPetRelocationPolicyTimelineCostAndFailurePlan() {
        assertFixture(
                report(
                        "带宠物跨国搬家",
                        "relocation-task",
                        "政策路径、十二周时间窗口、费用预算和检疫失败预案",
                        "入境政策与运输时间必须共同约束计划",
                        "按关键日期倒排并保留检疫失败的替代安排"),
                "relocation-task",
                "政策路径",
                "时间窗口",
                "费用",
                "失败预案");
    }

    @Test
    void coversFiveYearEldercareMedicalHousingCareCostAndTriggers() {
        assertFixture(
                report("父母异地养老", "eldercare-task", "五年阶段以及医疗、住房、陪护和成本比较", "健康变化和照护可用性决定阶段选择", "用健康、预算和陪护触发条件切换方案"),
                "eldercare-task",
                "五年",
                "医疗",
                "住房",
                "陪护",
                "成本",
                "触发条件");
    }

    @Test
    void coversAiProductClaimEvidenceCounterevidenceInferenceUnknownAndJudgment() {
        assertFixture(
                report(
                        "热门 AI 产品真实性调查",
                        "truth-task",
                        "宣传主张、已验证证据、反证、技术来源与商业模式",
                        "事实、推断和未知必须分开，热度不能替代真实性判断",
                        "真实性判断为部分成立，并要求公开可复现实测"),
                "truth-task",
                "主张",
                "证据",
                "反证",
                "推断",
                "未知",
                "真实性判断",
                "技术来源",
                "商业模式");
    }

    @Test
    void coversFailureTimelineCompetingAccountsDirectRootCauseAndDecisionMistakes() {
        assertFixture(
                report("商业失败复盘", "postmortem-task", "事件时间线、不同说法、直接原因和根本原因", "共识与冲突显示关键决策失误放大了执行问题", "将决策门槛和失败信号纳入后续评审"),
                "postmortem-task",
                "时间线",
                "不同说法",
                "直接原因",
                "根本原因",
                "决策失误");
    }

    private void assertFixture(String report, String taskId, String... requiredContent) {
        assertThat(gate.evaluate(report, List.of(taskId), Set.of("source-fixture"))
                        .passed())
                .isTrue();
        assertThat(report).contains(requiredContent);
    }

    private static String report(String title, String taskId, String finding, String synthesis, String conclusion) {
        return """
                # %s
                <!-- haifa-section: executive-summary -->
                ## 执行摘要
                本报告给出证据约束的核心结论、关键限制和下一步建议，避免把未验证信息写成事实。
                <!-- haifa-section: scope-method -->
                ## 范围、假设与方法
                使用确定性离线来源 Fixture 验证报告结构、任务覆盖和引用闭合，不声称复现外部真实结论。
                <!-- haifa-section: task-findings -->
                ## 分项研究发现
                <!-- haifa-task: %s -->
                ### 主要发现
                %s，并由 [[source-fixture]] 提供结构化证据。
                <!-- haifa-section: synthesis -->
                ## 综合分析
                %s，同时记录相反证据、剩余风险和会改变判断的新事实。
                <!-- haifa-section: conclusions -->
                ## 结论与建议
                %s，结论严格限制在已确认范围和当前证据内。
                <!-- haifa-section: risks-unknowns -->
                ## 风险、未知与待确认问题
                外部规则、产品版本和成本可能变化，正式执行前必须重新核对权威来源。
                <!-- haifa-section: sources -->
                ## 来源
                - [[source-fixture]] 确定性离线来源 Fixture，用于结构与完成语义验证。
                """
                .formatted(title, taskId, finding, synthesis, conclusion);
    }
}
