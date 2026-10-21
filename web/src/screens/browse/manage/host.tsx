import type { BaseItem } from '../../../api/types';
import { createStore, useStore } from '../../../lib/store';
import { DeleteDialog } from './delete';
import { EditMetadataDialog } from './edit-metadata';
import { IdentifyDialog } from './identify';
import { ImagesDialog } from './images';
import { NewPlaylistDialog } from './playlist';

/* Item dialogs open from anywhere (the details More menu, the library's selection bar) through one host mounted
   by the app. Pages watch `manageChanged` to reload what a dialog changed. */

export type ManageKind = 'metadata' | 'identify' | 'images' | 'playlist' | 'delete';

interface Open { kind: ManageKind; items: BaseItem[] }

const openStore = createStore<Open | null>(null);

/* Bumped after a dialog saved something; `deleted` lists the ids that are gone. */
export const manageChanged = createStore<{ n: number; deleted: string[] }>({ n: 0, deleted: [] });

export function openManage(kind: ManageKind, items: BaseItem | BaseItem[]) {
  openStore.set({ kind, items: Array.isArray(items) ? items : [items] });
}

function changed(deleted: string[] = []) {
  manageChanged.set((c) => ({ n: c.n + 1, deleted }));
}

export function ManageHost() {
  const open = useStore(openStore);
  if (!open) return null;
  const close = () => openStore.set(null);
  const done = (deleted?: string[]) => { close(); changed(deleted); };
  const [item] = open.items;
  switch (open.kind) {
    case 'metadata': return <EditMetadataDialog item={item} onClose={close} onDone={() => done()} />;
    case 'identify': return <IdentifyDialog item={item} onClose={close} onDone={() => done()} />;
    case 'images': return <ImagesDialog item={item} onClose={close} onDone={() => done()} />;
    case 'playlist': return <NewPlaylistDialog items={open.items} onClose={close} onDone={() => done()} />;
    case 'delete': return <DeleteDialog items={open.items} onClose={close} onDone={(ids) => done(ids)} />;
  }
}
