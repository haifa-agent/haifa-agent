import { useCallback, useEffect, useRef, useState } from "react";
import type { AudioInput, ImageInput } from "../api/generated";

export type ComposerMode = "CHAT" | "DEEP_RESEARCH";

export type PendingImage = ImageInput & {
  key: string;
  label: string;
  previewUrl?: string;
};

export type PendingAudio = AudioInput & {
  key: string;
  label: string;
};

export function useComposerState() {
  const [composerMode, setComposerMode] = useState<ComposerMode>("CHAT");
  const [pendingImages, setPendingImages] = useState<PendingImage[]>([]);
  const [pendingAudios, setPendingAudios] = useState<PendingAudio[]>([]);
  const [imageUrl, setImageUrl] = useState("");
  const [uploadingImage, setUploadingImage] = useState(false);
  const [uploadingAudio, setUploadingAudio] = useState(false);
  const [imageToolsOpen, setImageToolsOpen] = useState(false);
  const [imageUrlInputOpen, setImageUrlInputOpen] = useState(false);
  const [draggingImages, setDraggingImages] = useState(false);

  const fileInputRef = useRef<HTMLInputElement>(null);
  const audioInputRef = useRef<HTMLInputElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const imageToolsRef = useRef<HTMLDivElement>(null);
  const pendingImagePreviews = useRef(new Set<string>());

  const revokePreview = useCallback((url?: string) => {
    if (!url) return;
    URL.revokeObjectURL(url);
    pendingImagePreviews.current.delete(url);
  }, []);

  const clearAttachments = useCallback(() => {
    setPendingImages((current) => {
      current.forEach((image) => revokePreview(image.previewUrl));
      return [];
    });
    setPendingAudios([]);
  }, [revokePreview]);

  const resetComposer = useCallback(() => {
    clearAttachments();
    setImageUrl("");
    setUploadingImage(false);
    setUploadingAudio(false);
    setImageToolsOpen(false);
    setImageUrlInputOpen(false);
    setDraggingImages(false);
    setComposerMode("CHAT");
  }, [clearAttachments]);

  useEffect(() => () => {
    pendingImagePreviews.current.forEach((previewUrl) => URL.revokeObjectURL(previewUrl));
    pendingImagePreviews.current.clear();
  }, []);

  return {
    composerMode,
    setComposerMode,
    pendingImages,
    setPendingImages,
    pendingAudios,
    setPendingAudios,
    imageUrl,
    setImageUrl,
    uploadingImage,
    setUploadingImage,
    uploadingAudio,
    setUploadingAudio,
    imageToolsOpen,
    setImageToolsOpen,
    imageUrlInputOpen,
    setImageUrlInputOpen,
    draggingImages,
    setDraggingImages,
    fileInputRef,
    audioInputRef,
    textareaRef,
    imageToolsRef,
    pendingImagePreviews,
    revokePreview,
    clearAttachments,
    resetComposer,
  };
}
