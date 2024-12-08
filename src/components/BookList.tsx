"use client";

import { classnames } from "@/librairies/utils";
import { File } from "@/types/filesystem";
import ePub from "epubjs";
import { FC, useEffect, useState } from "react";
import { Book } from "./Book";

export interface BookListProps {
  books: File[];
}

export const BookList: FC<BookListProps> = ({ books: defaultBooks }) => {
  const [books, setBooks] = useState<(File & { cover: string })[]>([]);

  console.log(books);

  const getImage = async (book: File) => {
    const epub = ePub(book.download || "");
    const cover = await epub.coverUrl();

    if (!cover) return;

    setBooks([
      ...books,
      {
        ...book,
        cover,
      },
    ]);
  };

  useEffect(() => {
    defaultBooks.forEach((book) => {
      getImage(book);
    });
  }, [defaultBooks]);

  return (
    <div
      className={classnames(
        "grid gap-8 transition-all",
        "grid-cols-2 md:grid-cols-3 lg:grid-cols-4"
      )}
    >
      {books.map((book) => (
        <Book key={book.name} book={book} cover={book.cover} />
      ))}
    </div>
  );
};
