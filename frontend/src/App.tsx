import { Navigate, Route, Routes } from 'react-router-dom';
import { Box, CircularProgress } from '@mui/material';
import { useAuth } from './auth';
import Layout from './components/Layout';
import LoginPage from './pages/Login';
import DashboardPage from './pages/Dashboard';
import AssetsPage from './pages/Assets';
import AssetDetailPage from './pages/AssetDetail';
import LocationsPage from './pages/Locations';
import WorkOrdersPage from './pages/WorkOrders';
import WorkOrderDetailPage from './pages/WorkOrderDetail';
import PmPage from './pages/PmSchedules';
import InstructionsPage from './pages/Instructions';
import InstructionEditPage from './pages/InstructionEdit';
import RequestsPage from './pages/Requests';
import PartsPage from './pages/Parts';
import UsersPage from './pages/Users';
import ReportsPage from './pages/Reports';
import AccountPage from './pages/Account';

export default function App() {
  const { user, loading, isStaff } = useAuth();
  if (loading) return <Box p={6} textAlign="center"><CircularProgress /></Box>;
  if (!user) return <Routes><Route path="*" element={<LoginPage />} /></Routes>;
  if (user.mustChangePassword) return <AccountPage forced />;
  return (
    <Routes>
      <Route path="/login" element={<Navigate to="/" />} />
      <Route element={<Layout />}>
        <Route path="/" element={isStaff ? <DashboardPage /> : <Navigate to="/requests" />} />
        <Route path="/requests" element={<RequestsPage />} />
        <Route path="/work-orders" element={<WorkOrdersPage />} />
        <Route path="/work-orders/:id" element={<WorkOrderDetailPage />} />
        <Route path="/pm" element={<PmPage />} />
        <Route path="/assets" element={<AssetsPage />} />
        <Route path="/assets/:id" element={<AssetDetailPage />} />
        <Route path="/locations" element={<LocationsPage />} />
        <Route path="/instructions" element={<InstructionsPage />} />
        <Route path="/instructions/:id" element={<InstructionEditPage />} />
        <Route path="/parts" element={<PartsPage />} />
        <Route path="/users" element={<UsersPage />} />
        <Route path="/reports" element={<ReportsPage />} />
        <Route path="/account" element={<AccountPage />} />
        <Route path="*" element={<Navigate to="/" />} />
      </Route>
    </Routes>
  );
}
