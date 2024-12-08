import { getInitialDirectory, sanitizePath } from "@/librairies/utils";
import archiver from "archiver";
import { promises as fs } from "fs";
import mime from "mime-types";
import { NextRequest, NextResponse } from "next/server";
import { join } from "path";

export type Params = {
  directory: string[];
};

export async function GET(
  request: NextRequest,
  res: Response & { params: Params }
) {
  const { directory } = res.params;
  const searchParams = request.nextUrl.searchParams;

  const zip = (searchParams.get("zip") || "") === "true";

  const directoryString = join(
    getInitialDirectory(),
    "downloads",
    sanitizePath(directory.join("/"))
  );

  const exists = await fs
    .access(directoryString)
    .then(() => true)
    .catch(() => false);

  if (!exists) {
    return NextResponse.json({ error: "Directory not found" }, { status: 404 });
  }

  const isFile = await fs.stat(directoryString).then((stat) => stat.isFile());

  if (zip) {
    try {
      const archive = archiver("zip", {
        zlib: { level: 9 },
      });

      const chunks: Buffer[] = [];

      archive.on("warning", (err) => {
        if (err.code === "ENOENT") {
          console.warn("Archive warning:", err);
        } else {
          throw err;
        }
      });

      archive.on("error", (err) => {
        throw err;
      });
      archive.on("data", (chunk) => {
        chunks.push(Buffer.from(chunk));
      });

      if (isFile) {
        archive.file(directoryString, {
          name: directoryString.split("/").pop() || "unknown",
        });
      } else {
        archive.directory(directoryString, false);
      }

      const archiveFinalized = new Promise<Buffer>((resolve) => {
        archive.on("end", () => {
          const finalBuffer = Buffer.concat(chunks);
          resolve(finalBuffer);
        });
      });

      archive.finalize();
      const zipBuffer = await archiveFinalized;

      const headers = new Headers();
      headers.set("Content-Type", "application/zip");
      headers.set(
        "Content-Disposition",
        `attachment; filename="${directoryString.split("/").pop()}.zip"`
      );
      headers.set("Content-Length", zipBuffer.length.toString());

      return new NextResponse(zipBuffer, {
        status: 200,
        headers,
      });
    } catch {
      return NextResponse.json(
        { error: "Failed to create zip file" },
        { status: 500 }
      );
    }
  }

  if (isFile) {
    const fileBuffer = await fs.readFile(directoryString);

    const filename = directoryString.split("/").pop() || "";
    const type = mime.contentType(filename) || "application/octet-stream";

    const headers = new Headers();
    headers.set("Content-Disposition", `attachment; filename="${filename}"`);
    headers.set("Content-Type", type);
    headers.set("Content-Length", fileBuffer.length.toString());

    // Return the file as a stream
    return new NextResponse(fileBuffer, {
      status: 200,
      headers,
    });
  }

  try {
    const getContent = await fs.readdir(directoryString);

    const data = await Promise.all(
      getContent.map(async (file) => {
        const stat = await fs.stat(join(directoryString, file));

        if (stat.isDirectory()) {
          return {
            name: file,
            type: "directory",
            children: [],
            url: join("/downloads", sanitizePath(directory.join("/")), file),
            download: join(
              "/api/downloads",
              sanitizePath(directory.join("/")),
              file + "?zip=true"
            ),
            date: stat.mtime,
          };
        } else {
          return {
            name: file,
            type: "file",
            size: stat.size,
            url: join("/downloads", sanitizePath(directory.join("/")), file),
            download: join(
              "/api/downloads",
              sanitizePath(directory.join("/")),
              file
            ),
            date: stat.mtime,
          };
        }
      })
    );

    return NextResponse.json({
      data,
    });
  } catch (error) {
    return NextResponse.json(error, { status: 500 });
  }
}
