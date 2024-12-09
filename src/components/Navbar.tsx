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

export type Divider = {
  name: "divider";
};

const isDivider = (route: Route | Divider): route is Divider =>
  route.name === "divider";

export interface NavbarProps {
  routes: (Route | Divider)[];
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
        "flex flex-col items-center justify-center space-y-2 size-24 p-2 rounded-xl hover:dark:bg-neutral-900 hover:bg-neutral-100 transition-all cursor-pointer",
        active
          ? "dark:border-neutral-50 border-neutral-950 border-2 dark:bg-black bg-white"
          : "dark:bg-neutral-950 bg-neutral-50"
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
    <div
      className={classnames(
        "w-36",
        pathname.startsWith("/seedflix/player") && "absolute z-50"
      )}
    >
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
                  {routes.map((route, i) =>
                    isDivider(route) ? (
                      <hr key={`divider-nav-${i}`} className="w-4/5" />
                    ) : (
                      <Item
                        key={route.href}
                        active={isActive(route.href)}
                        {...route}
                      />
                    )
                  )}
                </div>
              </div>
            </SidebarGroupContent>
          </SidebarGroup>
        </SidebarContent>
        {pathname.startsWith("/epub/") && (
          <SidebarFooter className="items-center pb-8 px-6">
            <ModeToggle className="w-full dark:bg-neutral-950 hover:dark:bg-black" />
          </SidebarFooter>
        )}
      </Sidebar>
    </div>
  );
};
