import { ModeToggle } from "@/components/ModeToggle";
import { FC, ReactNode } from "react";

export interface DashboardLayoutProps {
  children: ReactNode;
}

const DashboardLayout: FC<DashboardLayoutProps> = ({ children }) => {
  return (
    <div className="flex-1 p-4 overflow-y-auto">
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
