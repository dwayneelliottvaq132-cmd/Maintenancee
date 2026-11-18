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

/**
 * Error messages come from the server in English. Fixed messages are looked up directly in the
 * dictionary; messages with names or numbers in them are matched by pattern.
 */
const SERVER_PATTERNS: [RegExp, string][] = [
  [/^(\d+) required instruction step\(s\) are not checked off yet$/, '{1} required instruction step(s) are not checked off yet'],
  [/^Not enough (.+) in stock \(on hand (.+), requested (.+)\)$/, 'Not enough {1} in stock (on hand {2}, requested {3})'],
  [/^Work order (\S+) is (\S+) and can no longer be changed$/, 'Work order {1} is {2} and can no longer be changed'],
  [/^Work order (\S+) is completed\. A supervisor must send it back before it can be changed$/, 'Work order {1} is completed. A supervisor must send it back before it can be changed'],
  [/^Request has already been (\w+)$/, 'Request has already been {1}'],
  [/^The new reading is lower than the current one \((.+)\)\. Ask a supervisor to correct it$/, 'The new reading is lower than the current one ({1}). Ask a supervisor to correct it'],
  [/^(.+) is no longer active$/, '{1} is no longer active'],
  [/^(.+) is a requester and cannot be assigned work$/, '{1} is a requester and cannot be assigned work'],
  [/^(.+) is not active$/, '{1} is not active'],
  [/^(\S+) (\S+) not found$/, '{1} {2} not found'],
];

export function tServer(message: string): string {
  if (current !== 'es') return message;
  if (ES[message]) return ES[message];
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
