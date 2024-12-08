import { Reader } from "@/components/Reader";
import { join } from "path";
import { FC } from "react";

export interface EpubProps {
  params: {
    path: string[];
  };
}

const Epub: FC<EpubProps> = ({ params: { path } }) => {
  return (
    <div className="h-screen w-full">
      <Reader url={join(`/api/downloads`, path.join("/"))} />
    </div>
  );
};

export default Epub;
