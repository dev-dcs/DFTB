/* DFTB Companion WebUI - @Dev_DcS */
'use strict';

const MOD = '/data/adb/modules/dftb_companion';
const PARTBAK = `sh ${MOD}/libexec/partbak.sh`;
const UPDATE = `sh ${MOD}/libexec/update-check.sh`;
const CONF = '/data/adb/dftb/companion.conf';

// KernelSU / WebUI-X / APatch exec bridge, wrapped as a promise.
function exec(cmd, opts) {
  return new Promise((resolve) => {
    const name = 'cb' + Math.random().toString(36).slice(2);
    window[name] = (errno, stdout, stderr) => {
      delete window[name];
      resolve({ errno, stdout: stdout || '', stderr: stderr || '' });
    };
    try {
      ksu.exec(cmd, JSON.stringify(opts || {}), name);
    } catch (e) {
      delete window[name];
      resolve({ errno: -1, stdout: '', stderr: String(e) });
    }
  });
}

const $ = (s) => document.querySelector(s);
const el = (t, c, txt) => { const e = document.createElement(t); if (c) e.className = c; if (txt != null) e.textContent = txt; return e; };

let snackTimer;
function snack(msg) {
  const s = $('#snack');
  s.textContent = msg;
  s.classList.add('show');
  clearTimeout(snackTimer);
  snackTimer = setTimeout(() => s.classList.remove('show'), 2600);
}

// ---- tabs ---------------------------------------------------------------------
document.querySelectorAll('.tabs button').forEach((b) => {
  b.addEventListener('click', () => {
    document.querySelectorAll('.tabs button').forEach((x) => x.classList.remove('active'));
    document.querySelectorAll('.view').forEach((v) => v.classList.remove('active'));
    b.classList.add('active');
    $('#view-' + b.dataset.view).classList.add('active');
    if (b.dataset.view === 'parts') loadParts();
    if (b.dataset.view === 'backups') loadBackups();
  });
});

// ---- status -------------------------------------------------------------------
async function loadStatus() {
  const prop = await exec(`cat ${MOD}/module.prop`);
  const ver = (prop.stdout.match(/^version=(.*)$/m) || [])[1] || '?';
  $('#ver').textContent = ver;
  $('#about').textContent = `DFTB Companion ${ver} — systemless tools, partition backup and reboot-resume for the DFTB app.`;

  const tools = $('#tools');
  tools.textContent = '';
  for (const t of ['rclone', 'busybox', 'nsenter', 'zstd', 'tar']) {
    const r = await exec(`command -v ${t}`);
    const ok = r.errno === 0 && r.stdout.trim();
    const chip = el('span', 'chip ' + (ok ? 'ok' : 'off'), t);
    tools.appendChild(chip);
  }

  const j = await exec('ls /data/adb/dftb/journal/*.json 2>/dev/null | wc -l');
  const trig = await exec('test -f /data/adb/dftb/resume.trigger && echo yes || echo no');
  $('#journal').textContent =
    `${(j.stdout.trim() || '0')} journal entr${j.stdout.trim() === '1' ? 'y' : 'ies'}` +
    (trig.stdout.trim() === 'yes' ? ' · resume pending' : '');

  // settings
  const conf = await exec(`cat ${CONF} 2>/dev/null`);
  $('#opt-update').checked = /^update_check=1/m.test(conf.stdout);
  const dest = (conf.stdout.match(/^dest=(.*)$/m) || [])[1];
  if (dest) $('#opt-dest').value = dest;
}

$('#check-update').addEventListener('click', async () => {
  $('#update-line').textContent = 'checking…';
  const r = await exec(`${UPDATE} --json`);
  try {
    const d = JSON.parse(r.stdout.trim());
    $('#update-line').textContent = d.available
      ? `Update available: ${d.latest} (you have ${d.current})`
      : `Up to date (${d.current})`;
    $('#update-line').className = d.available ? 'accent' : 'muted';
  } catch {
    $('#update-line').textContent = r.stdout.trim() || 'could not check';
  }
});

// ---- partitions ---------------------------------------------------------------
async function loadParts() {
  const box = $('#parts');
  box.textContent = 'loading…';
  const r = await exec(`${PARTBAK} list`);
  const rows = r.stdout.trim().split('\n').slice(1).filter(Boolean);
  if (!rows.length) { box.textContent = r.stderr.trim() || 'no partitions found'; return; }
  box.textContent = '';
  for (const line of rows) {
    const [name, size] = line.trim().split(/\s+/);
    const row = el('div', 'item');
    const info = el('div', 'item-main');
    info.appendChild(el('span', 'name', name));
    info.appendChild(el('span', 'sub', size));
    const btn = el('button', 'btn small', 'Backup');
    btn.addEventListener('click', () => backup(name, btn));
    row.appendChild(info);
    row.appendChild(btn);
    box.appendChild(row);
  }
}

