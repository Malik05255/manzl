const SUPABASE_FUNCTION = 'https://abavsspydbpkudhswmzp.supabase.co/functions/v1/media-gateway';
const SUPABASE_PUBLISHABLE_KEY = 'sb_publishable_iuZnOH7ye1WITm-xc44TiQ_CNb2d2qB';
const PENDING_KEY = 'h_ai_pending_job';
const ACCOUNT_KEY_STORAGE = 'h_ai_personal_account_key_v1';
const ACCOUNT_KEY_RE = /^[A-Za-z0-9_-]{32,128}$/;

const form = document.getElementById('translateForm');
const statusCard = document.getElementById('statusCard');
const resultCard = document.getElementById('resultCard');
const spinner = document.getElementById('spinner');
const statusText = document.getElementById('statusText');
const statusTitle = document.getElementById('statusTitle');
const startButton = document.getElementById('startButton');
const player = document.getElementById('player');
const syncKeyInput = document.getElementById('syncKey');
const copySyncKeyButton = document.getElementById('copySyncKey');
const newSyncKeyButton = document.getElementById('newSyncKey');
let currentTrackBlobUrl = null;

initializePersonalKey();

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  const sourceUrl = document.getElementById('sourceUrl').value.trim();
  const retention = document.getElementById('retention').value;
  if (!/^https?:\/\//i.test(sourceUrl)) return;

  const key = saveAndGetPersonalKey();
  if (!key) {
    fail(new Error('مفتاح H AI الشخصي غير صالح.'));
    return;
  }

  setWorking('إرسال الفيلم للسحابة…');
  try {
    const accepted = await gateway({
      mode: 'translate_url',
      source_url: sourceUrl,
      retention,
      language: 'auto',
    });
    const jobId = accepted.job_id;
    if (!jobId) throw new Error('لم تُرجع البوابة رقم المهمة');
    localStorage.setItem(PENDING_KEY, jobId);
    await watchJob(jobId);
  } catch (error) {
    fail(error);
  }
});

syncKeyInput.addEventListener('change', () => {
  const previous = localStorage.getItem(ACCOUNT_KEY_STORAGE) || '';
  const value = syncKeyInput.value.trim();
  if (!ACCOUNT_KEY_RE.test(value)) {
    syncKeyInput.value = previous || ensurePersonalKey();
    fail(new Error('مفتاح المزامنة يجب أن يكون مفتاح H AI صالحًا.'));
    return;
  }
  if (value !== previous) localStorage.removeItem(PENDING_KEY);
  localStorage.setItem(ACCOUNT_KEY_STORAGE, value);
});

copySyncKeyButton.addEventListener('click', async () => {
  const key = saveAndGetPersonalKey();
  if (!key) return;
  try {
    await navigator.clipboard.writeText(key);
    copySyncKeyButton.textContent = 'تم النسخ';
    setTimeout(() => { copySyncKeyButton.textContent = 'نسخ'; }, 1400);
  } catch {
    syncKeyInput.type = 'text';
    syncKeyInput.select();
  }
});

newSyncKeyButton.addEventListener('click', () => {
  const ok = window.confirm('إنشاء مفتاح جديد سيفصل هذا المتصفح عن المكتبة المرتبطة بالمفتاح الحالي. هل تريد المتابعة؟');
  if (!ok) return;
  const created = generatePersonalKey();
  localStorage.setItem(ACCOUNT_KEY_STORAGE, created);
  localStorage.removeItem(PENDING_KEY);
  syncKeyInput.value = created;
});

async function watchJob(jobId) {
  setWorking('المهمة تعمل في السحابة… ويمكن إغلاق هذه الصفحة.');
  for (;;) {
    const root = await gateway({ mode: 'get_job', job_id: jobId });
    const job = root.job;
    if (!job) {
      statusText.textContent = 'بانتظار ظهور المهمة على الخادم…';
      await sleep(1600);
      continue;
    }

    const percent = Math.round(Math.max(0, Math.min(1, Number(job.progress || 0))) * 100);
    statusTitle.textContent = `${percent}%`;
    statusText.textContent = job.stage || 'المعالجة السحابية';

    if (job.status === 'completed') {
      localStorage.removeItem(PENDING_KEY);
      spinner.classList.add('hidden');
      startButton.disabled = false;
      renderJob(job);
      return;
    }
    if (job.status === 'failed' || job.status === 'cancelled') {
      localStorage.removeItem(PENDING_KEY);
      throw new Error(job.error || job.stage || 'تعذر إكمال المهمة');
    }
    await sleep(2000);
  }
}

