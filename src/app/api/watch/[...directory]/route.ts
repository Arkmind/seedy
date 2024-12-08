import { getTorrentLinkByName } from "@/actions/transmission";
import { isActionError } from "@/librairies/error";
import { getInitialDirectory, sanitizePath } from "@/librairies/utils";
import { promises as fs } from "fs";
import { NextRequest, NextResponse } from "next/server";
import { join } from "path";

export type Params = {
  directory: string[];
};

export async function GET(_: NextRequest, res: Response & { params: Params }) {
  const { directory } = res.params;

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

  try {
    const getContent = await fs.readdir(directoryString);

    const data = await Promise.all(
      getContent.map(async (file) => {
        const torrent = await getTorrentLinkByName(file);

        const stat = await fs.stat(join(directoryString, file));

        return {
          path: join(directoryString, file),
          name: file,
          torrent: isActionError(torrent) ? null : torrent,
          size: stat.size,
          date: stat.mtime,
          uid: stat.uid,
        };
      })
    );

    return NextResponse.json({
      data,
    });
  } catch (error) {
    return NextResponse.json(error, { status: 500 });
  }
}
