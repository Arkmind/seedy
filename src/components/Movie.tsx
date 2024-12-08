"use client";

import { classnames } from "@/librairies/utils";
import Image from "next/image";
import Link from "next/link";
import { DefaultParserResult } from "parse-torrent-title";
import { FC } from "react";
import {
  PiStar,
  PiStarFill,
  PiStarFourFill,
  PiStarHalfFill,
} from "react-icons/pi";
import { Movie as TMDBMovie, TV } from "tmdb-ts";

export interface MovieProps {
  movie: TMDBMovie | TV;
  torrent: DefaultParserResult & { name: string };
}

const isMovie = (movie: TMDBMovie | TV): movie is TMDBMovie => {
  return "title" in movie;
};

export const Movie: FC<MovieProps> = ({ movie, torrent }) => {
  return (
    <Link
      href={
        isMovie(movie)
          ? `/seedflix/player/${torrent.name}`
          : `/seedflix/episodes/${torrent.name}`
      }
    >
      <div
        className={classnames(
          "flex flex-col space-y-3 dark:bg-neutral-950 p-2 rounded-xl bg-white hover:dark:bg-neutral-800 hover:bg-neutral-100 transition-all cursor-pointer border-2"
        )}
      >
        <Image
          src={`https://image.tmdb.org/t/p/w500${movie.poster_path}`}
          alt={isMovie(movie) ? movie.title : movie.name}
          priority
          width={0}
          height={0}
          sizes="100vw"
          className="object-cover w-full h-full rounded-md"
        />
        <div className="flex-1 px-2">
          <h2 className="text-lg font-bold">
            {isMovie(movie) ? movie.title : movie.name}
          </h2>
          <p className="text-xs opacity-30 -mt-1">
            {torrent.title} - {torrent.resolution}
          </p>
        </div>
        <div className="flex-1 flex space-x-2 items-center px-2 pb-2">
          <div className="flex space-x-1 items-center">
            {movie.vote_average > 7.5 ? (
              <PiStarFill color="#eab308" className="inline" />
            ) : movie.vote_average > 5 ? (
              <PiStarFourFill color="#eab308" className="inline" />
            ) : movie.vote_average > 2.5 ? (
              <PiStarHalfFill color="#eab308" className="inline" />
            ) : (
              <PiStar color="#eab308" className="inline" />
            )}
            <p className="text-sm line-clamp-3">
              {movie.vote_average.toFixed(1)}
            </p>
          </div>
          <p>|</p>
          <p className="text-sm line-clamp-3">
            {isMovie(movie) ? movie.release_date : movie.first_air_date}
          </p>
        </div>
      </div>
    </Link>
  );
};
