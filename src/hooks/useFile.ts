import { File } from "@/types/filesystem";
import { create } from "zustand";

export interface UseFileState {
  current: File[];
  setCurrent: (file: File[]) => void;
  addFile: (file: File) => void;
  removeFile: (file: File) => void;
  setFile: (file: File) => void;
}

export const useFileSystem = create<UseFileState>((set, get) => ({
  current: [],
  setCurrent: (file) => set({ current: file }),
  addFile: (file) =>
    set((state) => ({
      current: [...state.current, file],
    })),
  removeFile: (file) =>
    set((state) => ({
      current: state.current.filter((f) => f.name !== file.name),
    })),
  setFile: (file) => {
    const exists = get().current.some((f) => f.name === file.name);

    if (exists) {
      get().removeFile(file);
    } else {
      get().addFile(file);
    }
  },
}));
