import { createContext, ReactNode, useContext, useState } from 'react';
import { ES } from './i18n.es';

/**
 * Lightweight translations. Text in the code is written in English and used as the lookup key, so a
 * missing Spanish entry simply shows the English text. Switching language remounts the app, so the
 * plain t() function can be used anywhere (components, helpers, field lists).
 */
export type Lang = 'en' | 'es';

const STORAGE_KEY = 'cmms_lang';

function initialLang(): Lang {
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved === 'en' || saved === 'es') return saved;
  } catch { /* storage unavailable */ }
  return navigator.language?.toLowerCase().startsWith('es') ? 'es' : 'en';
}

let current: Lang = initialLang();

export const getLang = () => current;
export const locale = () => (current === 'es' ? 'es-MX' : 'en-US');

/** Translate English text. Placeholders like {name} are filled from vars. */
export function t(text: string, vars?: Record<string, string | number | null | undefined>): string {
  let out = current === 'es' ? ES[text] ?? text : text;
  if (vars) for (const [k, v] of Object.entries(vars)) out = out.split(`{${k}}`).join(v == null ? '' : String(v));
  return out;
}

/** Translate a code value such as IN_PROGRESS, HIGH, TECHNICIAN. */
export function tEnum(value?: string | null): string {
  if (!value) return '';
  const english = value.replace(/^ACT_/, '').replace(/_/g, ' ');
  return current === 'es' ? ES[`enum:${value}`] ?? english : english;
}

/** Error messages come from the server in English; translate the common ones. */
const SERVER_PATTERNS: [RegExp, string][] = [
  [/^Invalid username or password$/, 'Invalid username or password'],
  [/^(\d+) required instruction step\(s\) are not checked off yet$/, '{1} required instruction step(s) are not checked off yet'],
  [/^Not enough (.+) in stock \(on hand (.+), requested (.+)\)$/, 'Not enough {1} in stock (on hand {2}, requested {3})'],
  [/^Work order must be completed before sign-off$/, 'Work order must be completed before sign-off'],
  [/^Only supervisors can sign off work$/, 'Only supervisors can sign off work'],
  [/^Type your full name to sign off$/, 'Type your full name to sign off'],
  [/^Technicians can only log their own hours$/, 'Technicians can only log their own hours'],
  [/^Work order (\S+) is (\S+) and can no longer be changed$/, 'Work order {1} is {2} and can no longer be changed'],
  [/^An open work order already exists for this schedule$/, 'An open work order already exists for this schedule'],
  [/^Request has already been (\w+)$/, 'Request has already been {1}'],
  [/^Give a reason for rejecting the request$/, 'Give a reason for rejecting the request'],
  [/^Current password is incorrect$/, 'Current password is incorrect'],
  [/^You do not have permission to do that$/, 'You do not have permission to do that'],
  [/^Username already exists$/, 'Username already exists'],
  [/^Password must be at least 8 characters$/, 'Password must be at least 8 characters'],
  [/^(.+) is required$/, '{1} is required'],
  [/^Conflicts with existing data/, 'Conflicts with existing data (duplicate or in use)'],
];

export function tServer(message: string): string {
  if (current !== 'es') return message;
  for (const [re, key] of SERVER_PATTERNS) {
    const m = message.match(re);
    if (m) {
      let out = ES[key] ?? key;
      m.slice(1).forEach((g, i) => { out = out.split(`{${i + 1}}`).join(g); });
      return out;
    }
  }
  return message;
}

const Ctx = createContext<{ lang: Lang; setLang: (l: Lang) => void }>({ lang: current, setLang: () => {} });

export function LanguageProvider({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState<Lang>(current);
  const setLang = (l: Lang) => {
    current = l;
    try { localStorage.setItem(STORAGE_KEY, l); } catch { /* ignore */ }
    document.documentElement.lang = l;
    setLangState(l);
  };
  document.documentElement.lang = lang;
  // key={lang} remounts everything so every t() call re-runs in the new language
  return <Ctx.Provider value={{ lang, setLang }}><div key={lang} style={{ display: 'contents' }}>{children}</div></Ctx.Provider>;
}

export const useLang = () => useContext(Ctx);
