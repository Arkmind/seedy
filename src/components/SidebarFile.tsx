"use client";

import { useFileSystem } from "@/hooks/useFile";
import { downloadGeneratedFile } from "@/librairies/downloadGeneratedFile";
import { classnames, formatBytes } from "@/librairies/utils";
import { Tooltip, TooltipTrigger } from "@radix-ui/react-tooltip";
import { contentType } from "mime-types";
import Link from "next/link";
import { useRouter } from "next/navigation";
import ptt from "parse-torrent-title";
import { join } from "path";
import { FC } from "react";
import { PiDownload, PiFileText, PiPlay, PiShare, PiX } from "react-icons/pi";
import { IconOf } from "./File";
import { Button } from "./ui/button";
import { TooltipContent } from "./ui/tooltip";

export const SidebarFile: FC = () => {
  const router = useRouter();
  const fileSystem = useFileSystem();

  const readFile = async () => {
    if (fileSystem.current.length !== 1) {
      return;
    }

    const file = fileSystem.current[0];

    const mime = contentType(file.name);

    if (mime && mime === "application/epub+zip") {
      router.push(`/epub/${file.url?.split("/").slice(2).join("/")}`);
    } else if (mime && mime.startsWith("video")) {
      router.push(
        `/seedflix/player/${file.url?.split("/").slice(2).join("/")}`
      );
    }
  };

  const downloadAsM3u8 = async () => {
    const parsed = ptt.parse(fileSystem.current[0].name);
    const playlistName = parsed.title;
    let playlistContent = ``;

    fileSystem.current.forEach((file) => {
      const url = join(
        process.env.NEXT_PUBLIC_API_URL || "",
        file.download || ""
      );
      const name = file.name.replace(/.*?[/]/, "");

      playlistContent += `\n#EXTINF:,${name}` + `\n${url}`;
    });

    downloadGeneratedFile(`${playlistName}.m3u8`, `#EXTM3U${playlistContent}`);
  };

  if (!fileSystem.current) {
    return null;
  }

  return (
    <div
      className={classnames(
        "flex flex-col py-10 overflow-x-hidden w-0 transition-all dark:bg-black bg-white items-center sticky top-0 h-screen",
        fileSystem.current.length > 0 && "w-1/4 px-2"
      )}
    >
      <div className="flex flex-col items-center w-full px-8">
        <div className="flex w-full">
          <div className="flex flex-1 space-x-3 items-center">
            <PiFileText size={30} />
            <h3 className="font-semibold">File preview</h3>
          </div>
          <div className="flex flex-1 justify-end">
            <Tooltip delayDuration={200}>
              <TooltipTrigger asChild>
                <Button
                  className="size-8 dark:bg-black dark:border-neutral-700 hover:dark:bg-neutral-800 bg-white hover:bg-neutral-100 border-neutral-200"
                  variant="outline"
                  onClick={() => fileSystem.setCurrent([])}
                >
                  <PiX size={16} />
                </Button>
              </TooltipTrigger>
              <TooltipContent>
                <p>Unselect every files</p>
              </TooltipContent>
            </Tooltip>
          </div>
        </div>
        <hr className="w-full my-12" />
      </div>
      <div
        className={classnames(
          "flex-1 flex flex-col items-center w-full h-full overflow-auto px-8",
          "[&::-webkit-scrollbar]:w-2 [&::-webkit-scrollbar-thumb]:rounded-md",
          "[&::-webkit-scrollbar-track]:bg-white [&::-webkit-scrollbar-thumb]:bg-gray-50",
          "dark:[&::-webkit-scrollbar-track]:bg-black dark:[&::-webkit-scrollbar-thumb]:bg-neutral-950"
        )}
      >
        {fileSystem.current.length > 1 && (
          <div className="flex flex-col space-y-2 w-full h-full">
            {fileSystem.current.map((file) => (
              <div key={`current-${file.name}`} className="flex w-full">
                <div className="flex flex-col justify-center flex-1 w-[calc(100%-5rem)]">
                  <Tooltip delayDuration={800}>
                    <TooltipTrigger asChild>
                      <h2 className="font-semibold text-lg text-ellipsis w-full overflow-hidden whitespace-nowrap">
                        {file.name}
                      </h2>
                    </TooltipTrigger>
                    <TooltipContent>
                      <p>{file.name}</p>
                    </TooltipContent>
                  </Tooltip>
                  <div>
                    <p className="dark:text-neutral-500 text-xs">
                      {formatBytes(file.size)} |{" "}
                      {file.date ? new Date(file.date).toLocaleString() : "???"}
                    </p>
                  </div>
                </div>
                <div className="flex justify-end w-20">
                  <Button
                    variant="outline"
                    className="size-7 dark:bg-neutral-900 dark:border-neutral-700 hover:dark:bg-neutral-800 bg-neutral-50 hover:bg-neutral-100 border-neutral-200"
                    onClick={() => fileSystem.removeFile(file)}
                  >
                    <PiX className="scale-75" />
                  </Button>
                </div>
              </div>
            ))}
          </div>
        )}
        {fileSystem.current.length === 1 && (
          <>
            <IconOf filename={fileSystem.current[0].name} size={200} />
            <hr className="w-full my-12" />
            <div className="flex flex-col space-y-2 w-full">
              <h2 className="font-semibold text-xl">
                {fileSystem.current[0].name}
              </h2>
              <div>
                <h3 className="dark:text-neutral-500">
                  {fileSystem.current[0].date
                    ? new Date(fileSystem.current[0].date).toLocaleString()
                    : "???"}
                </h3>
                <h3 className="dark:text-neutral-500">
                  {formatBytes(fileSystem.current[0].size)}
                </h3>
              </div>
            </div>
          </>
        )}
      </div>
      <div className="flex flex-col w-full px-8">
        <hr className="my-10" />
        <div className="flex justify-evenly w-full">
          <Tooltip>
            <TooltipTrigger asChild>
              <Button className="size-12" onClick={() => readFile()}>
                <PiPlay size={20} />
              </Button>
            </TooltipTrigger>
            <TooltipContent>
              <p>Play the file in the browser (beta)</p>
            </TooltipContent>
          </Tooltip>
          {fileSystem.current.length === 1 && (
            <Tooltip>
              <TooltipTrigger asChild>
                <Link href={fileSystem.current[0].download || ""}>
                  <Button className="size-12">
                    <PiDownload size={20} />
                  </Button>
                </Link>
              </TooltipTrigger>
              <TooltipContent>
                <p>Download the file</p>
              </TooltipContent>
            </Tooltip>
          )}
          {fileSystem.current.length > 1 &&
            fileSystem.current.every((file) => {
              const mime = contentType(file.name);

              return (
                mime && (mime.startsWith("video") || mime.startsWith("audio"))
              );
            }) && (
              <Tooltip>
                <TooltipTrigger asChild>
                  <Button className="size-12" onClick={() => downloadAsM3u8()}>
                    <PiDownload size={20} />
                  </Button>
                </TooltipTrigger>
                <TooltipContent>
                  <p>Download the current view into an m3u8 file</p>
                </TooltipContent>
              </Tooltip>
            )}
          <Tooltip>
            <TooltipTrigger asChild>
              <Button className="size-12">
                <PiShare size={20} />
              </Button>
            </TooltipTrigger>
            <TooltipContent>
              <p>Share the file</p>
            </TooltipContent>
          </Tooltip>
        </div>
        <hr className="mt-10" />
      </div>
    </div>
  );
};
