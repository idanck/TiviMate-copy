/**
 * The Luna bus: how the page talks to the television, and to Shidur's own service (service/).
 *
 * On webOS every page has `PalmServiceBridge`, the object LG's webOSTV.js wraps; using it
 * directly keeps that library out of the package. A call names a service and a method, takes JSON
 * and answers with JSON, any number of times while subscribed.
 *
 * Off the television (a desktop browser while developing, the tests) there is no bridge, and
 * data/api.ts falls back to the browser's own fetch.
 */

export interface Bridge {
  call(uri: string, params: string): void;
  cancel(): void;
  onservicecallback: ((message: string) => void) | null;
}

interface PalmSystemApi {
  platformBack?: () => void;
  activate?: () => void;
}

declare global {
  interface Window {
    PalmServiceBridge?: new () => Bridge;
    PalmSystem?: PalmSystemApi;
  }
}

/** The app's id (appinfo.json) and its service's, which must start with it. */
export const APP_ID = 'com.idanck.shidur';
export const SERVICE_URI = `luna://${APP_ID}.service/`;

export function onTelevision(): boolean {
  return typeof window !== 'undefined' && typeof window.PalmServiceBridge === 'function';
}

/** A reply as the bus delivers it: `returnValue` false is a failure, anything else is data. */
export type LunaReply = { returnValue?: boolean; errorCode?: number; errorText?: string } & Record<string, unknown>;

/**
 * Calls `method` on `uri` and hands every reply on (with `subscribe: true`, until cancelled); the
 * returned function cancels the call, which tells the service to stop.
 */
export function subscribe(
  uri: string,
  method: string,
  params: Record<string, unknown>,
  onReply: (reply: LunaReply) => void,
  subscribed = true,
): () => void {
  const Make = window.PalmServiceBridge;
  if (!Make) throw new Error('no-luna');
  const bridge = new Make();
  let open = true;
  bridge.onservicecallback = (message: string) => {
    if (!open) return;
    let reply: LunaReply;
    try {
      reply = JSON.parse(message) as LunaReply;
    } catch {
      return;
    }
    onReply(reply);
  };
  bridge.call(uri + method, JSON.stringify(subscribed ? { ...params, subscribe: true } : params));
  return () => {
    if (!open) return;
    open = false;
    try {
      bridge.cancel();
    } catch {
      // the call had already ended
    }
  };
}

/** One call, one reply, nothing waited for: for a message the service only needs to receive. */
export function send(uri: string, method: string, params: Record<string, unknown>): void {
  let stop: (() => void) | null = null;
  let answered = false;
  stop = subscribe(uri, method, params, () => {
    answered = true;
    stop?.();
  }, false);
  if (answered) stop();
}

/**
 * Leaves the app the way the platform wants from its first screen: webOS 6 and later ask whether
 * to exit, webOS 5 goes to the Home launcher. Off the television there is nothing to leave.
 */
export function platformBack() {
  const system = typeof window !== 'undefined' ? window.PalmSystem : undefined;
  if (system?.platformBack) system.platformBack();
  else if (onTelevision()) window.close();
}
