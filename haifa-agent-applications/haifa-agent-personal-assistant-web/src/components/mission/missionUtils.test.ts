import { describe, expect, it } from "vitest";
import {
  artifactDisplayName,
  missionArtifactItems,
  parseMissionFinalResult,
} from "./missionUtils";
import type { MissionSnapshot } from "../../api/generated";

describe("missionUtils", () => {
  it("parses v2 mission final result with structured sources and outcomes", () => {
    const raw = JSON.stringify({
      schemaVersion: "pa.mission-final-result/v2",
      directAnswer: "Direct summary",
      answerMarkdown: "# Mission Report\n\nFull details",
      completedItems: ["Task 1 done"],
      failedItems: [],
      artifactRefs: ["art-1", "art-2"],
      reportArtifactRef: {
        artifactId: "art-1",
        title: "mission-report.md",
        mediaType: "text/markdown; charset=utf-8",
      },
      resultArtifactRef: {
        artifactId: "art-2",
        title: "mission-result.json",
        mediaType: "application/json",
      },
      sources: [
        {
          sourceId: "src-001",
          title: "Ethereum Blog",
          locator: "https://blog.ethereum.org/dencun",
        },
      ],
      taskOutcomes: [
        {
          taskId: "task-protocol-upgrades",
          status: "COMPLETED",
        },
      ],
      acceptanceOutcomes: [
        {
          criterionIndex: 0,
          status: "SATISFIED",
          taskIds: ["task-protocol-upgrades"],
        },
      ],
      sectionSources: [
        {
          sectionHeading: "协议升级",
          sourceIds: ["src-001"],
        },
      ],
      sourceRefs: ["Ethereum Blog: https://blog.ethereum.org/dencun"],
      unverifiedClaims: [],
      unresolvedQuestions: [],
      residualRisks: [],
      completionKind: "COMPLETE",
    });

    const parsed = parseMissionFinalResult(raw);
    expect(parsed).not.toBeNull();
    expect(parsed?.schemaVersion).toBe("pa.mission-final-result/v2");
    expect(parsed?.directAnswer).toBe("Direct summary");
    expect(parsed?.reportArtifactRef?.artifactId).toBe("art-1");
    expect(parsed?.reportArtifactRef?.title).toBe("mission-report.md");
    expect(parsed?.resultArtifactRef?.artifactId).toBe("art-2");
    expect(parsed?.resultArtifactRef?.title).toBe("mission-result.json");
    expect(parsed?.sources).toEqual([
      {
        sourceId: "src-001",
        title: "Ethereum Blog",
        locator: "https://blog.ethereum.org/dencun",
      },
    ]);
    expect(parsed?.taskOutcomes).toEqual([
      {
        taskId: "task-protocol-upgrades",
        status: "COMPLETED",
      },
    ]);
    expect(parsed?.acceptanceOutcomes).toEqual([
      {
        criterionIndex: 0,
        status: "SATISFIED",
        taskIds: ["task-protocol-upgrades"],
      },
    ]);
    expect(parsed?.sectionSources).toEqual([
      {
        sectionHeading: "协议升级",
        sourceIds: ["src-001"],
      },
    ]);
  });

  it("resolves display names for standard mission artifacts", () => {
    expect(artifactDisplayName("mission-report.md")).toBe("完整任务报告");
    expect(artifactDisplayName("mission-result.json")).toBe("任务执行结果");
    expect(artifactDisplayName("research-report.md")).toBe("完整研究报告");
  });

  it("extracts typed mission artifact items for standard mission artifacts", () => {
    const raw = JSON.stringify({
      schemaVersion: "pa.mission-final-result/v2",
      directAnswer: "Direct summary",
      answerMarkdown: "# Report",
      completedItems: [],
      failedItems: [],
      artifactRefs: ["art-res", "art-rep"],
      resultArtifactRef: {
        artifactId: "art-res",
        title: "mission-result.json",
        mediaType: "application/json",
      },
      reportArtifactRef: {
        artifactId: "art-rep",
        title: "mission-report.md",
        mediaType: "text/markdown; charset=utf-8",
      },
      completionKind: "COMPLETE",
    });

    const mockMission: MissionSnapshot = {
      schemaVersion: "pa.mission/v1",
      missionId: "m-1",
      conversationId: "c-1",
      modelBinding: {
        modelId: "mock-model",
        modelDisplayName: "Mock Model",
        providerId: "mock",
        providerDisplayName: "Mock Provider",
        configurationDigest: "digest",
      },
      objective: "Test objective",
      acceptanceCriteria: [],
      constraints: { maxTasks: 3, maxDependencyDepth: 2 },
      mode: "STANDARD",
      researchBrief: null,
      selectedSkillId: null,
      selectedSkillBinding: null,
      state: "COMPLETED",
      plan: null,
      tasks: [],
      blocker: null,
      artifacts: ["art-res", "art-rep"],
      sources: [],
      finalResult: raw,
      version: 1,
      createdAt: "2026-09-05T00:00:00Z",
      updatedAt: "2026-09-05T00:00:00Z",
      confirmedAt: null,
      finishedAt: "2026-09-05T00:00:00Z",
      pollAfterMs: 1000,
      execution: {
        dispatcherStatus: "READY",
        recovering: false,
        allTasksSettled: true,
        completedTasks: 0,
        blockedTasks: 0,
        currentTaskId: null,
        latestAttempt: null,
      },
    };

    const items = missionArtifactItems(mockMission);
    expect(items).toHaveLength(2);
    expect(items[0]).toEqual({
      artifactId: "art-res",
      title: "任务执行结果",
      fileName: "mission-result.json",
      mediaType: "application/json",
    });
    expect(items[1]).toEqual({
      artifactId: "art-rep",
      title: "完整任务报告",
      fileName: "mission-report.md",
      mediaType: "text/markdown; charset=utf-8",
    });
  });
});
