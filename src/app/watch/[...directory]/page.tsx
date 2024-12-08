import { FC } from "react";

export interface DirectoryProps {
  params: { directory: string[] };
}

const Directory: FC<DirectoryProps> = ({ params }) => {
  const { directory } = params;

  return <></>;
};

export default Directory;
