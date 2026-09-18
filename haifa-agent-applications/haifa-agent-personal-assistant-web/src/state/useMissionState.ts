import { useCallback, useEffect, useState, type RefObject } from "react";
import type { MissionSnapshot } from "../api/generated";
import type { PersonalAssistantClient } from "../api/client";
import type { MarkdownResearchContext } from "../utils/markdownRenderer";
import { conversationIdParameter } from "../utils/formatters";
import {
  parseMissionFinalResult,
  parseResearchSourcesArtifact,
  type MissionDraftRequest,
} from "../components/mission/missionUtils";

export const missionPathPattern = /^\/missions\/([^/]+)$/;

export function missionIdFromLocation(): string | null {
  const match = missionPathPattern.exec(window.location.pathname);
  if (!match) return null;
  try {
    return decodeURIComponent(match[1]);
  } catch {
    return null;
  }
}

export function useMissionState({
  client,
  selectedConversationId,
  previousFocusRef,
}: {
  client: PersonalAssistantClient;
  selectedConversationId: string | null;
  previousFocusRef?: RefObject<HTMLElement | null>;
}) {
  const [missionRouteId, setMissionRouteId] = useState<string | null>(() => missionIdFromLocation());
  const [missionOpen, setMissionOpen] = useState(() => missionIdFromLocation() != null);
  const [missionDraft, setMissionDraft] = useState<MissionDraftRequest | null>(null);
  const [conversationMission, setConversationMission] = useState<MissionSnapshot | null>(null);
  const [conversationMissions, setConversationMissions] = useState<MissionSnapshot[]>([]);
  const [requestedMissionTaskId, setRequestedMissionTaskId] = useState<string | null>(null);
  const [requestedMissionArtifact, setRequestedMissionArtifact] = useState<string | null>(null);
  const [researchReadingContext, setResearchReadingContext] = useState<
    (MarkdownResearchContext & { missionId: string }) | null
  >(null);

  const navigateToMission = useCallback((mission: MissionSnapshot) => {
    setMissionRouteId(mission.missionId);
    const query = new URLSearchParams(window.location.search);
    query.set(conversationIdParameter, mission.conversationId);
    const nextUrl = `/missions/${encodeURIComponent(mission.missionId)}?${query.toString()}`;
    if (`${window.location.pathname}${window.location.search}` !== nextUrl) {
      window.history.pushState(null, "", nextUrl);
    }
  }, []);

  const openResearchTask = useCallback(
    (ordinal: number) => {
      const mission = conversationMission;
      const task = mission?.tasks.find((candidate) => candidate.ordinal === ordinal);
      if (!mission || !task) return;
      if (previousFocusRef) {
        previousFocusRef.current = document.activeElement as HTMLElement;
      }
      setRequestedMissionTaskId(task.taskId);
      setMissionOpen(true);
      navigateToMission(mission);
    },
    [conversationMission, navigateToMission, previousFocusRef],
  );

  useEffect(() => {
    const syncMissionRoute = () => {
      const missionId = missionIdFromLocation();
      setMissionRouteId(missionId);
      setMissionOpen(missionId != null);
    };
    window.addEventListener("popstate", syncMissionRoute);
    return () => window.removeEventListener("popstate", syncMissionRoute);
  }, []);

  useEffect(() => {
    if (!selectedConversationId || !client.missions) {
      setConversationMission(null);
      setConversationMissions([]);
      return;
    }
    const controller = new AbortController();
    client
      .missions(selectedConversationId, controller.signal)
      .then((page) => {
        if (!controller.signal.aborted) {
          setConversationMissions(page.items);
          setConversationMission(page.items[0] ?? null);
        }
      })
      .catch(() => {
        if (!controller.signal.aborted) {
          setConversationMissions([]);
          setConversationMission(null);
        }
      });
    return () => controller.abort();
  }, [client, selectedConversationId]);

  useEffect(() => {
    const mission = conversationMission;
    const finalResult = parseMissionFinalResult(mission?.finalResult ?? null);
    if (!mission || finalResult?.schemaVersion !== "pa.research-delivery/v2") {
      setResearchReadingContext(null);
      return;
    }
    const baseContext: MarkdownResearchContext & { missionId: string } = {
      missionId: mission.missionId,
      anchorPrefix: `mission-${mission.missionId}`,
      tasks: mission.tasks.map((task) => ({
        ordinal: task.ordinal,
        taskId: task.taskId,
        title: task.title,
      })),
      sources: [],
      sourceState: "loading",
    };
    const artifactId = finalResult.sourcesArtifactRef?.artifactId;
    if (!artifactId || !client.missionArtifact) {
      setResearchReadingContext({ ...baseContext, sourceState: "failed" });
      return;
    }
    let cancelled = false;
    setResearchReadingContext(baseContext);
    client
      .missionArtifact(mission.missionId, artifactId)
      .then((artifact) => {
        if (!cancelled) {
          setResearchReadingContext({
            ...baseContext,
            sources: parseResearchSourcesArtifact(artifact),
            sourceState: "ready",
          });
        }
      })
      .catch(() => {
        if (!cancelled) setResearchReadingContext({ ...baseContext, sourceState: "failed" });
      });
    return () => {
      cancelled = true;
    };
  }, [client, conversationMission]);

  const handleMissionChanged = useCallback(
    (mission: MissionSnapshot | null) => {
      if (!mission || mission.conversationId === selectedConversationId) {
        setConversationMission(mission);
        setConversationMissions((current) => {
          if (!mission) return current;
          return [mission, ...current.filter((candidate) => candidate.missionId !== mission.missionId)];
        });
      }
    },
    [selectedConversationId],
  );

  return {
    missionRouteId,
    setMissionRouteId,
    missionOpen,
    setMissionOpen,
    missionDraft,
    setMissionDraft,
    conversationMission,
    setConversationMission,
    conversationMissions,
    setConversationMissions,
    requestedMissionTaskId,
    setRequestedMissionTaskId,
    requestedMissionArtifact,
    setRequestedMissionArtifact,
    researchReadingContext,
    setResearchReadingContext,
    navigateToMission,
    openResearchTask,
    handleMissionChanged,
  };
}
