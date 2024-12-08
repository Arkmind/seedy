export const getInitialDirectory = () => {
  return process.env.NODE_ENV === "production" ? "/app/" : "/srv/";
};

/**
 * Sanitizes a file path by:
 * - Removing path traversal attempts (../)
 * - Removing duplicate slashes
 * - Removing leading/trailing slashes
 * - Removing special characters that could be used for command injection
 * - Converting backslashes to forward slashes
 *
 * @param path - The path string to sanitize
 * @returns A sanitized version of the path
 * @throws Error if the path is null, undefined, or not a string
 */
export const sanitizePath = (path: string): string => {
  if (typeof path !== "string") {
    throw new Error("Path must be a string");
  }

  const sanitized = path
    .replace(/\\/g, "/")
    .trim()
    .replace(/^\/+|\/+$/g, "")
    .replace(/\/+/g, "/");

  const segments = sanitized.split("/");
  const safeSegments = segments.filter((segment) => {
    if (!segment || segment === "." || segment === "..") {
      return false;
    }

    if (/[<>:"|?*\x00-\x1F]/.test(segment)) {
      return false;
    }

    return true;
  });

  return safeSegments.join("/");
};

export const classnames = (
  ...classes: (string | undefined | boolean | null)[]
) => {
  return classes.filter(Boolean).join(" ");
};

export const formatBytes = (bytes: number, decimals = 2) => {
  if (!+bytes) return "0 Bytes";

  const k = 1024;
  const dm = decimals < 0 ? 0 : decimals;
  const sizes = [
    "Bytes",
    "KiB",
    "MiB",
    "GiB",
    "TiB",
    "PiB",
    "EiB",
    "ZiB",
    "YiB",
  ];

  const i = Math.floor(Math.log(bytes) / Math.log(k));

  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(dm))} ${sizes[i]}`;
};
