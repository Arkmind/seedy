"use client";

import { useLayout } from "@/hooks/useLayout";
import { FC } from "react";
import { PiDotsNine, PiList, PiSquaresFour } from "react-icons/pi";
import { Tabs, TabsList, TabsTrigger } from "./ui/tabs";

export const TabsLayout: FC = () => {
  const layout = useLayout();

  return (
    <Tabs value={layout.layout}>
      <TabsList className="h-12">
        <TabsTrigger
          className="h-10"
          value="list"
          onClick={() => layout.setLayout("list")}
        >
          <PiList size={24} />
        </TabsTrigger>
        <TabsTrigger
          className="h-10"
          value="grid"
          onClick={() => layout.setLayout("grid")}
        >
          <PiSquaresFour size={24} />
        </TabsTrigger>
        <TabsTrigger
          className="h-10"
          value="dots"
          onClick={() => layout.setLayout("dots")}
        >
          <PiDotsNine size={24} />
        </TabsTrigger>
      </TabsList>
    </Tabs>
  );
};
