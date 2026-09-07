const SUPABASE_FUNCTION = 'https://abavsspydbpkudhswmzp.supabase.co/functions/v1/media-gateway';
const SUPABASE_PUBLISHABLE_KEY = 'sb_publishable_iuZnOH7ye1WITm-xc44TiQ_CNb2d2qB';
const PENDING_KEY = 'h_ai_pending_job';

const form = document.getElementById('translateForm');
const statusCard = document.getElementById('statusCard');
const resultCard = document.getElementById('resultCard');
const spinner = document.getElementById('spinner');
const statusText = document.getElementById('statusText');
const statusTitle = document.getElementById('statusTitle');
const startButton = document.getElementById('startButton');
const player = document.getElementById('player');
let currentTrackBlobUrl = null;

function clientId() {
  let id = localStorage.getItem('h_ai_client_id');
  if (!id) {
    id = 'web-' + crypto.randomUUID().replaceAll('-', '').slice(0, 20);
    localStorage.setItem('h_ai_client_id', id);
  }
  return id;
}

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  const sourceUrl = document.getElementById('sourceUrl').value.trim();
  const retention = document.getElementById('retention').value;
  if (!/^https?:\/\//i.test(sourceUrl)) return;

  setWorking('إرسال الفيلم للسحابة…');
  try {
    const accepted = await gateway({
      mode: 'translate_url',
      client_id: clientId(),
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

async function watchJob(jobId) {
  setWorking('المهمة تعمل في السحابة… ويمكن إغلاق هذه الصفحة.');
  for (;;) {
    const root = await gateway({ mode: 'get_job', client_id: clientId(), job_id: jobId });
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
  const response = await fetch(SUPABASE_FUNCTION, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'apikey': SUPABASE_PUBLISHABLE_KEY,
      'authorization': `Bearer ${SUPABASE_PUBLISHABLE_KEY}`,
    },
    body: JSON.stringify(payload),
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok || data.error) throw new Error(data.message || data.error || `Gateway ${response.status}`);
  return data;
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

// If the browser was closed while a movie was processing, resume status tracking.
const pending = localStorage.getItem(PENDING_KEY);
if (pending) {
  watchJob(pending).catch(fail);
}
