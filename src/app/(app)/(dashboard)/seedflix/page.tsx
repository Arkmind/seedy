import { searchMovie } from "@/actions/tmdb";
import { getTorrentList } from "@/actions/transmission";
import { Movie } from "@/components/Movie";
import { isActionError } from "@/librairies/error";
import { classnames } from "@/librairies/utils";
import ptt from "parse-torrent-title";
import { FC } from "react";

export interface SeedflixProps {}

const Seedflix: FC<SeedflixProps> = async () => {
  const torrents = await getTorrentList();

  if (isActionError(torrents)) {
    console.error(torrents);
    return <></>;
  }

  const ptorrents = torrents.arguments.torrents
    .map((torrent) => {
      return {
        ...ptt.parse(torrent.name),
        name: torrent.name,
      };
    })
    .filter((t) => t.title && t.resolution);

  const search = await Promise.all(
    ptorrents.map(async (t) => {
      const search = await searchMovie(`${t.title} ${t.season}`);
      return {
        search,
        torrent: t,
      };
    })
  );

  const moviesTorrent = search
    .filter((r) => r.search.results.length > 0)
    .map((r) => ({
      movie: r.search.results[0],
      torrent: r.torrent,
    }));

  return (
    <div
      className={classnames(
        "grid gap-8 transition-all",
        "grid-cols-2 md:grid-cols-3 lg:grid-cols-4"
      )}
    >
      {moviesTorrent.map((m) => (
        <Movie key={m.torrent.title} movie={m.movie} torrent={m.torrent} />
      ))}
    </div>
  );
};

export default Seedflix;