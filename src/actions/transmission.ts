import { ActionError } from "@/types/error";
import { join } from "path";

export interface TransmissionTorrent {
  id: number;
  name: string;
  magnetLink?: string;
  hashString: string;
}

export interface TransmissionResponse {
  arguments: {
    torrents: TransmissionTorrent[];
  };
  result: string;
}

export const getSessionID = async () => {
  try {
    const response = await fetch(
      join(process.env.TRANSMISSION_URL || "", "/transmission/rpc"),
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Basic ${btoa(
            `${process.env.TRANSMISSION_USERNAME}:${process.env.TRANSMISSION_PASSWORD}`
          )}`,
        },
      }
    );

    return response.headers.get("X-Transmission-Session-Id") || "";
  } catch (error) {
    if (error instanceof Response) {
      return error.headers.get("X-Transmission-Session-Id") || "";
    }

    throw error;
  }
};

const generateHeaders = async (): Promise<HeadersInit> => {
  const sessionId = await getSessionID();

  return {
    "x-transmission-session-id": sessionId,
    "Content-Type": "application/json",
    Authorization: `Basic ${btoa(
      `${process.env.TRANSMISSION_USERNAME}:${process.env.TRANSMISSION_PASSWORD}`
    )}`,
  };
};

export const getTorrentList = async (): Promise<
  TransmissionResponse | ActionError
> => {
  try {
    const headers = await generateHeaders();

    const response = await fetch(
      join(process.env.TRANSMISSION_URL || "", "/transmission/rpc"),
      {
        method: "POST",
        headers,
        body: JSON.stringify({
          method: "torrent-get",
          arguments: {
            fields: ["id", "name", "magnetLink", "hashString"],
          },
        }),
      }
    );

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }

    return (await response.json()) as TransmissionResponse;
  } catch (error) {
    console.error("Error fetching torrent list:", error);
    return {
      message: "An error occurred while fetching the torrent list",
      status: 500,
      error: "Internal Server Error",
    };
  }
};

export const getTorrentLinkByName = async (
  torrentName: string
): Promise<string | null | ActionError> => {
  try {
    const headers = await generateHeaders();

    const response = await fetch(
      join(process.env.TRANSMISSION_URL || "", "/transmission/rpc"),
      {
        method: "POST",
        headers,
        body: JSON.stringify({
          method: "torrent-get",
          arguments: {
            fields: ["id", "name", "magnetLink", "hashString"],
          },
        }),
      }
    );

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }

    const data = (await response.json()) as TransmissionResponse;

    // Find the torrent by name
    const torrent = data.arguments.torrents.find(
      (t) => t.name.toLowerCase() === torrentName.toLowerCase()
    );

    if (!torrent) {
      return null;
    }

    // If magnetLink is available, return it
    if (torrent.magnetLink) {
      return torrent.magnetLink;
    }

    // If no magnet link is available, construct a magnet link using the hash
    return `magnet:?xt=urn:btih:${torrent.hashString}`;
  } catch (error) {
    console.error("Error fetching torrent link:", error);
    return {
      message: "An error occurred while fetching the torrent",
      status: 500,
      error: "Internal Server Error",
    };
  }
};
