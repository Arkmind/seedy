"use client";

import { getFiles } from "@/actions/files";
import { useFileSystem } from "@/hooks/useFile";
import { useLayout } from "@/hooks/useLayout";
import { classnames } from "@/librairies/utils";
import { FileSystemItem } from "@/types/filesystem";
import { FC, useEffect, useRef, useState } from "react";
import { File } from "./File";
import { FileExplorer } from "./FileExplorer";
import { FileFilter } from "./FileFilter";
import { Skeleton } from "./ui/skeleton";

export interface FileListProps {
  files?: FileSystemItem[];
}

const getLayout = (layout: "list" | "grid" | "dots", file: boolean) => {
  if (file) {
    switch (layout) {
      case "list":
        return "grid-cols-1";
      case "grid":
        return "grid-cols-1 md:grid-cols-2 lg:grid-cols-3";
      case "dots":
        return "grid-cols-3 md:grid-cols-4 lg:grid-cols-5";
    }
  } else {
    switch (layout) {
      case "list":
        return "grid-cols-1 md:grid-cols-1 lg:grid-cols-1";
      case "grid":
        return "grid-cols-2 md:grid-cols-3 lg:grid-cols-4";
      case "dots":
        return "grid-cols-4 md:grid-cols-5 lg:grid-cols-6";
    }
  }
};

export const FileList: FC<FileListProps> = ({ files: defaultFiles }) => {
  const [files, setFiles] = useState<FileSystemItem[]>(defaultFiles || []);
  const loading = useRef(false);
  const finish = useRef(false);
  const count = useRef(0);
  const filesLength = useRef(0);
  const fileSystem = useFileSystem();
  const layout = useLayout();

  const fetchFiles = async (
    reset: boolean = false,
    query: string = "",
    order: string = "",
    extension: string = "",
    nlength?: string
  ) => {
    loading.current = true;

    try {
      const { data, count: responseCount } = await getFiles(
        query,
        order,
        extension,
        nlength || filesLength.current.toString(),
        "12"
      );

      setFiles((prev) => (reset ? data : [...prev, ...data]));
      count.current = responseCount;

      if (!reset && count.current === filesLength.current) {
        finish.current = true;
      } else if (reset) {
        finish.current = false;
      }
    } catch (error) {
      console.error("error", error);
    }

    loading.current = false;
  };

  useEffect(() => {
    filesLength.current = files.length;
  }, [files]);

  useEffect(() => {
    const localLayout = localStorage.getItem("layout") as
      | "list"
      | "grid"
      | "dots";

    if (localLayout) {
      layout.setLayout(localLayout);
    }
  }, []);

  return (
    <div className="flex flex-col space-y-8">
      {!defaultFiles ? (
        <FileFilter
          onChange={(reset, query, order, extension, plength) => {
            if (!reset && (finish.current || loading.current)) return;

            fetchFiles(reset, query, order, extension, plength);
          }}
        />
      ) : (
        <FileExplorer
          onSelect={() => {
            if (fileSystem.current.length > 0) {
              fileSystem.setCurrent([]);
            } else {
              fileSystem.setCurrent(
                files.filter((file) => file.type !== "directory")
              );
            }
          }}
        />
      )}
      <div
        className={classnames(
          "grid gap-8 transition-all",
          getLayout(layout.layout, fileSystem.current.length > 0)
        )}
      >
        {files.map((file) => {
          return <File key={file.name} file={file} />;
        })}
        {loading.current &&
          Array.from({ length: 12 }).map((_, i) => (
            <Skeleton key={i} className="h-full" />
          ))}
      </div>
      {finish.current && <h3 className="text-center">No more files to show</h3>}
    </div>
  );
};