async function backup(name, btn) {
  const mode = $('#use-zstd').checked ? '--zstd' : '--gzip';
  const dest = $('#opt-dest').value.trim() || '/data/adb/dftb/partitions';
  btn.disabled = true; btn.textContent = '…';
  const r = await exec(`${PARTBAK} backup ${name} ${mode} --dest ${dest}`);
  btn.disabled = false; btn.textContent = 'Backup';
  snack(r.errno === 0 ? `${name} backed up` : (r.stderr.trim() || 'backup failed'));
}

// ---- backups ------------------------------------------------------------------
async function loadBackups() {
  const dest = $('#opt-dest').value.trim() || '/data/adb/dftb/partitions';
  $('#dest-line').textContent = dest;
  const box = $('#backups');
  box.textContent = 'loading…';
  const r = await exec(`${PARTBAK} list-backups ${dest}`);
  const lines = r.stdout.trim().split('\n').filter((l) => l && !l.startsWith('('));
  if (!lines.length) { box.textContent = 'no backups yet'; return; }
  box.textContent = '';
  for (const line of lines) {
    const parts = line.trim().split(/\s+/);
    const name = parts[0];
    const img = parts[parts.length - 1];
    const file = `${dest}/${img}`;
    const row = el('div', 'item');
    const info = el('div', 'item-main');
    info.appendChild(el('span', 'name', name));
    info.appendChild(el('span', 'sub', `${parts[1] || ''} · ${img}`));
    const acts = el('div', 'item-acts');
    const v = el('button', 'btn tiny', 'Verify');
    v.addEventListener('click', async () => { const x = await exec(`${PARTBAK} verify ${file}`); snack(x.errno === 0 ? 'checksum ok' : 'checksum MISMATCH'); });
    const rs = el('button', 'btn tiny', 'Restore');
    rs.addEventListener('click', () => askRestore(name, file));
    const d = el('button', 'btn tiny danger', 'Delete');
    d.addEventListener('click', async () => { await exec(`${PARTBAK} delete ${file}`); snack('deleted'); loadBackups(); });
    acts.append(v, rs, d);
    row.appendChild(info);
    row.appendChild(acts);
    box.appendChild(row);
  }
}

// ---- restore sheet ------------------------------------------------------------
let restoreCtx = null;
function askRestore(name, file) {
  restoreCtx = { name, file };
  $('#sheet-title').textContent = `Restore ${name}`;
  $('#sheet-body').textContent = `This writes the image back to ${name} and overwrites it. Type "restore ${name}" to confirm.`;
  $('#sheet-input').value = '';
  $('#sheet-input').placeholder = `restore ${name}`;
  $('#sheet-wrap').hidden = false;
}
$('#sheet-cancel').addEventListener('click', () => { $('#sheet-wrap').hidden = true; });
$('#sheet-ok').addEventListener('click', async () => {
  if (!restoreCtx) return;
  if ($('#sheet-input').value.trim() !== `restore ${restoreCtx.name}`) { snack('confirmation did not match'); return; }
  $('#sheet-wrap').hidden = true;
  snack(`restoring ${restoreCtx.name}…`);
  const r = await exec(`${PARTBAK} restore ${restoreCtx.file} ${restoreCtx.name} --yes`);
  snack(r.errno === 0 ? `${restoreCtx.name} restored` : (r.stderr.trim() || 'restore failed'));
});

// ---- settings -----------------------------------------------------------------
$('#save-settings').addEventListener('click', async () => {
  const up = $('#opt-update').checked ? 1 : 0;
  const dest = $('#opt-dest').value.trim() || '/data/adb/dftb/partitions';
  const conf = `update_check=${up}\ndest=${dest}\n`;
  await exec(`mkdir -p /data/adb/dftb; printf '%s' ${shq(conf)} > ${CONF}`);
  snack('saved');
});

// shell-quote for a single argument
function shq(s) { return "'" + String(s).replace(/'/g, "'\\''") + "'"; }

// ---- boot ---------------------------------------------------------------------
loadStatus().catch((e) => snack('WebUI error: ' + e));
