export interface Folder {
  name: string;
  type: "directory";
  children: (Folder | File | null)[];
  url?: string;
  download?: string;
  date?: Date;
}

export interface File {
  name: string;
  type: "file";
  size: number;
  url?: string;
  download?: string;
  date?: Date;
}

export type FileSystemItem = Folder | File;
