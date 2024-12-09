import "@/app/videojs.css";
import React, { FC, useEffect, useState } from "react";
import videojs from "video.js";
import Player from "video.js/dist/types/player";
import "video.js/dist/video-js.css";

export interface VideoJSOptions {
  // Standard HTML5 Video Element Options
  autoplay?: boolean | "muted" | "play" | "any";
  controls?: boolean;
  height?: string | number;
  loop?: boolean;
  muted?: boolean;
  poster?: string;
  preload?: "auto" | "metadata" | "none";
  src?: string;
  width?: string | number;

  // Video.js Specific Options
  aspectRatio?: string;
  audioOnlyMode?: boolean;
  audioPosterMode?: boolean;
  autoSetup?: boolean;
  breakpoints?: {
    tiny?: number;
    xsmall?: number;
    small?: number;
    medium?: number;
    large?: number;
    xlarge?: number;
    huge?: number;
  };
  children?: string[] | Record<string, unknown>;
  controlBar?: {
    remainingTimeDisplay?: {
      displayNegative?: boolean;
    };
    skipButtons?: {
      forward?: 5 | 10 | 30;
      backward?: 5 | 10 | 30;
    };
    [key: string]: unknown;
  };
  disablePictureInPicture?: boolean;
  enableDocumentPictureInPicture?: boolean;
  enableSmoothSeeking?: boolean;
  experimentalSvgIcons?: boolean;
  fluid?: boolean;
  fullscreen?: {
    options?: {
      navigationUI?: "hide" | "show";
      [key: string]: unknown;
    };
  };
  id?: string;
  inactivityTimeout?: number;
  language?: string;
  languages?: Record<string, Record<string, string>>;
  liveui?: boolean;
  liveTracker?: {
    trackingThreshold?: number;
    liveTolerance?: number;
  };
  nativeControlsForTouch?: boolean;
  normalizeAutoplay?: boolean;
  notSupportedMessage?: string;
  noUITitleAttributes?: boolean;
  playbackRates?: number[];
  playsinline?: boolean;
  plugins?: Record<string, unknown>;
  preferFullWindow?: boolean;
  responsive?: boolean;
  restoreEl?: boolean | HTMLElement;
  sources?: Array<{
    src: string;
    type: string;
  }>;
  suppressNotSupportedError?: boolean;
  techCanOverridePoster?: boolean;
  techOrder?: string[];
  userActions?: {
    click?: boolean | ((event: MouseEvent) => void);
    doubleClick?: boolean | ((event: MouseEvent) => void);
    hotkeys?:
      | boolean
      | ((event: KeyboardEvent) => void)
      | {
          fullscreenKey?: (event: KeyboardEvent) => boolean;
          muteKey?: (event: KeyboardEvent) => boolean;
          playPauseKey?: (event: KeyboardEvent) => boolean;
        };
  };
  "vtt.js"?: string;
  spatialNavigation?: {
    enabled?: boolean;
    horizontalSeek?: boolean;
  };

  // HTML5 Tech-specific options
  html5?: {
    nativeControlsForTouch?: boolean;
    nativeAudioTracks?: boolean;
    nativeTextTracks?: boolean;
    nativeVideoTracks?: boolean;
    preloadTextTracks?: boolean;
  };
}

export interface VideoJSProps {
  options: VideoJSOptions;
  onReady?: (player: Player) => void;
}

export const VideoJS: FC<VideoJSProps> = ({ options, onReady }) => {
  const [isFirst, setIsFirst] = useState(true);
  const videoRef = React.useRef<HTMLDivElement>(null);
  const playerRef = React.useRef<Player>(null);

  useEffect(() => {
    if (!isFirst) {
      return;
    }

    // Make sure Video.js player is only initialized once
    if (!playerRef.current) {
      // The Video.js player needs to be _inside_ the component el for React 18 Strict Mode.
      const videoElement = document.createElement("video-js");

      videoElement.classList.add("vjs-big-play-centered");
      videoRef.current?.appendChild(videoElement);

      const player = (playerRef.current = videojs(videoElement, options, () => {
        videojs.log("player is ready");
        onReady?.(player);
      }));

      // You could update an existing player in the `else` block here
      // on prop change, for example:
      player.fill(true);
    }

    setIsFirst(false);
  }, [videoRef]);

  useEffect(() => {
    const player = playerRef.current;

    if (!player) {
      return;
    }

    player.autoplay(options.autoplay);
    player.src(options.sources);
  }, [options]);

  // Dispose the Video.js player when the functional component unmounts
  useEffect(() => {
    const player = playerRef.current;

    return () => {
      if (player && !player.isDisposed()) {
        player.dispose();
        playerRef.current = null;
      }
    };
  }, [playerRef]);

  return (
    <div data-vjs-player className="w-full h-full">
      <div className="w-full h-full" ref={videoRef} />
    </div>
  );
};
