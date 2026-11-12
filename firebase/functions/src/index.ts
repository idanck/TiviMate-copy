import { initializeApp } from 'firebase-admin/app';

initializeApp();

export { approvePairing, claimPairing, createPairing } from './pairing';
export { deleteAccount, ensureProfile, signOutEverywhere } from './account';
export {
  ytChannels,
  ytLinkCode,
  ytLinkPoll,
  ytLinkStart,
  ytLinkStatus,
  ytmChart,
  ytmLibrary,
  ytmPlaylist,
  ytmRate,
  ytmSearch,
  ytResolve,
  ytSearch,
  ytSubscriptions,
  ytUnlink,
} from './youtube';
export { pcSearch, pcTrending } from './podcasts';
