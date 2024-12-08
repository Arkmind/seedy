import { explore } from "@/actions/files";
import { FileList } from "@/components/FileList";
import { FC } from "react";

const Directory: FC = async () => {
  const files = await explore("/");

  return <FileList files={files.data} />;
};

export default Directory;
