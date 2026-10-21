import type { BaseItem } from '../../../api/types';

export function DeleteDialog(_: { items: BaseItem[]; onClose: () => void; onDone: (deleted: string[]) => void }) {
  return null;
}
