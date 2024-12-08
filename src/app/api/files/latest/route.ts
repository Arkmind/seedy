import { getInitialDirectory } from "@/librairies/utils";
import { File, FileSystemItem, Folder } from "@/types/filesystem";
import { promises as fs } from "fs";
import { NextRequest, NextResponse } from "next/server";
import { join } from "path";

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

const getFiles = ({ items }: { items: FileSystemItem[] }): File[] => {
  const allFiles: File[] = [];

  function collectFiles(item: FileSystemItem) {
    if (item.type === "file") {
      if (item.date) {
        allFiles.push(item);
      }
    } else {
      for (const child of item.children) {
        if (child) {
          collectFiles(child);
        }
      }
    }
  }

  for (const item of items) {
    collectFiles(item);
  }

  return allFiles;
};

export async function GET(request: NextRequest) {
  const skip = Number(request.nextUrl.searchParams.get("skip")) || 0;
  const take = Number(request.nextUrl.searchParams.get("take")) || 8;
  const query = request.nextUrl.searchParams.get("query") || "";
  const extensions = request.nextUrl.searchParams.get("extension") || "";
  const order = request.nextUrl.searchParams.get("order") || "desc";

  try {
    const directory = join(getInitialDirectory(), "downloads");
    const data = await recursiveFileSearch(directory);
    const files = getFiles({
      items: data,
    });

    const filteredFiles = files
      .filter((file) => {
        if (!query) {
          return true;
        }

        return file.name.toLowerCase().includes(query.toLowerCase());
      })
      .filter((file) => {
        if (!extensions || extensions.length === 0) {
          return true;
        }

        return extensions.includes(file.name.split(".").pop() || "");
      })
      .sort((a, b) => {
        if (!a.date || !b.date) {
          return 0;
        }

        if (order === "asc") {
          return a.date.getTime() - b.date.getTime();
        }

        return b.date.getTime() - a.date.getTime();
      });

    const sliced = filteredFiles.slice(skip, take + skip);

    return NextResponse.json({
      data: sliced,
      count: filteredFiles.length,
    });
  } catch (error) {
    return NextResponse.json(error, { status: 500 });
  }
}
