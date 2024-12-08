"use client";

export interface Cancelable {
  clear(): void;
}

export const debounce = <T extends (...args: Parameters<T>) => ReturnType<T>>(
  func: T,
  wait = 166
) => {
  let timeout: ReturnType<typeof setTimeout>;

  const debounced = function (
    this: ThisParameterType<T>,
    ...args: Parameters<T>
  ) {
    clearTimeout(timeout);
    timeout = setTimeout(() => {
      func.apply(this, args);
    }, wait);
  };

  debounced.clear = () => {
    clearTimeout(timeout);
  };

  return debounced as unknown as T & Cancelable;
};
