import { initializeApp } from 'firebase-admin/app';

initializeApp();

export { approvePairing, claimPairing, createPairing } from './pairing';
export { deleteAccount, ensureProfile } from './account';
