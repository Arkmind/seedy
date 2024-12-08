import { Metadata } from "next";
import { FC } from "react";

export const metadata: Metadata = {
  title: "Seedy Epub",
  description: "Seedy epub, the seediest epub reader",
};

export interface EpubProps {}

const Epub: FC<EpubProps> = () => {
  return <div className="h-screen w-full"></div>;
};

export default Epub;
