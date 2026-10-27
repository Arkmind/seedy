import { del, get, post } from '../../../api/client';
import type { BaseItem, ItemsResult } from '../../../api/types';
import { currentUserId, isAdmin, userStore } from '../../../lib/app-state';

/* Jellyfin and Seedy calls behind the item actions: playlists, collections, refresh, watched, delete. */

const uid = () => currentUserId();

/* Who may do what. Jellyfin asks for an admin to edit, identify, change images or refresh; collections take the
   collection permission; deleting is per item (CanDelete). */
export const canManage = () => isAdmin();
export const canCollect = () => isAdmin() || !!userStore.get()?.Policy?.EnableCollectionManagement;
export const canDelete = (item: BaseItem) => !!item.CanDelete;

// ── Playlists ──

export interface PlaylistInfo { OpenAccess?: boolean; Shares?: { UserId: string; CanEdit: boolean }[]; ItemIds?: string[] }

export const getPlaylists = () =>
  get<ItemsResult>(`/Users/${uid()}/Items`, { IncludeItemTypes: 'Playlist', Recursive: true, SortBy: 'DateLastContentAdded,SortName', SortOrder: 'Descending', Fields: 'ChildCount,RecursiveItemCount', enableImages: false });
export const getPlaylist = (id: string) => get<PlaylistInfo>(`/Playlists/${id}`);
export const getPlaylistItems = (id: string) =>
  get<ItemsResult>(`/Playlists/${id}/Items`, { userId: uid(), Fields: 'ParentId', enableImages: false, enableUserData: false });
export const addToPlaylist = (id: string, ids: string[]) => post<void>(`/Playlists/${id}/Items`, undefined, { ids: ids.join(','), userId: uid() });
export const createPlaylist = (body: { Name: string; Ids: string[]; Users: { UserId: string; CanEdit: boolean }[]; IsPublic: boolean; MediaType?: 'Audio' | 'Video' }) =>
  post<{ Id: string }>('/Playlists', { ...body, UserId: uid() });

/* The people a playlist can be shared with (Seedy; null without the plugin). */
export interface SharePerson { id: string; name: string; hasImage: boolean }
export const getShareWith = () => get<SharePerson[]>('/Seedy/Items/ShareWith').catch(() => null);

// ── Collections ──

export const getCollections = () =>
  get<ItemsResult>(`/Users/${uid()}/Items`, { IncludeItemTypes: 'BoxSet', Recursive: true, SortBy: 'SortName', Fields: 'ChildCount', enableImages: false });
export const getChildren = (parentId: string) =>
  get<ItemsResult>(`/Users/${uid()}/Items`, { ParentId: parentId, Fields: 'ParentId', enableImages: false, enableUserData: false });
export const addToCollection = (id: string, ids: string[]) => post<void>(`/Collections/${id}/Items`, undefined, { ids: ids.join(',') });
export const createCollection = (name: string, ids: string[]) => post<{ Id: string }>('/Collections', undefined, { name, ids: ids.join(','), isLocked: false });

// ── Library actions ──

export const refreshItem = (id: string) =>
  post<void>(`/Items/${id}/Refresh`, undefined, { metadataRefreshMode: 'FullRefresh', imageRefreshMode: 'FullRefresh', replaceAllMetadata: false, replaceAllImages: false });
export const markPlayed = (id: string, on = true) => (on ? post<void>(`/UserPlayedItems/${id}`, undefined, { userId: uid() }) : del<void>(`/UserPlayedItems/${id}`, { userId: uid() }));

// ── Delete ──

export interface DeleteGroup { kind: 'season' | 'media' | 'extras'; id?: string; name?: string; index?: number; episodes: number; files: number; bytes: number; height?: number; codec?: string }
export interface DeletePreviewItem {
  id: string; name: string; type: string; canDelete: boolean; bytes: number; paths: string[]; groups: DeleteGroup[];
  seerr?: { mediaType: 'movie' | 'tv'; tmdbId: number; arr?: 'Sonarr' | 'Radarr'; monitored?: boolean };
}
export interface DeletePreview { items: DeletePreviewItem[]; bytes: number }

export const getDeletePreview = (ids: string[]) => get<DeletePreview>('/Seedy/Items/DeletePreview', { ids: ids.join(',') });
export const deleteItems = (ids: string[]) => del<void>('/Items', { ids: ids.join(',') });
export const clearInSeerr = (mediaType: 'movie' | 'tv', tmdbId: number) => del<void>(`/Seedy/Requests/Media/${mediaType}/${tmdbId}`);
