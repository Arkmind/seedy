import { explore } from "@/actions/files";
import { LinkClient } from "@/components/LinkClient";
import { Button } from "@/components/ui/button";
import { classnames } from "@/librairies/utils";
import { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";
import ptt from "parse-torrent-title";
import { join } from "path";
import { FC } from "react";
import { PiArrowLeft, PiPlay } from "react-icons/pi";

export const metadata: Metadata = {
  title: "Seedy Episodes",
  description: "Seedy episodes, watch your series",
};

export interface SeedflixEpisodesProps {
  params: {
    name: string;
  };
}

const SeedflixEpisodes: FC<SeedflixEpisodesProps> = async ({
  params: { name },
}) => {
  const files = await explore(join("/complete/", name));

  if (files.data.length === 0) {
    return (
      <div className="flex flex-col w-full flex-1 justify-center items-center space-y-8 py-40">
        <Image
          src="/cat.svg"
          alt="Empty folder"
          width={400}
          height={400}
          className="dark:invert"
        />
        <h2 className="text-xl font-bold">
          Seems like folder is empty, you can go back now 🥱
        </h2>
      </div>
    );
  }

  return (
    <div className="flex flex-col space-y-8">
      <div className="flex justify-start items-center space-x-4">
        <LinkClient back>
          <Button className="h-12" variant="outline">
            <PiArrowLeft size={24} /> Go back
          </Button>
        </LinkClient>
      </div>
      <div className="flex flex-1 flex-col space-y-4">
        {files.data.map(async (file) => {
          const f = ptt.parse(file.name);

          return (
            <Link
              href={`/seedflix/player/${file.url
                ?.split("/")
                .slice(2)
                .join("/")}`}
              key={file.name}
            >
              <div
                className={classnames(
                  "flex p-3 px-4 border rounded-md transition-all cursor-pointer",
                  "dark:border-neutral-700 hover:dark:bg-neutral-900",
                  "border-neutral-200 hover:bg-neutral-100"
                )}
              >
                <div className="flex flex-col flex-1 space-y-1">
                  <h1 className="text-xl font-bold">
                    Episode {f.episode} {f.title} {f.season}
                  </h1>
                  <p className="text-xs opacity-30">
                    {Object.values({
                      ...f,
                      title: undefined,
                      season: undefined,
                      episode: undefined,
                    })
                      .filter(Boolean)
                      .join(" - ")}
                  </p>
                </div>
                <div className="flex justify-end">
                  <Button className="ml-auto h-full">
                    Watch <PiPlay size={20} />
                  </Button>
                </div>
              </div>
            </Link>
          );
        })}
      </div>
    </div>
  );
};

export default SeedflixEpisodes;
