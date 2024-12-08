import { explore } from "@/actions/files";
import { FileList } from "@/components/FileList";
import { Metadata } from "next";
import Image from "next/image";
import { FC } from "react";

export const metadata: Metadata = {
  title: "Seedy Explorer",
  description: "Seedy explorer, explore your files",
};

export interface DirectoryProps {
  params: { directory: string[] };
}

const Directory: FC<DirectoryProps> = async ({ params }) => {
  const files = await explore(params.directory.join("/"));

  return (
    <div className="flex flex-col space-y-4">
      <FileList files={files.data} />
      {files.data.length === 0 && (
        <div className="flex flex-col w-full flex-1 justify-center items-center space-y-8 py-40">
          <Image
            src="/cat.svg"
            alt="Empty folder"
            width={400}
            height={400}
            className="dark:invert"
          />
          <h2 className="text-xl font-bold">
            Seems like folder is empty, you can go back now 🥱
          </h2>
        </div>
      )}
    </div>
  );
};

export default Directory;
