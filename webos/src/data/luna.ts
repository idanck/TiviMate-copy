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

interface Bridge {
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

export const onTelevision = typeof window !== 'undefined' && typeof window.PalmServiceBridge === 'function';

/** A reply as the bus delivers it: `returnValue` false is a failure, anything else is data. */
export type LunaReply = { returnValue?: boolean; errorCode?: number; errorText?: string } & Record<string, unknown>;

/**
 * Calls `method` on `uri` with `subscribe: true` and hands every reply on; the returned function
 * cancels the call, which tells the service to stop.
 */
export function subscribe(uri: string, method: string, params: Record<string, unknown>, onReply: (reply: LunaReply) => void): () => void {
  const Bridge = window.PalmServiceBridge;
  if (!Bridge) throw new Error('no-luna');
  const bridge = new Bridge();
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
  bridge.call(uri + method, JSON.stringify({ ...params, subscribe: true }));
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

/**
 * Leaves the app the way the platform wants from its first screen: webOS 6 and later ask whether
 * to exit, earlier versions go to the Home launcher. Off the television there is nothing to leave.
 */
export function platformBack() {
  const system = typeof window !== 'undefined' ? window.PalmSystem : undefined;
  if (system?.platformBack) system.platformBack();
  else if (onTelevision) window.close();
}
