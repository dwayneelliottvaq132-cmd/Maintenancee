import { t, tEnum } from '../i18n';
import LanguageSwitch from './LanguageSwitch';
import { useState } from 'react';
import { Link, Outlet, useLocation } from 'react-router-dom';
import {
  AppBar, Box, Drawer, IconButton, List, ListItemButton, ListItemIcon, ListItemText, Toolbar, Typography, Button,
  useMediaQuery, useTheme,
} from '@mui/material';
import MenuIcon from '@mui/icons-material/Menu';
import DashboardIcon from '@mui/icons-material/Dashboard';
import BuildIcon from '@mui/icons-material/Build';
import EventRepeatIcon from '@mui/icons-material/EventRepeat';
import PrecisionManufacturingIcon from '@mui/icons-material/PrecisionManufacturing';
import PlaceIcon from '@mui/icons-material/Place';
import ListAltIcon from '@mui/icons-material/ListAlt';
import InventoryIcon from '@mui/icons-material/Inventory2';
import RequestPageIcon from '@mui/icons-material/RequestPage';
import PeopleIcon from '@mui/icons-material/People';
import AssessmentIcon from '@mui/icons-material/Assessment';
import AccountCircleIcon from '@mui/icons-material/AccountCircle';
import { useAuth } from '../auth';
import { Role } from '../api';

const W = 230;
const NAV: { to: string; label: string; icon: JSX.Element; roles?: Role[] }[] = [
  { to: '/', label: 'Dashboard', icon: <DashboardIcon />, roles: ['ADMIN', 'SUPERVISOR', 'TECHNICIAN'] },
  { to: '/requests', label: 'Requests', icon: <RequestPageIcon /> },
  { to: '/work-orders', label: 'Work Orders', icon: <BuildIcon />, roles: ['ADMIN', 'SUPERVISOR', 'TECHNICIAN'] },
  { to: '/pm', label: 'PM Schedules', icon: <EventRepeatIcon />, roles: ['ADMIN', 'SUPERVISOR', 'TECHNICIAN'] },
  { to: '/assets', label: 'Assets', icon: <PrecisionManufacturingIcon />, roles: ['ADMIN', 'SUPERVISOR', 'TECHNICIAN'] },
  { to: '/locations', label: 'Locations', icon: <PlaceIcon />, roles: ['ADMIN', 'SUPERVISOR', 'TECHNICIAN'] },
  { to: '/instructions', label: 'Work Instructions', icon: <ListAltIcon />, roles: ['ADMIN', 'SUPERVISOR', 'TECHNICIAN'] },
  { to: '/parts', label: 'Parts & Supplies', icon: <InventoryIcon />, roles: ['ADMIN', 'SUPERVISOR', 'TECHNICIAN'] },
  { to: '/reports', label: 'Spending & Reports', icon: <AssessmentIcon />, roles: ['ADMIN', 'SUPERVISOR'] },
  { to: '/users', label: 'Employees', icon: <PeopleIcon />, roles: ['ADMIN', 'SUPERVISOR'] },
  { to: '/account', label: 'My Account', icon: <AccountCircleIcon /> },
];

export default function Layout() {
  const { user, logout, can } = useAuth();
  const theme = useTheme();
  const mobile = useMediaQuery(theme.breakpoints.down('md'));
  const [open, setOpen] = useState(false);
  const loc = useLocation();

  const nav = (
    <List sx={{ pt: 1 }}>
      {NAV.filter((n) => !n.roles || can(...n.roles)).map((n) => {
        const active = n.to === '/' ? loc.pathname === '/' : loc.pathname.startsWith(n.to);
        return (
          <ListItemButton key={n.to} component={Link} to={n.to} selected={active} onClick={() => setOpen(false)}
            sx={{ mx: 1, borderRadius: 1 }}>
            <ListItemIcon sx={{ minWidth: 38 }}>{n.icon}</ListItemIcon>
            <ListItemText primary={t(n.label)} />
          </ListItemButton>
        );
      })}
    </List>
  );

  return (
    <Box sx={{ display: 'flex', minHeight: '100vh', bgcolor: 'background.default' }}>
      <AppBar position="fixed" sx={{ zIndex: (t) => t.zIndex.drawer + 1 }}>
        <Toolbar>
          {mobile && <IconButton color="inherit" edge="start" onClick={() => setOpen(true)} aria-label={t("menu")}><MenuIcon /></IconButton>}
          <Typography variant="h6" sx={{ flexGrow: 1, ml: mobile ? 1 : 0 }} noWrap>{t("Maintenance Manager")}</Typography>
          <Typography variant="body2" sx={{ mr: 2, display: { xs: 'none', sm: 'block' } }}>
            {user?.fullName} · {tEnum(user?.role).toLowerCase()}
          </Typography>
          <LanguageSwitch color="inherit" />
          <Button color="inherit" onClick={logout}>{t("Log out")}</Button>
        </Toolbar>
      </AppBar>
      <Drawer variant={mobile ? 'temporary' : 'permanent'} open={mobile ? open : true} onClose={() => setOpen(false)}
        sx={{ width: W, flexShrink: 0, '& .MuiDrawer-paper': { width: W, boxSizing: 'border-box' } }}>
        <Toolbar />
        {nav}
      </Drawer>
      <Box component="main" sx={{ flexGrow: 1, p: { xs: 2, md: 3 }, minWidth: 0 }}>
        <Toolbar />
        <Outlet />
      </Box>
    </Box>
  );
}
