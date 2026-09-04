import { useCallback, useEffect, useState } from "react";
import type { ModelConnection, ModelPreferences } from "../api/generated";
import type { PersonalAssistantClient } from "../api/client";
import type { ModelConnectionsTab } from "../components/ModelConnectionsModal";

export function useModelCenterState({
  client,
}: {
  client: PersonalAssistantClient;
}) {
  const [newModelId, setNewModelId] = useState("");
  const [newModelPreferences, setNewModelPreferences] = useState<ModelPreferences | null>(null);
  const [modelConnections, setModelConnections] = useState<ModelConnection[] | null>(null);
  const [modelConnectionsOpen, setModelConnectionsOpen] = useState(false);
  const [modelCenterTab, setModelCenterTab] = useState<ModelConnectionsTab>("catalog");

  useEffect(() => {
    if (!client.modelConnections) return;
    const controller = new AbortController();
    client
      .modelConnections(controller.signal)
      .then((connections) => {
        if (!controller.signal.aborted) setModelConnections(connections);
      })
      .catch(() => {
        if (!controller.signal.aborted) setModelConnections(null);
      });
    return () => controller.abort();
  }, [client]);

  const openModelCenter = useCallback((tab: ModelConnectionsTab) => {
    setModelCenterTab(tab);
    setModelConnectionsOpen(true);
  }, []);

  const closeModelCenter = useCallback((onClosed?: () => void) => {
    setModelConnectionsOpen(false);
    if (onClosed) {
      window.requestAnimationFrame(onClosed);
    }
  }, []);

  return {
    newModelId,
    setNewModelId,
    newModelPreferences,
    setNewModelPreferences,
    modelConnections,
    setModelConnections,
    modelConnectionsOpen,
    setModelConnectionsOpen,
    modelCenterTab,
    setModelCenterTab,
    openModelCenter,
    closeModelCenter,
  };
}
