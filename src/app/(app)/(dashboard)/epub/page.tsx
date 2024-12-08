import { getFiles } from "@/actions/files";
import { BookList } from "@/components/BookList";
import { Metadata } from "next";
import { FC } from "react";

export const metadata: Metadata = {
  title: "Seedy Epub",
  description: "Seedy epub, the seediest epub reader",
};

export interface EpubProps {}

const Epub: FC<EpubProps> = async () => {
  const files = await getFiles("", "", "epub");

  return (
    <div>
      <BookList books={files.data} />
    </div>
  );
};

export default Epub;
