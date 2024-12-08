"use client";

import { useFileSystem } from "@/hooks/useFile";
import { FC, useEffect } from "react";
import { useSidebar } from "./ui/sidebar";

export interface PlayerProps {
  url: string;
}

export const Player: FC<PlayerProps> = ({ url }) => {
  const sidebar = useSidebar();
  const fileSystem = useFileSystem();

  useEffect(() => {
    sidebar.setOpen(false);
    fileSystem.setCurrent([]);
  }, []);

  return (
    <div className="w-full h-full">
      <div
        className="w-2 h-screen absolute top-0 left-0 z-50"
        onMouseEnter={() => {
          sidebar.setOpen(true);
        }}
      ></div>
      <video
        controls
        className="h-full w-full bg-white dark:bg-black"
        onMouseMove={() => {
          sidebar.setOpen(false);
        }}
      >
        <source src={url} />
      </video>
    </div>
  );
};
