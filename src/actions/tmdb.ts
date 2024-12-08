import { Movie, TMDB, TV } from "tmdb-ts";

const tmdb = new TMDB(process.env.TMDB_ACCESS_TOKEN || "");

export const searchMovie = async (query: string) => {
  const response = await tmdb.search.multi({
    query,
  });

  const results = response.results.filter(
    (result) => result.media_type === "movie" || result.media_type === "tv"
  ) as (Movie | TV)[];

  return {
    ...response,
    results,
  };
};

export const searchTV = async (query: string) => {
  return await tmdb.search.tvShows({
    query,
  });
};

export const getEpisodes = async (id: number, season: number) => {
  return await tmdb.tvSeasons.externalIds({
    seasonNumber: season,
    tvShowID: id,
  });
};
