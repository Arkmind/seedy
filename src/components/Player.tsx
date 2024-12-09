"use client";

import { useFileSystem } from "@/hooks/useFile";
import { FC, useEffect, useRef, useState } from "react";
import videojs from "video.js";
import VideoJSPlayer from "video.js/dist/types/player";
import "video.js/dist/video-js.css";
import { useSidebar } from "./ui/sidebar";
import { VideoJS, VideoJSOptions } from "./VideoJS";

export interface PlayerProps {
  url: string;
}

export const Player: FC<PlayerProps> = ({ url }) => {
  const [videoJsOptions] = useState<VideoJSOptions>({
    autoplay: true,
    controls: true,
    responsive: true,
    fluid: true,
    sources: [
      {
        src: url,
        type: "video/mp4",
      },
    ],
    height: "100%",
    width: "auto",
  });
  const sidebar = useSidebar();
  const fileSystem = useFileSystem();
  const playerRef = useRef<VideoJSPlayer>(null);

  const handlePlayerReady = (player: VideoJSPlayer) => {
    playerRef.current = player;

    // You can handle player events here, for example:
    player.on("waiting", () => {
      videojs.log("player is waiting");
    });

    player.on("dispose", () => {
      videojs.log("player will dispose");
    });
  };

  useEffect(() => {
    sidebar.setOpen(false);
    fileSystem.setCurrent([]);
  }, []);

  return (
    <div className="w-full h-screen">
      <div
        className="w-2 h-screen absolute top-0 left-0 z-50"
        onMouseEnter={() => {
          sidebar.setOpen(true);
        }}
      ></div>
      <div
        className="h-full w-full bg-black dark:bg-black"
        onMouseMove={() => {
          sidebar.setOpen(false);
        }}
      >
        <VideoJS options={videoJsOptions} onReady={handlePlayerReady} />
      </div>
    </div>
  );
};
