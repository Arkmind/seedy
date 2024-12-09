import { ModeToggle } from "@/components/ModeToggle";
import { classnames } from "@/librairies/utils";
import { FC, ReactNode } from "react";

export interface DashboardLayoutProps {
  children: ReactNode;
}

const DashboardLayout: FC<DashboardLayoutProps> = ({ children }) => {
  return (
    <div
      className={classnames(
        "flex-1 p-4 bg-neutral-50 dark:bg-neutral-950 overflow-y-auto h-screen",
        "[&::-webkit-scrollbar]:w-2 [&::-webkit-scrollbar-thumb]:rounded-md",
        "[&::-webkit-scrollbar-track]:bg-neutral-50 [&::-webkit-scrollbar-thumb]:bg-gray-300",
        "dark:[&::-webkit-scrollbar-track]:bg-neutral-950 dark:[&::-webkit-scrollbar-thumb]:bg-neutral-900"
      )}
    >
      <div className="px-16 py-6">
        <div className="flex">
          <div className="flex-1">
            <h1 className="text-3xl font-black">Seedy</h1>
          </div>
          <div className="flex justify-end items-center space-x-4">
            <ModeToggle />
          </div>
        </div>
        <hr className="my-8" />
        {children}
      </div>
    </div>
  );
};

export default DashboardLayout;
