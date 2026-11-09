import { $, call, describeError, leave, show, signInWithGoogle, start, watchUser } from './firebase';

/**
 * Deleting a Shidur account from the web, as Google Play requires besides the in-app route
 * (ADR-059): the account's documents and the account itself, on the server.
 */
async function main() {
  const session = await start();
  let deleted = false;
  const message = (text: string, tone: 'error' | 'ok' | '' = '') => {
    const el = $('message');
    el.textContent = text;
    el.className = tone;
  };

  watchUser(session, (user) => {
    show('signed-out', !user && !deleted);
    show('signed-in', !!user);
    if (user) $('who').textContent = user.email ?? user.displayName ?? '';
  });

  $('sign-in').addEventListener('click', async () => {
    message('');
    try {
      await signInWithGoogle(session);
    } catch (e) {
      message(describeError(e), 'error');
    }
  });

  $('switch').addEventListener('click', () => void leave(session));

  $('delete').addEventListener('click', async () => {
    const confirmed = ($('confirm') as HTMLInputElement).checked;
    if (!confirmed) {
      message('סמן את התיבה כדי לאשר את המחיקה.', 'error');
      return;
    }
    const button = $('delete') as HTMLButtonElement;
    button.disabled = true;
    message('מוחק…');
    try {
      await call(session, 'deleteAccount', {});
      deleted = true;
      await leave(session);
      message('החשבון וכל הנתונים שלו נמחקו.', 'ok');
    } catch (e) {
      message(describeError(e), 'error');
    } finally {
      button.disabled = false;
    }
  });
}

main().catch(() => {
  $('message').textContent = 'הדף לא נטען. רענן ונסה שוב.';
});
