import { FC, ReactNode } from "react";

export interface SeedzikLayoutProps {
  children: ReactNode;
}

const SeedzikLayout: FC<SeedzikLayoutProps> = ({ children }) => {
  return (
    <div className="flex flex-col w-full h-screen">
      <div className="flex-1 rounded-bl-lg bg-neutral-950">{children}</div>
      <div className="h-32 bg-black">
        <div className="flex items-center justify-center h-full w-full">
          <div className="w-[calc(33.333333%-9rem)]"></div>
          <div className="w-1/3 text-center"></div>
          <div className="w-1/3"></div>
        </div>
      </div>
    </div>
  );
};

export default SeedzikLayout;
