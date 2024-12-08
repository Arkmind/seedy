"use client";

export const downloadGeneratedFile = (
  fileName: string,
  content: string
): void => {
  const a = document.createElement("a");
  a.setAttribute("style", "display: none");
  document.body.appendChild(a);

  // Setup Blob url
  const blob = new Blob([content], {
    type: "octet/stream",
  });

  const url = URL.createObjectURL(blob);

  // Init <a> and force download
  a.href = url;
  a.download = fileName;
  a.click();

  // Destroy blob & <a> properly
  URL.revokeObjectURL(url);
  document.body.removeChild(a);
};