async function gateway(payload) {
  const body = { ...payload };
  if (body.mode !== 'plan') {
    const key = saveAndGetPersonalKey();
    if (!key) throw new Error('مفتاح H AI الشخصي مطلوب.');
    body.account_key = key;
  }

  const response = await fetch(SUPABASE_FUNCTION, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'apikey': SUPABASE_PUBLISHABLE_KEY,
    },
    body: JSON.stringify(body),
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok || data.error) throw new Error(data.message || data.error || `Gateway ${response.status}`);
  return data;
}

function initializePersonalKey() {
  syncKeyInput.value = ensurePersonalKey();
}

function ensurePersonalKey() {
  const existing = localStorage.getItem(ACCOUNT_KEY_STORAGE) || '';
  if (ACCOUNT_KEY_RE.test(existing)) return existing;
  const created = generatePersonalKey();
  localStorage.setItem(ACCOUNT_KEY_STORAGE, created);
  localStorage.removeItem(PENDING_KEY);
  return created;
}

function saveAndGetPersonalKey() {
  const typed = syncKeyInput.value.trim();
  if (!ACCOUNT_KEY_RE.test(typed)) return null;
  localStorage.setItem(ACCOUNT_KEY_STORAGE, typed);
  return typed;
}

function generatePersonalKey() {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  let binary = '';
  bytes.forEach((b) => { binary += String.fromCharCode(b); });
  return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/g, '');
}

function setWorking(text) {
  statusCard.classList.remove('hidden');
  resultCard.classList.add('hidden');
  spinner.classList.remove('hidden');
  statusTitle.textContent = 'H AI';
  statusText.textContent = text;
  startButton.disabled = true;
}

function fail(error) {
  statusCard.classList.remove('hidden');
  spinner.classList.add('hidden');
  statusTitle.textContent = 'تعذر الإكمال';
  statusText.textContent = error?.message || 'تعذر إكمال المهمة';
  startButton.disabled = false;
}

function renderJob(job) {
  document.getElementById('movieTitle').textContent = job.title || 'الفيلم';
  document.getElementById('language').textContent = `اللغة: ${job.source_language || 'auto'}`;
  document.getElementById('providers').textContent = providerText(job.provider_trace, job.asr_route);
  document.getElementById('summary').textContent = job.summary?.summary || 'اكتملت الترجمة. لا يوجد ملخص متاح حاليًا.';
  fillList('characters', job.summary?.characters, (x) => typeof x === 'string' ? x : `${x.name || ''}${x.role ? ' — ' + x.role : ''}`);
  fillList('events', job.summary?.major_events, (x) => String(x));

  if (currentTrackBlobUrl) {
    URL.revokeObjectURL(currentTrackBlobUrl);
    currentTrackBlobUrl = null;
  }

  player.pause();
  player.innerHTML = '';
  player.src = job.playback_url || job.source_url;

  const track = document.createElement('track');
  track.kind = 'subtitles';
  track.label = 'العربية';
  track.srclang = 'ar';
  track.default = true;

  if (job.subtitle_url) {
    track.src = job.subtitle_url;
    player.appendChild(track);
  } else if (job.vtt_text) {
    const blob = new Blob([job.vtt_text], { type: 'text/vtt' });
    currentTrackBlobUrl = URL.createObjectURL(blob);
    track.src = currentTrackBlobUrl;
    player.appendChild(track);
  }

  player.load();
  resultCard.classList.remove('hidden');
  statusText.textContent = 'جاهز للمشاهدة';
  statusTitle.textContent = '100%';
}

function providerText(items, asrRoute) {
  const names = Array.isArray(items) ? [...new Set(items.map((x) => x?.provider).filter(Boolean))] : [];
  const route = asrRoute ? `ASR: ${asrRoute}` : '';
  return [names.length ? names.join(' + ') : 'Smart Router', route].filter(Boolean).join(' • ');
}

function fillList(id, items, format) {
  const node = document.getElementById(id);
  node.innerHTML = '';
  (Array.isArray(items) ? items : []).slice(0, 20).forEach((item) => {
    const li = document.createElement('li');
    li.textContent = format(item);
    node.appendChild(li);
  });
}

function sleep(ms) { return new Promise((resolve) => setTimeout(resolve, ms)); }

const pending = localStorage.getItem(PENDING_KEY);
if (pending) {
  watchJob(pending).catch(fail);
}
