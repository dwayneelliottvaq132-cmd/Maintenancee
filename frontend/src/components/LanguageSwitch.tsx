import { ToggleButton, ToggleButtonGroup } from '@mui/material';
import { useLang } from '../i18n';

/** EN / ES switch. The choice is remembered on this computer. */
export default function LanguageSwitch({ color }: { color?: 'inherit' }) {
  const { lang, setLang } = useLang();
  const sx = color === 'inherit'
    ? { mr: 1, '& .MuiToggleButton-root': { color: 'inherit', borderColor: 'rgba(255,255,255,0.5)', py: 0.25, px: 1 },
        '& .Mui-selected': { bgcolor: 'rgba(255,255,255,0.25) !important', color: 'inherit !important' } }
    : { '& .MuiToggleButton-root': { py: 0.25, px: 1 } };
  return (
    <ToggleButtonGroup size="small" exclusive value={lang} onChange={(_, v) => v && setLang(v)} sx={sx} aria-label="Language / Idioma">
      <ToggleButton value="en" title="English">EN</ToggleButton>
      <ToggleButton value="es" title="Español">ES</ToggleButton>
    </ToggleButtonGroup>
  );
}
