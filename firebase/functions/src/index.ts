import { initializeApp } from 'firebase-admin/app';

initializeApp();

export { approvePairing, claimPairing, createPairing } from './pairing';
export { createProfile, deleteAccount, removeProfile } from './account';
