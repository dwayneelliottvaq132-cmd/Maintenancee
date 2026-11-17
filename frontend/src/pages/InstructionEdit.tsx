import { t } from '../i18n';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import {
  Alert, Autocomplete, Box, Button, Checkbox, FormControlLabel, IconButton, Paper, Stack, TextField, Typography,
} from '@mui/material';
import DeleteIcon from '@mui/icons-material/DeleteOutline';
import UpIcon from '@mui/icons-material/ArrowUpward';
import DownIcon from '@mui/icons-material/ArrowDownward';
import PrintIcon from '@mui/icons-material/Print';
import { api, errMsg, InstructionDto, WorkInstruction } from '../api';
import { useAuth } from '../auth';
import { PageHeader, num, useLookups } from '../components/common';

type Step = { text: string; requiresCheck: boolean };
type PartRow = { partId: number | null; qty: number };

export default function InstructionEditPage() {
  const { id } = useParams();
  const isNew = id === 'new';
  const nav = useNavigate();
  const qc = useQueryClient();
  const { isSupervisor } = useAuth();
  const lk = useLookups();
  const [wi, setWi] = useState<Partial<WorkInstruction>>({ title: '' });
  const [steps, setSteps] = useState<Step[]>([{ text: '', requiresCheck: true }]);
  const [parts, setParts] = useState<PartRow[]>([]);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);

  useEffect(() => {
    if (isNew) return;
    api.get<InstructionDto>(`/instructions/${id}`).then(({ data }) => {
      setWi(data.instruction);
      setSteps(data.steps.map((s) => ({ text: s.text, requiresCheck: s.requiresCheck })));
      setParts(data.parts.map((p) => ({ partId: p.partId, qty: Number(p.qty) })));
    });
  }, [id, isNew]);

  const ro = !isSupervisor;
  const setStep = (i: number, s: Partial<Step>) => setSteps(steps.map((x, j) => (j === i ? { ...x, ...s } : x)));
  const move = (i: number, d: number) => {
    const n = [...steps]; const [x] = n.splice(i, 1); n.splice(i + d, 0, x); setSteps(n);
  };

  const save = async () => {
    try {
      const body = { instruction: wi, steps, parts: parts.filter((p) => p.partId) };
      const { data } = isNew ? await api.post('/instructions', body) : await api.put(`/instructions/${id}`, body);
      qc.invalidateQueries({ queryKey: ['instructions'] });
      setMsg({ ok: true, text: t('Saved (version {v})', { v: data.instruction.version }) });
      setWi(data.instruction);
      if (isNew) nav(`/instructions/${data.instruction.id}`, { replace: true });
    } catch (e) { setMsg({ ok: false, text: errMsg(e) }); }
  };

  const field = (k: keyof WorkInstruction, label: string, extra: any = {}) => (
    <TextField label={label} value={(wi as any)[k] ?? ''} fullWidth InputProps={{ readOnly: ro }} {...extra}
      onChange={(e) => setWi({ ...wi, [k]: extra.type === 'number' ? (e.target.value === '' ? null : Number(e.target.value)) : e.target.value })} />
  );

  return (
    <>
      <PageHeader title={isNew ? t('New work instruction') : wi.title ?? ''} subtitle={!isNew ? t('Version {v}', { v: wi.version }) : undefined}
        actions={<Box className="no-print" display="flex" gap={1}>
          <Button startIcon={<PrintIcon />} onClick={() => window.print()}>{t("Print")}</Button>
          {isSupervisor && !isNew && <Button color="error" onClick={async () => {
            if (!confirm(t('Delete this instruction?'))) return;
            try { await api.delete(`/instructions/${id}`); qc.invalidateQueries({ queryKey: ['instructions'] }); nav('/instructions'); }
            catch (e) { setMsg({ ok: false, text: errMsg(e) }); }
          }}>{t("Delete")}</Button>}
          {isSupervisor && <Button variant="contained" onClick={save}>{t("Save")}</Button>}
        </Box>} />
      {msg && <Alert severity={msg.ok ? 'success' : 'error'} sx={{ mb: 2 }} onClose={() => setMsg(null)}>{msg.text}</Alert>}
      <Paper sx={{ p: 2, mb: 2 }}>
        <Box display="grid" gridTemplateColumns={{ xs: '1fr', md: '1fr 1fr' }} gap={2}>
          <Box sx={{ gridColumn: '1 / -1' }}>{field('title', t('Title'), { required: true })}</Box>
          <Box sx={{ gridColumn: '1 / -1' }}>{field('description', t('Purpose / scope'), { multiline: true, minRows: 2 })}</Box>
          <Box sx={{ gridColumn: '1 / -1' }}>{field('safetyNotes', t('Safety notes (lockout, hazards)'), { multiline: true, minRows: 2 })}</Box>
          {field('requiredPpe', t('Required PPE'))}
          {field('toolsRequired', t('Tools required'))}
          {field('estimatedMinutes', t('Estimated minutes'), { type: 'number' })}
        </Box>
      </Paper>

      <Paper sx={{ p: 2, mb: 2 }}>
        <Typography variant="subtitle1" fontWeight={600} mb={1}>{t("Steps")}</Typography>
        <Stack gap={1}>
          {steps.map((s, i) => (
            <Stack key={i} direction="row" gap={1} alignItems="flex-start">
              <Typography sx={{ pt: 1, minWidth: 24 }} fontWeight={600}>{i + 1}.</Typography>
              <TextField fullWidth multiline size="small" value={s.text} InputProps={{ readOnly: ro }}
                placeholder={t("Describe the step")} onChange={(e) => setStep(i, { text: e.target.value })} />
              <FormControlLabel className="no-print" sx={{ whiteSpace: 'nowrap' }} label={t("Must check off")} disabled={ro}
                control={<Checkbox checked={s.requiresCheck} onChange={(e) => setStep(i, { requiresCheck: e.target.checked })} />} />
              {!ro && <Box className="no-print" display="flex">
                <IconButton size="small" disabled={i === 0} onClick={() => move(i, -1)}><UpIcon fontSize="small" /></IconButton>
                <IconButton size="small" disabled={i === steps.length - 1} onClick={() => move(i, 1)}><DownIcon fontSize="small" /></IconButton>
                <IconButton size="small" onClick={() => setSteps(steps.filter((_, j) => j !== i))}><DeleteIcon fontSize="small" /></IconButton>
              </Box>}
            </Stack>
          ))}
        </Stack>
        {!ro && <Button className="no-print" sx={{ mt: 1 }} onClick={() => setSteps([...steps, { text: '', requiresCheck: true }])}>{t("Add step")}</Button>}
      </Paper>

      <Paper sx={{ p: 2 }}>
        <Typography variant="subtitle1" fontWeight={600} mb={1}>{t("Parts & supplies typically needed")}</Typography>
        <Stack gap={1}>
          {parts.map((p, i) => (
            <Stack key={i} direction="row" gap={1} alignItems="center">
              <Autocomplete sx={{ flex: 1 }} size="small" options={lk.parts} readOnly={ro}
                getOptionLabel={(o) => `${o.partNo} — ${o.name} (${num(o.qtyOnHand)} ${o.unit} on hand)`}
                value={lk.parts.find((x) => x.id === p.partId) ?? null}
                onChange={(_, v) => setParts(parts.map((x, j) => (j === i ? { ...x, partId: v?.id ?? null } : x)))}
                renderInput={(params) => <TextField {...params} placeholder={t("Part")} />} />
              <TextField size="small" type="number" label={t("Qty")} sx={{ width: 100 }} value={p.qty} InputProps={{ readOnly: ro }}
                onChange={(e) => setParts(parts.map((x, j) => (j === i ? { ...x, qty: Number(e.target.value) } : x)))} />
              {!ro && <IconButton className="no-print" onClick={() => setParts(parts.filter((_, j) => j !== i))}><DeleteIcon /></IconButton>}
            </Stack>
          ))}
          {!parts.length && <Typography color="text.secondary" variant="body2">{t("None listed.")}</Typography>}
        </Stack>
        {!ro && <Button className="no-print" sx={{ mt: 1 }} onClick={() => setParts([...parts, { partId: null, qty: 1 }])}>{t("Add part")}</Button>}
      </Paper>
    </>
  );
}
