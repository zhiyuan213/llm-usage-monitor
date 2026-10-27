// Public-site measurement only. Never send route queries, hashes, account IDs or form values.
export const measurementId = 'G-W26TJWRR17';
export const consentKey = 'metertrail.analytics-consent.v1';
export const publicPaths = ['/', '/pricing', '/docs', '/demo', '/tutorials', '/tutorials/nodejs', '/tutorials/python', '/tutorials/rest', '/privacy', '/terms', '/refunds', '/security'];
const titles: Record<string, string> = {
  '/': 'LLM call tracking and cost monitoring', '/pricing': 'Pricing', '/docs': 'Documentation',
  '/demo': 'Interactive demo', '/tutorials': 'Integration tutorials',
  '/tutorials/nodejs': 'Node.js integration tutorial', '/tutorials/python': 'Python integration tutorial',
  '/tutorials/rest': 'REST integration tutorial', '/privacy': 'Privacy policy', '/terms': 'Terms', '/refunds': 'Refunds', '/security': 'Security and data handling',
};
export function publicPage(path: string) {
  return publicPaths.includes(path) ? { page_location: `https://metertrail.com${path}`, page_title: `${titles[path]} | MeterTrail`, page_referrer: '' } : null;
}
export function campaign(search: string) {
  const query = new URLSearchParams(search);
  const sources = ['github', 'reddit', 'producthunt', 'hackernews', 'devto', 'medium', 'x', 'google', 'newsletter'];
  const media = ['social', 'referral', 'email', 'organic'];
  const names = ['launch', 'node-tutorial', 'python-tutorial', 'rest-tutorial'];
  const result: Record<string, string> = {};
  for (const [input, output, allowed] of [['utm_source', 'campaign_source', sources], ['utm_medium', 'campaign_medium', media], ['utm_campaign', 'campaign_name', names]] as const) {
    const value = query.get(input);
    if (value && allowed.includes(value)) result[output] = value;
  }
  return result;
}
type AnalyticsWindow = Window & { dataLayer?: unknown[]; gtag?: (...args: unknown[]) => void };
let loaded = false;
let lastPage = '';
export function hasAnalytics() { return loaded; }
export function consent(): string | null {
  try { return localStorage.getItem(consentKey); } catch { return 'denied'; }
}
export function setConsent(accepted: boolean) {
  try { localStorage.setItem(consentKey, accepted ? 'granted' : 'denied'); } catch { return; }
  if (!accepted && loaded) {
    const w = window as AnalyticsWindow;
    w.gtag?.('consent', 'update', { analytics_storage: 'denied' });
    // Remove our GA cookies at both host and parent domain before unloading the tag.
    for (const name of ['_ga', '_ga_W26TJWRR17']) {
      for (const domain of ['', '; domain=metertrail.com', '; domain=.metertrail.com'])
        document.cookie = `${name}=; Max-Age=0; path=/${domain}; SameSite=Lax; Secure`;
    }
    window.location.reload();
  }
}
export function trackPage(path: string) {
  const page = publicPage(path);
  if (!page || consent() !== 'granted' || window.location.hostname !== 'metertrail.com') return;
  const w = window as AnalyticsWindow;
  if (!loaded) {
    w.dataLayer = w.dataLayer || [];
    w.gtag = function () { w.dataLayer!.push(arguments); };
    w.gtag('consent', 'default', { analytics_storage: 'granted', ad_storage: 'denied', ad_user_data: 'denied', ad_personalization: 'denied' });
    w.gtag('js', new Date());
    w.gtag('config', measurementId, { ...page, ...campaign(window.location.search), send_page_view: false, allow_google_signals: false, allow_ad_personalization_signals: false, cookie_flags: 'SameSite=Lax;Secure' });
    const script = document.createElement('script');
    script.async = true;
    script.src = `https://www.googletagmanager.com/gtag/js?id=${measurementId}`;
    script.referrerPolicy = 'origin';
    document.head.appendChild(script);
    loaded = true;
  }
  if (lastPage === path) return;
  w.gtag?.('set', page);
  w.gtag?.('event', 'page_view', page);
  lastPage = path;
}
export function trackAction(action: 'start_free' | 'open_demo' | 'open_tutorial' | 'copy_example', path: string) {
  const page = publicPage(path);
  if (!loaded || !page || consent() !== 'granted') return;
  (window as AnalyticsWindow).gtag?.('event', action, { ...page, send_to: measurementId });
}
