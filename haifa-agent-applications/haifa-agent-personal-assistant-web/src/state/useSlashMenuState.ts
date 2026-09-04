import { useCallback, useRef, useState } from "react";
import type { ModelPreferences } from "../api/generated";

export type SlashMenuState =
  | { stage: "commands" }
  | { stage: "providers" }
  | { stage: "models"; providerId: string }
  | { stage: "settings"; providerId: string; modelGroupId: string };

export function useSlashMenuState() {
  const [slashMenu, setSlashMenu] = useState<SlashMenuState | null>(null);
  const [slashActiveIndex, setSlashActiveIndex] = useState(0);
  const [slashFromPlus, setSlashFromPlus] = useState(false);
  const [modelDraftBindingId, setModelDraftBindingId] = useState("");
  const [modelDraftPreferences, setModelDraftPreferences] = useState<ModelPreferences | null>(null);

  const slashMenuRef = useRef<HTMLElement>(null);

  const closeSlashMenu = useCallback(() => {
    setSlashMenu(null);
    setSlashActiveIndex(0);
    setSlashFromPlus(false);
    setModelDraftBindingId("");
    setModelDraftPreferences(null);
  }, []);

  const openCommands = useCallback((fromPlus = false) => {
    setSlashMenu({ stage: "commands" });
    setSlashActiveIndex(0);
    setSlashFromPlus(fromPlus);
  }, []);

  return {
    slashMenu,
    setSlashMenu,
    slashActiveIndex,
    setSlashActiveIndex,
    slashFromPlus,
    setSlashFromPlus,
    modelDraftBindingId,
    setModelDraftBindingId,
    modelDraftPreferences,
    setModelDraftPreferences,
    slashMenuRef,
    closeSlashMenu,
    openCommands,
  };
}
