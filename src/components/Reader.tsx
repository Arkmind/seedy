"use client";

import { Rendition } from "epubjs";
import { useTheme } from "next-themes";
import { FC, useEffect, useRef, useState } from "react";
import {
  IReactReaderProps,
  IReactReaderStyle,
  ReactReader,
  ReactReaderStyle,
} from "react-reader";

export interface ReaderProps
  extends Omit<IReactReaderProps, "location" | "locationChanged"> {}

const lightReaderTheme: IReactReaderStyle = {
  ...ReactReaderStyle,
  readerArea: {
    ...ReactReaderStyle.readerArea,
  },
};

const darkReaderTheme: IReactReaderStyle = {
  ...ReactReaderStyle,
  arrow: {
    ...ReactReaderStyle.arrow,
    color: "white",
  },
  arrowHover: {
    ...ReactReaderStyle.arrowHover,
    color: "#ccc",
  },
  readerArea: {
    ...ReactReaderStyle.readerArea,
    backgroundColor: "#0a0a0a",
  },
  titleArea: {
    ...ReactReaderStyle.titleArea,
    color: "#ccc",
  },
  tocArea: {
    ...ReactReaderStyle.tocArea,
    background: "#0a0a0a",
    color: "#fff",
  },
  tocButtonExpanded: {
    ...ReactReaderStyle.tocButtonExpanded,
    background: "#222",
    top: "20px",
    left: "20px",
  },
  tocButtonBar: {
    ...ReactReaderStyle.tocButtonBar,
    background: "#fff",
  },
  tocButton: {
    ...ReactReaderStyle.tocButton,
    color: "white",
  },
};

const updateTheme = (rendition: Rendition, theme: "dark" | "light") => {
  const themes = rendition.themes;

  switch (theme) {
    case "dark": {
      themes.override("color", "#fff");
      themes.override("background", "#0a0a0a");
      themes.override(
        "transition",
        "background 0.2s cubic-bezier(0.4, 0, 0.2, 1)"
      );
      break;
    }
    case "light": {
      themes.override("color", "#0a0a0a");
      themes.override("background", "#fff");
      themes.override(
        "transition",
        "background 0.2s cubic-bezier(0.4, 0, 0.2, 1)"
      );
      break;
    }
  }
};

export const Reader: FC<ReaderProps> = (props) => {
  const theme = useTheme();
  const rendition = useRef<Rendition | undefined>(undefined);
  const [location, setLocation] = useState<string | number>(0);

  useEffect(() => {
    if (rendition.current) {
      updateTheme(rendition.current, theme.resolvedTheme as "dark" | "light");
    }
  }, [theme.resolvedTheme]);

  return (
    <ReactReader
      url={props.url || "https://react-reader.metabits.no/files/alice.epub"}
      location={location}
      locationChanged={(epubcfi: string) => setLocation(epubcfi)}
      readerStyles={
        theme.resolvedTheme === "dark" ? darkReaderTheme : lightReaderTheme
      }
      getRendition={(_rendition) => {
        updateTheme(_rendition, theme.resolvedTheme as "dark" | "light");
        rendition.current = _rendition;
      }}
    />
  );
};
