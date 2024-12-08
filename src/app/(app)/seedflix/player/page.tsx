import { redirect } from "next/navigation";
import { FC } from "react";

export interface SeedflixProps {}

const Seedflix: FC<SeedflixProps> = () => {
  redirect("/seedflix");
};

export default Seedflix;
