"use client";

import usePageBottom from "@/hooks/usePageBottom";
import { debounce } from "@/librairies/debounce";
import { FC, useEffect, useMemo, useState } from "react";
import { TabsLayout } from "./TabsLayout";
import { Input } from "./ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "./ui/select";

export interface FileFilterProps {
  onChange: (
    reset: boolean,
    query: string,
    order: string,
    extension: string,
    length?: string
  ) => void;
}

export const FileFilter: FC<FileFilterProps> = ({ onChange }) => {
  const [query, setQuery] = useState("");
  const [order, setOrder] = useState<"desc" | "asc">("desc");
  const [extension, setExtension] = useState<string>("");

  const pageBottom = usePageBottom();

  // Create a stable debounced version of fetchMore
  const onChangeDebounced = useMemo(() => debounce(onChange, 300), []);

  useEffect(() => {
    onChangeDebounced(true, query, order, extension, "0");
  }, [query, order, extension]);

  useEffect(() => {
    onChangeDebounced(false, query, order, extension);
  }, [pageBottom]);

  return (
    <div className="flex">
      <div className="flex flex-1 justify-start items-center space-x-4">
        <Input
          type="search"
          placeholder="Search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          className="dark:bg-neutral-900 h-12"
        />
        <Select
          defaultValue="desc"
          value={order}
          onValueChange={(v) => setOrder(v as "desc" | "asc")}
        >
          <SelectTrigger className="dark:bg-neutral-900 w-32 h-12">
            <SelectValue placeholder="Order" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="desc">Latest</SelectItem>
            <SelectItem value="asc">Oldest</SelectItem>
          </SelectContent>
        </Select>
        <Select
          defaultValue="none"
          value={extension || "none"}
          onValueChange={(v) => setExtension(v)}
        >
          <SelectTrigger className="dark:bg-neutral-900 w-32 h-12">
            <SelectValue placeholder="Type" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="none">All</SelectItem>
            <SelectItem value="mp4,mkv,avi">Video</SelectItem>
            <SelectItem value="epub">E-book</SelectItem>
          </SelectContent>
        </Select>
      </div>
      <div className="flex flex-1 justify-end">
        <TabsLayout />
      </div>
    </div>
  );
};
