"use client";

import { useFileSystem } from "@/hooks/useFile";
import { classnames } from "@/librairies/utils";
import { useRouter } from "next/navigation";
import { FC } from "react";
import { PiArrowLeft, PiCheck, PiX } from "react-icons/pi";
import { TabsLayout } from "./TabsLayout";
import { Button } from "./ui/button";

export interface FileExplorerProps {
  onSelect?: (evt: React.MouseEvent<HTMLButtonElement, MouseEvent>) => void;
}

export const FileExplorer: FC<FileExplorerProps> = ({ onSelect }) => {
  const fileSystem = useFileSystem();
  const router = useRouter();

  return (
    <div className="flex items-center">
      <div className="flex flex-1 justify-start items-center space-x-4">
        <Button
          onClick={() => router.back()}
          className="h-12"
          variant="outline"
        >
          <PiArrowLeft size={24} /> Go back
        </Button>
      </div>
      <div className="flex flex-1 justify-end space-x-4">
        <Button
          className={classnames(
            "size-12 dark:border-neutral-700 border-neutral-200",
            fileSystem.current.length > 0
              ? "dark:bg-neutral-900  hover:dark:bg-neutral-800 bg-neutral-100 hover:bg-neutral-200 "
              : "dark:bg-neutral-950 dark:border-neutral-700 bg-neutral-50 border-neutral-200"
          )}
          variant="outline"
          onClick={onSelect}
        >
          {fileSystem.current.length > 0 ? (
            <PiX size={20} />
          ) : (
            <PiCheck size={20} />
          )}
        </Button>
        <TabsLayout />
      </div>
    </div>
  );
};
