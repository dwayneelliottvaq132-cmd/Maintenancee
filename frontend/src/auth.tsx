import { createContext, useContext, useEffect, useState, ReactNode } from 'react';
import { api, Role, User } from './api';

interface AuthCtx {
  user: User | null;
  loading: boolean;
  login: (u: string, p: string) => Promise<User>;
  logout: () => void;
  refresh: () => Promise<void>;
  can: (...roles: Role[]) => boolean;
  isSupervisor: boolean;
  isStaff: boolean;
}

const Ctx = createContext<AuthCtx>(null as any);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  const refresh = async () => {
    if (!localStorage.getItem('cmms_token')) { setUser(null); return; }
    try { setUser((await api.get<User>('/auth/me')).data); } catch { setUser(null); }
  };

  useEffect(() => { refresh().finally(() => setLoading(false)); }, []);

  const login = async (username: string, password: string) => {
    const { data } = await api.post('/auth/login', { username, password });
    localStorage.setItem('cmms_token', data.token);
    setUser(data.user);
    return data.user as User;
  };
  const logout = () => { localStorage.removeItem('cmms_token'); setUser(null); };
  const can = (...roles: Role[]) => !!user && roles.includes(user.role);

  return (
    <Ctx.Provider value={{
      user, loading, login, logout, refresh, can,
      isSupervisor: can('ADMIN', 'SUPERVISOR'),
      isStaff: can('ADMIN', 'SUPERVISOR', 'TECHNICIAN'),
    }}>
      {children}
    </Ctx.Provider>
  );
}

export const useAuth = () => useContext(Ctx);
