"use client";

import { useFileSystem } from "@/hooks/useFile";
import { useLayout } from "@/hooks/useLayout";
import { classnames, formatBytes } from "@/librairies/utils";
import { FileSystemItem } from "@/types/filesystem";
import { useRouter } from "next/navigation";
import { FC } from "react";
import {
  PiBookBookmark,
  PiFile,
  PiFileImage,
  PiFilePdf,
  PiFileVideo,
  PiFileZip,
  PiFolder,
} from "react-icons/pi";

export interface FileProps {
  file: FileSystemItem;
  onClick?: (file: FileSystemItem) => void;
}

export const IconOf: FC<{ filename: string; size: number }> = ({
  filename,
  size,
}) => {
  const file = filename.split(".").pop();

  switch (file) {
    case "folder":
      return <PiFolder size={size} />;
    case "mp4":
      return <PiFileVideo size={size} />;
    case "mkv":
      return <PiFileVideo size={size} />;
    case "avi":
      return <PiFileVideo size={size} />;
    case "pdf":
      return <PiFilePdf size={size} />;
    case "epub":
      return <PiBookBookmark size={size} />;
    case "png":
      return <PiFileImage size={size} />;
    case "jpg":
      return <PiFileImage size={size} />;
    case "zip":
      return <PiFileZip size={size} />;
    default:
      return <PiFile size={size} />;
  }
};

export const File: FC<FileProps> = ({ file, onClick }) => {
  const router = useRouter();
  const fileSystem = useFileSystem();
  const layout = useLayout();

  const isActive =
    fileSystem.current && fileSystem.current.some((f) => f.name === file.name);

  return (
    <div
      className={classnames(
        "flex items-center space-y-4 p-6 rounded-xl hover:dark:bg-neutral-900 hover:bg-neutral-50 transition-all cursor-pointer border-2",
        isActive
          ? "dark:border-neutral-900 border-neutral-200 dark:bg-neutral-800 bg-neutral-200"
          : "dark:bg-neutral-950 bg-white",
        layout.layout !== "list" && "flex-col h-full",
        layout.layout === "list" ? "justify-between" : "justify-center"
      )}
      onClick={() => {
        onClick?.(file);

        if (file.type === "directory" && file.url) router.push(file.url);
        if (file.type !== "file") return;

        fileSystem.setFile(file);
      }}
    >
      <div
        className={classnames(
          "flex items-center space-y-2",
          layout.layout !== "list" &&
            "flex-col justify-center w-64 max-w-full text-center"
        )}
      >
        {layout.layout !== "dots" && (
          <IconOf
            filename={file.type === "directory" ? "folder" : file.name || ""}
            size={120}
          />
        )}
        <div className="max-w-full max-h-36 overflow-hidden text-ellipsis line-clamp-5">
          <h3
            className={classnames(
              "font-bold text-xl",
              layout.layout !== "list" ? "text-center" : ""
            )}
          >
            {file.name}
          </h3>
        </div>
      </div>
      {file.type !== "directory" && (
        <>
          <hr
            className={classnames(
              "my-8",
              layout.layout !== "list" ? "w-full" : "h-full"
            )}
          />
          <div
            className={classnames(
              layout.layout === "list"
                ? "flex flex-col items-end w-auto text-right justify-center min-w-44"
                : "w-full"
            )}
          >
            <h3 className="text-sm font-semibold">Filesize:</h3>
            <span className="text-sm text-neutral-500">
              {"size" in file ? formatBytes(file.size) : "??"}
            </span>
            {layout.layout === "list" && (
              <>
                <h3 className="text-sm font-semibold">Date:</h3>
                <span className="text-sm text-neutral-500">
                  {file.date ? new Date(file.date).toLocaleString() : "???"}
                </span>
              </>
            )}
          </div>
        </>
      )}
    </div>
  );
};
