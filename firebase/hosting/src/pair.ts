import { $, call, describeError, leave, show, signInWithGoogle, start, watchUser } from './firebase';

/**
 * The phone's half of signing a television in (ADR-059): sign in with Google, check the code the
 * television shows, approve it. The television then signs itself in within a few seconds.
 */
const CODE = /^[A-HJ-NP-Z2-9]{8}$/;

function normalize(input: string): string {
  return input.toUpperCase().replace(/[\s-]/g, '');
}

function format(code: string): string {
  return `${code.slice(0, 4)}-${code.slice(4)}`;
}

async function main() {
  const session = await start();
  const input = $('code') as HTMLInputElement;
  const fromLink = new URLSearchParams(location.search).get('code');
  if (fromLink) input.value = format(normalize(fromLink));

  const message = (text: string, tone: 'error' | 'ok' | '' = '') => {
    const el = $('message');
    el.textContent = text;
    el.className = tone;
  };

  watchUser(session, (user) => {
    show('signed-out', !user);
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

  $('switch').addEventListener('click', () => {
    message('');
    void leave(session);
  });

  $('approve-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const code = normalize(input.value);
    if (!CODE.test(code)) {
      message('זה לא נראה כמו קוד. הקוד בטלוויזיה בנוי משמונה תווים.', 'error');
      return;
    }
    const button = $('approve') as HTMLButtonElement;
    button.disabled = true;
    message('מאשר…');
    try {
      await call(session, 'approvePairing', { code });
      show('approve-form', false);
      message('אושר. הטלוויזיה תתחבר בעוד כמה שניות, ואפשר לסגור את הדף.', 'ok');
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
