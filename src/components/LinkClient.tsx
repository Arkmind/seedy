"use client";

import { useRouter } from "next/navigation";
import React from "react";

export interface LinkClientProps {
  children: React.ReactNode | string;
  href?: string;
  back?: boolean;
  forward?: boolean;
  refresh?: boolean;
}

export const LinkClient: React.FC<LinkClientProps> = ({
  children,
  href,
  back,
  forward,
  refresh,
}) => {
  const router = useRouter();

  return (
    <a
      onClick={(e) => {
        e.preventDefault();
        if (back) {
          router.back();
        } else if (forward) {
          router.forward();
        } else if (refresh) {
          router.refresh();
        } else if (href) {
          router.push(href);
        }
      }}
    >
      {children}
    </a>
  );
};
