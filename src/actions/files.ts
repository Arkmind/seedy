"use server";

import { FileSystemItem } from "@/types/filesystem";
import { join } from "path";

export const getFiles = async (
  query: string = "",
  order: string = "",
  extension: string = "",
  length: string = ""
) => {
  const searchParams = new URLSearchParams();
  searchParams.append("skip", length);
  searchParams.append("query", query);
  searchParams.append("order", order);
  searchParams.append("extension", extension === "none" ? "" : extension || "");
  searchParams.append("take", "8");

  try {
    const nfiles = await fetch(
      "http://localhost:3000/api/files/latest?" + searchParams.toString(),
      { cache: "no-store" }
    );
    return (await nfiles.json()) as { data: FileSystemItem[]; count: number };
  } catch (error) {
    console.error("error", error);
    return { data: [], count: 0 };
  }
};

export const explore = async (directory: string) => {
  try {
    const nfiles = await fetch(
      join("http://localhost:3000/api/downloads/", directory),
      { cache: "no-store" }
    );

    const files = (await nfiles.json()) as { data: FileSystemItem[] };

    return files;
  } catch (error) {
    console.error("error", error);
    return { data: [] };
  }
};
