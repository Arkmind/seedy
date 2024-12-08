"use client";

import { create } from "zustand";

export interface UseLayoutState {
  layout: "list" | "grid" | "dots";
  setLayout: (layout: "list" | "grid" | "dots") => void;
}

export const useLayout = create<UseLayoutState>((set) => ({
  layout: "grid",
  setLayout: (layout) => {
    if (typeof localStorage !== "undefined") {
      localStorage.setItem("layout", layout);
    }
    set({ layout });
  },
}));
