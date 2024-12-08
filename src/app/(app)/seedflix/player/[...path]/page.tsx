import { Player } from "@/components/Player";
import { Metadata } from "next";
import { FC } from "react";

export interface DirectoryProps {
  params: { path: string[] };
}

export const metadata: Metadata = {
  title: "Seedy Player",
  description: "Seedy player, watch your torrents",
};

const Directory: FC<DirectoryProps> = async ({ params }) => {
  const download = `/api/downloads/${params.path.join("/")}`;

  return (
    <div className="flex flex-col space-y-4 h-screen">
      <Player url={download} />
    </div>
  );
};

export default Directory;
