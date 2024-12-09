import { Navbar } from "@/components/Navbar";
import { SidebarFile } from "@/components/SidebarFile";
import { SidebarProvider } from "@/components/ui/sidebar";
import { TooltipProvider } from "@/components/ui/tooltip";
import { classnames } from "@/librairies/utils";
import { FC, ReactNode } from "react";
import {
  PiBook,
  PiEqualizer,
  PiFolder,
  PiHouse,
  PiVideo,
} from "react-icons/pi";

export interface LayoutProps {
  children: ReactNode;
}

const Layout: FC<LayoutProps> = ({ children }) => {
  return (
    <TooltipProvider>
      <SidebarProvider>
        <div className="flex h-screen">
          <Navbar
            routes={[
              {
                name: "Home",
                href: "/",
                icon: <PiHouse size={30} />,
              },
              {
                name: "Explorer",
                href: "/downloads",
                icon: <PiFolder size={30} />,
              },
              {
                name: "divider",
              },
              {
                name: "Seedflix",
                href: "/seedflix",
                icon: <PiVideo size={30} />,
              },
              {
                name: "Seedbooks",
                href: "/epub",
                icon: <PiBook size={30} />,
              },
              {
                name: "Seedzik",
                href: "/seedzik",
                icon: <PiEqualizer size={30} />,
              },
            ]}
          />
        </div>
        <div className={classnames("flex-1")}>{children}</div>
        <SidebarFile />
      </SidebarProvider>
    </TooltipProvider>
  );
};

export default Layout;
