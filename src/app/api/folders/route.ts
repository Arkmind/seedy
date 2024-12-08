import { getInitialDirectory } from "@/librairies/utils";
import { promises as fs } from "fs";
import { NextRequest, NextResponse } from "next/server";
import { join } from "path";

interface Folder {
  name: string;
  type: "directory";
  children: (Folder | File | null)[];
  url?: string;
  download?: string;
  date?: Date;
}

interface File {
  name: string;
  type: "file";
  size: number;
  url?: string;
  download?: string;
  date?: Date;
}

const recursiveFileSearch = async (directory: string, parent = "") => {
  const files = await fs.readdir(directory);
  const data: (Folder | File)[] = [];

  for (const file of files) {
    const filePath = join(directory, file);
    const stat = await fs.stat(filePath);

    if (stat.isDirectory()) {
      data.push({
        name: file,
        type: "directory",
        children: await recursiveFileSearch(filePath, `${parent}/${file}`),
        url: join("/downloads", `${parent}/${file}`),
        download: join("/api/downloads", `${parent}/${file}?zip=true`),
        date: stat.mtime,
      });
    } else {
      data.push({
        name: file,
        type: "file",
        size: stat.size,
        url: join("/downloads", `${parent}/${file}`),
        download: join("/api/downloads", `${parent}/${file}`),
        date: stat.mtime,
      });
    }
  }

  return data;
};

const searchInRecursive = (
  items: (Folder | File)[],
  searchTerm: string,
  extensions?: string[]
): (Folder | File)[] => {
  function search(item: Folder | File): (Folder | File) | null {
    if (item.type === "file") {
      return item.name.toLowerCase().includes(searchTerm.toLowerCase()) &&
        (!extensions || extensions.includes(item.name.split(".").pop() || ""))
        ? item
        : null;
    }

    const matchingChildren = item.children
      .map((child) => child && search(child))
      .filter((result): result is Folder | File => result !== null);

    if (matchingChildren.length > 0) {
      return {
        ...item,
        children: matchingChildren,
      };
    }

    if (item.name.toLowerCase().includes(searchTerm.toLowerCase())) {
      return {
        ...item,
        children: [],
      };
    }

    return null;
  }

  return items
    .map((item) => search(item))
    .filter(
      (result): result is Folder | File =>
        result !== null && "children" in result && result.children.length > 0
    );
};

export async function GET(request: NextRequest) {
  const search = request.nextUrl.searchParams.get("query") || "";
  const extensions = request.nextUrl.searchParams.get("extension") || "";

  try {
    const directory = join(getInitialDirectory(), "downloads");
    const data = await recursiveFileSearch(directory);

    return NextResponse.json({
      data: searchInRecursive(data, search, extensions.split(",")),
    });
  } catch (error) {
    return NextResponse.json(error, { status: 500 });
  }
}
