import { ActionError } from "@/types/error";

export const isActionError = (value: unknown): value is ActionError => {
  if (typeof value !== "object" || value === null) {
    return false;
  }

  const obj = value as ActionError;

  return (
    typeof obj.message === "string" &&
    typeof obj.status === "number" &&
    typeof obj.error === "string"
  );
};
