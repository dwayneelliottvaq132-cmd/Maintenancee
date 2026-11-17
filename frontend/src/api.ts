import { tServer } from './i18n';
import axios from 'axios';

export const api = axios.create({ baseURL: '/api' });

api.interceptors.request.use((cfg) => {
  const t = localStorage.getItem('cmms_token');
  if (t) cfg.headers.Authorization = `Bearer ${t}`;
  return cfg;
});

api.interceptors.response.use(
  (r) => r,
  (err) => {
    if (err.response?.status === 401 && !err.config.url?.includes('/auth/login')) {
      localStorage.removeItem('cmms_token');
      window.location.href = '/login';
    }
    return Promise.reject(err);
  },
);

export function errMsg(e: unknown): string {
  const anyE = e as any;
  return tServer(anyE?.response?.data?.error ?? anyE?.message ?? 'Something went wrong');
}

/** URL for a file download that needs auth (opened in a new tab). */
export function fileUrl(path: string) {
  const t = localStorage.getItem('cmms_token') ?? '';
  return `/api${path}${path.includes('?') ? '&' : '?'}access_token=${encodeURIComponent(t)}`;
}

export type Role = 'ADMIN' | 'SUPERVISOR' | 'TECHNICIAN' | 'REQUESTER';
export type Priority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT';
export type WoStatus = 'OPEN' | 'ASSIGNED' | 'IN_PROGRESS' | 'COMPLETED' | 'SIGNED_OFF' | 'CANCELLED';

export interface User {
  id: number; username: string; fullName: string; email?: string; role: Role;
  hourlyRate?: number | null; trade?: string; active: boolean; mustChangePassword: boolean;
}
export interface Location { id: number; name: string; parentId?: number | null; description?: string }
export interface Asset {
  id: number; tag: string; name: string; category?: string; locationId?: number | null; parentAssetId?: number | null;
  manufacturer?: string; model?: string; serialNumber?: string; installDate?: string; purchaseCost?: number;
  warrantyUntil?: string; status: 'ACTIVE' | 'DOWN' | 'RETIRED'; criticality: 'LOW' | 'MEDIUM' | 'HIGH';
  meterReading: number; meterUnit?: string; notes?: string;
  locationName?: string; openWorkOrders?: number; lifetimeCost?: number; nextPmDue?: string;
}
export interface Part {
  id: number; partNo: string; name: string; unit: string; unitCost: number; qtyOnHand: number;
  reorderPoint: number; binLocation?: string; supplier?: string; active: boolean;
}
export interface WorkInstruction {
  id: number; title: string; version: number; description?: string; safetyNotes?: string;
  requiredPpe?: string; toolsRequired?: string; estimatedMinutes?: number; updatedAt?: string;
}
export interface InstructionStep { id: number; seq: number; text: string; requiresCheck: boolean }
export interface InstructionPart { id: number; partId: number; qty: number }
export interface InstructionDto { instruction: WorkInstruction; steps: InstructionStep[]; parts: InstructionPart[] }
export interface MaintenanceRequest {
  id: number; title: string; description?: string; requesterId: number; assetId?: number | null; locationId?: number | null;
  priority: Priority; status: 'NEW' | 'APPROVED' | 'REJECTED'; reviewedBy?: number; reviewedAt?: string;
  reviewNotes?: string; workOrderId?: number; createdAt: string;
}
export interface WorkOrder {
  id: number; number: string; title: string; description?: string; type: 'PM' | 'CORRECTIVE' | 'REQUEST';
  priority: Priority; status: WoStatus; assetId?: number | null; locationId?: number | null; pmScheduleId?: number;
  requestId?: number; instructionId?: number | null; dueDate?: string | null; createdAt: string; startedAt?: string;
  completedAt?: string; completedBy?: number; completionNotes?: string; meterAtCompletion?: number;
  signedOffBy?: number; signedOffAt?: string; signatureName?: string; signOffNotes?: string;
}
export interface WorkOrderDetail {
  workOrder: WorkOrder; assigneeIds: number[]; instruction?: WorkInstruction | null;
  steps: { stepId: number; seq: number; text: string; requiresCheck: boolean; done: boolean; note?: string; doneBy?: number; doneAt?: string }[];
  labor: { id: number; userId: number; workDate: string; hours: number; hourlyRate: number; note?: string }[];
  parts: { id: number; partId: number; qty: number; unitCost: number; usedAt: string }[];
  otherCosts: { id: number; description: string; vendor?: string; amount: number; costDate: string }[];
  totals: { labor: number; parts: number; other: number; total: number; hours: number };
}
