"use client";

import { classnames } from "@/librairies/utils";
import { File } from "@/types/filesystem";
import Image from "next/image";
import Link from "next/link";
import { FC } from "react";

export interface BookProps {
  book: File;
  cover: string;
}

export const Book: FC<BookProps> = ({ book, cover }) => {
  return (
    <Link href={`/epub/${book.url?.split("/").slice(2).join("/")}`}>
      <div
        className={classnames(
          "flex flex-col space-y-3 p-2 rounded-xl transition-all cursor-pointer border-2 h-full",
          "bg-white hover:bg-neutral-100",
          "dark:bg-neutral-950 hover:dark:bg-neutral-800"
        )}
      >
        <div className="w-full h-full">
          <Image
            src={cover}
            alt={book.name}
            priority
            width={0}
            height={0}
            sizes="100vw"
            className="object-cover w-full h-full rounded-md"
          />
        </div>
        <div className="flex-1 px-2">
          <h2 className="text-lg font-bold">{book.name}</h2>
        </div>
      </div>
    </Link>
  );
};
