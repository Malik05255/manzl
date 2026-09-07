const SUPABASE_FUNCTION = 'https://abavsspydbpkudhswmzp.supabase.co/functions/v1/media-gateway';
const SUPABASE_PUBLISHABLE_KEY = 'sb_publishable_iuZnOH7ye1WITm-xc44TiQ_CNb2d2qB';

const form = document.getElementById('translateForm');
const statusCard = document.getElementById('statusCard');
const resultCard = document.getElementById('resultCard');
const spinner = document.getElementById('spinner');
const statusText = document.getElementById('statusText');
const startButton = document.getElementById('startButton');
const player = document.getElementById('player');

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

  statusCard.classList.remove('hidden');
  resultCard.classList.add('hidden');
  spinner.classList.remove('hidden');
  statusText.textContent = 'اكتشاف اللغة وفهم الحوار وترجمته…';
  startButton.disabled = true;

  try {
    const response = await fetch(SUPABASE_FUNCTION, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'apikey': SUPABASE_PUBLISHABLE_KEY,
        'authorization': `Bearer ${SUPABASE_PUBLISHABLE_KEY}`,
      },
      body: JSON.stringify({
        mode: 'translate_url',
        client_id: clientId(),
        source_url: sourceUrl,
        retention,
        language: 'auto',
      }),
    });
    const data = await response.json();
    if (!response.ok || data.error) throw new Error(data.message || data.error || 'تعذر إكمال الترجمة');

    renderResult(data);
    statusText.textContent = 'جاهز للمشاهدة';
  } catch (error) {
    statusText.textContent = error.message || 'تعذر إكمال المهمة';
  } finally {
    spinner.classList.add('hidden');
    startButton.disabled = false;
  }
});

function renderResult(data) {
  document.getElementById('movieTitle').textContent = data.title || 'الفيلم';
  document.getElementById('language').textContent = `اللغة: ${data.detected_language || 'auto'}`;
  document.getElementById('providers').textContent = providerText(data.providers);
  document.getElementById('summary').textContent = data.summary?.summary || 'اكتملت الترجمة. لا يوجد ملخص متاح حاليًا.';
  fillList('characters', data.summary?.characters, (x) => typeof x === 'string' ? x : `${x.name || ''}${x.role ? ' — ' + x.role : ''}`);
  fillList('events', data.summary?.major_events, (x) => String(x));

  player.innerHTML = '';
  player.src = data.playback_url || data.source_url;
  if (data.vtt) {
    const blob = new Blob([data.vtt], { type: 'text/vtt' });
    const track = document.createElement('track');
    track.kind = 'subtitles';
    track.label = 'العربية';
    track.srclang = 'ar';
    track.default = true;
    track.src = URL.createObjectURL(blob);
    player.appendChild(track);
  }
  resultCard.classList.remove('hidden');
}

function providerText(items) {
  if (!Array.isArray(items)) return 'Smart Router';
  const names = [...new Set(items.map((x) => x?.provider).filter(Boolean))];
  return names.length ? names.join(' + ') : 'Smart Router';
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
