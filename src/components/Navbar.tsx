"use client";

import { classnames } from "@/librairies/utils";
import Image from "next/image";
import { usePathname, useRouter } from "next/navigation";
import React, { FC, useEffect } from "react";
import { ModeToggle } from "./ModeToggle";
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupContent,
  SidebarHeader,
  useSidebar,
} from "./ui/sidebar";

export type Route = {
  name: string;
  href: string;
  icon?: React.ReactNode;
};

export interface NavbarProps {
  routes: Route[];
}

const Item: FC<Partial<Route> & { active?: boolean }> = ({
  name,
  href,
  icon,
  active,
}) => {
  const router = useRouter();

  return (
    <div
      className={classnames(
        "flex flex-col items-center justify-center space-y-2 size-24 dark:bg-neutral-950 p-2 rounded-xl bg-neutral-50 hover:dark:bg-black transition-all cursor-pointer",
        active && "dark:border-white border-neutral-200 border-2"
      )}
      onClick={() => router.push(href || "")}
    >
      {icon}
      <a href={href} className="font-bold text-xs">
        {name?.toUpperCase()}
      </a>
    </div>
  );
};

export const Navbar: FC<NavbarProps> = ({ routes }) => {
  const pathname = usePathname();
  const sidebar = useSidebar();

  const isActive = (routePath: string) => {
    // Exact match for root path
    if (routePath === "/") {
      return pathname === "/";
    }
    // For other paths, use startsWith to handle nested routes
    return pathname.startsWith(routePath);
  };

  useEffect(() => {
    sidebar.setOpen(true);
  }, [pathname]);

  return (
    <div className={classnames("w-36", "absolute z-50")}>
      <Sidebar>
        <SidebarHeader className="items-center pt-8">
          <Image
            src="/jelly-white.svg"
            priority
            alt="logo"
            width={0}
            height={0}
            sizes="100vw"
            className="dark:invert w-14"
          />
        </SidebarHeader>
        <SidebarContent>
          <SidebarGroup>
            <SidebarGroupContent className="py-4">
              <div className="flex flex-col items-center">
                <div className="flex flex-1 flex-col items-center space-y-4">
                  {routes.map((route) => (
                    <Item
                      key={route.href}
                      active={isActive(route.href)}
                      {...route}
                    />
                  ))}
                </div>
              </div>
            </SidebarGroupContent>
          </SidebarGroup>
        </SidebarContent>
        {pathname.startsWith("/epub") && (
          <SidebarFooter className="items-center pb-8 px-6">
            <ModeToggle className="w-full dark:bg-neutral-950 hover:dark:bg-black" />
          </SidebarFooter>
        )}
      </Sidebar>
    </div>
  );
};
