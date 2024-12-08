import { Navbar } from "@/components/Navbar";
import { SidebarFile } from "@/components/SidebarFile";
import { SidebarProvider } from "@/components/ui/sidebar";
import { TooltipProvider } from "@/components/ui/tooltip";
import { FC, ReactNode } from "react";
import { PiBook, PiFolder, PiHouse, PiMagnet, PiVideo } from "react-icons/pi";

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
                name: "Seedflix",
                href: "/seedflix",
                icon: <PiVideo size={30} />,
              },
              {
                name: "Seedbooks",
                href: "/ebooks",
                icon: <PiBook size={30} />,
              },
              {
                name: "Torrents",
                href: "/torrents",
                icon: <PiMagnet size={30} />,
              },
            ]}
          />
        </div>
        <div className="flex-1">{children}</div>
        <SidebarFile />
      </SidebarProvider>
    </TooltipProvider>
  );
};

export default Layout;
