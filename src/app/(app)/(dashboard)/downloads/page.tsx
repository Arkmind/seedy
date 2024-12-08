import { explore } from "@/actions/files";
import { FileList } from "@/components/FileList";
import { Metadata } from "next";
import { FC } from "react";

export const metadata: Metadata = {
  title: "Seedy Explorer",
  description: "Seedy explorer, explore your files",
};

const Directory: FC = async () => {
  const files = await explore("/");

  return <FileList files={files.data} />;
};

export default Directory;
