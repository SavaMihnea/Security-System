/**
 * dashboard.js — Dashboard logic
 * Loads system status, sensor grid, recent events.
 * Connects to WebSocket for real-time updates.
 */

requireAuth();

// Hide gear icon for non-admin users (settings modal is admin-only)
if (!isAdmin()) {
    const gear = document.getElementById('btnSettings');
    if (gear) gear.style.display = 'none';
}

// Auto-open settings when redirected from another page via ?settings=open
if (new URLSearchParams(window.location.search).get('settings') === 'open') {
    openSettingsModal();
}

const _SVG = {
    lockOpen: `<svg xmlns="http://www.w3.org/2000/svg" width="44" height="44" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="square" stroke-linejoin="miter"><rect x="3" y="11" width="18" height="11"/><path d="M7 11V7a5 5 0 0 1 9.9-1"/></svg>`,
    lockClosed:`<svg xmlns="http://www.w3.org/2000/svg" width="44" height="44" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="square" stroke-linejoin="miter"><rect x="3" y="11" width="18" height="11"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>`,
    home:      `<svg xmlns="http://www.w3.org/2000/svg" width="44" height="44" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="square" stroke-linejoin="miter"><path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/></svg>`,
    moon:      `<svg xmlns="http://www.w3.org/2000/svg" width="44" height="44" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="square" stroke-linejoin="miter"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>`,
};
const STATUS_ICON = {
    DISARMED:         _SVG.lockOpen,
    ARMED_HOME:       _SVG.home,
    ARMED_HOME_NIGHT: _SVG.moon,
    ARMED_AWAY:       _SVG.lockClosed
};

const ALARM_EVENT_TYPES = new Set([
    'ALARM_TRIGGERED', 'MOTION_DETECTED', 'VIBRATION_DETECTED', 'DOOR_OPENED'
]);

// ---- System Status ----------------------------------------
async function loadStatus() {
    const res = await apiFetch('/api/system/status');
    if (!res) return;
    const status = await res.json();
    updateStatusBanner(status);
    loadSchedule(status);
}

function updateStatusBanner(status) {
    const banner  = document.getElementById('statusBanner');
    const icon    = document.getElementById('statusIcon');
    const text    = document.getElementById('statusText');

    const ARM_LABEL = {
        DISARMED:         'DISARMED',
        ARMED_AWAY:       'ARMED AWAY',
        ARMED_HOME:       'ARMED HOME',
        ARMED_HOME_NIGHT: 'ARMED NIGHT'
    };

    icon.innerHTML = STATUS_ICON[status.armMode] || _SVG.lockOpen;
    text.textContent = ARM_LABEL[status.armMode] || status.armMode.replaceAll('_', ' ');

    banner.className = 'command-core status-banner tac';
    const isArmed = status.armed;
    const isHome  = status.armMode === 'ARMED_HOME';
    const isNight = status.armMode === 'ARMED_HOME_NIGHT';

    if (!isArmed) {
        banner.classList.add('status-disarmed');
        document.getElementById('btnArmAway').classList.remove('d-none');
        document.getElementById('btnArmHome').classList.remove('d-none');
        document.getElementById('btnArmHomeNight').classList.remove('d-none');
        document.getElementById('btnDisarm').classList.add('d-none');
        CountdownTimer.clear();
        _sirenActive = false;
        _panicMode   = false;
        updatePanicNavBtn();
    } else {
        banner.classList.add(isNight ? 'status-night' : isHome ? 'status-home' : 'status-armed');
        document.getElementById('btnArmAway').classList.add('d-none');
        document.getElementById('btnArmHome').classList.add('d-none');
        document.getElementById('btnArmHomeNight').classList.add('d-none');
        document.getElementById('btnDisarm').classList.remove('d-none');
    }
}

async function arm(mode) {
    const res = await apiFetch(`/api/system/arm?mode=${mode}`, { method: 'POST' });
    if (res?.ok) updateStatusBanner(await res.json());
}

async function disarm() {
    const res = await apiFetch('/api/system/disarm', { method: 'POST' });
    if (res?.ok) updateStatusBanner(await res.json());
}

// ---- Panic nav button state ------------------------------
let _sirenActive = false;
let _panicMode   = false;   // true when siren was started via manual panic button

function updatePanicNavBtn() {
    const btn = document.getElementById('btnPanicNav');
    if (!btn) return;
    if (_sirenActive) {
        btn.innerHTML = '<span style="letter-spacing:0;margin-right:4px">&#9632;</span>STOP SIREN';
        btn.classList.add('siren-active');
    } else {
        btn.innerHTML = '<span style="letter-spacing:0;margin-right:4px">&#9888;</span>PANIC';
        btn.classList.remove('siren-active');
    }
}

function handlePanicNavClick() {
    if (_sirenActive) {
        disarm();
    } else {
        openPanicModal();
    }
}

// ---- Panic modal -----------------------------------------
function openPanicModal() {
    const btn = document.getElementById('btnPanicConfirm');
    if (btn) { btn.disabled = false; btn.innerHTML = '&#9888;&nbsp; ACTIVATE'; }
    document.getElementById('panicModal').style.display = 'flex';
}

function closePanicModal() {
    document.getElementById('panicModal').style.display = 'none';
}

async function confirmPanic() {
    const btn = document.getElementById('btnPanicConfirm');
    btn.disabled = true;
    btn.textContent = 'ACTIVATING…';
    // Set _panicMode BEFORE the request — the SIREN_ACTIVE WebSocket event
    // can arrive before the HTTP response, and must already see panicMode=true
    _panicMode = true;
    const res = await apiFetch('/api/system/panic', { method: 'POST' });
    closePanicModal();
    if (res?.ok) {
        _sirenActive = true;
        updatePanicNavBtn();
    } else {
        _panicMode = false;
    }
}

document.addEventListener('keydown', e => {
    if (e.key === 'Escape') { closePanicModal(); }
});

// ---- Sensors grid -----------------------------------------
const STATUS_DOT = { ONLINE: 'online', TRIGGERED: 'triggered', OFFLINE: 'offline', FAULT: 'offline' };
const TYPE_ICON  = {
    MOTION:    `<svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="square"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>`,
    VIBRATION: `<svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="square"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>`,
    DOOR:      `<svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="square"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><circle cx="15" cy="13" r="1" fill="currentColor"/></svg>`,
    CENTRAL:   `<svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="square"><rect x="2" y="3" width="20" height="14"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>`
};

// Helper function to check if a sensor is online based on last heartbeat
const isOnline = (lastHeartbeatISO) => {
    // This forces the string into a Date object.
    // If the backend sent it with 'Z', JS automatically converts it to local time.
    const heartbeat = new Date(lastHeartbeatISO);
    const now = new Date();

    // Difference in seconds
    const diff = (now.getTime() - heartbeat.getTime()) / 1000;

    // If heartbeat was within the last 90 seconds, it's online
    return diff < 90;
};

async function loadSensors() {
    const container = document.getElementById('sensorGrid');
    const res = await apiFetch('/api/sensors');
    if (!res) return;
    const sensors = await res.json();

    document.getElementById('statTotalSensors').textContent = sensors.length;
    document.getElementById('statOnline').textContent =
        sensors.filter(s => s.status === 'ONLINE' || s.status === 'TRIGGERED').length;

    // Gateway ESP32-S3 status (CENTRAL sensor type)
    const gateway = sensors.find(s => s.type === 'CENTRAL');
    const gwEl  = document.getElementById('statGateway');
    const hbEl  = document.getElementById('diagHeartbeat');
    const fnEl  = document.getElementById('diagFaultNodes');

    if (gateway && gateway.lastSeen) {
        const gatewayOnline = isOnline(gateway.lastSeen);
        const ageSec = Math.floor((Date.now() - new Date(gateway.lastSeen)) / 1000);
        gwEl.innerHTML = `<span class="${gatewayOnline ? 'text-success' : 'text-danger'}">${gatewayOnline ? 'Online' : 'Offline'}</span>`;
        const hbClass = ageSec < 60 ? 'text-success' : ageSec < 300 ? 'text-warning' : 'text-danger';
        const hbText  = timeAgo(gateway.lastSeen).replace(/^(\d)/, '$1').replace(/\b(\w)/g, c => c.toUpperCase());
        hbEl.innerHTML = `<span class="${hbClass}">${hbText}</span>`;
    } else {
        gwEl.innerHTML = '<span class="text-danger">Offline</span>';
        hbEl.innerHTML = '<span class="text-danger">Offline</span>';
    }

    const faultCount = sensors.filter(s => s.status === 'FAULT').length;
    fnEl.innerHTML = faultCount > 0
        ? `<span class="text-danger">${faultCount}</span>`
        : `<span class="text-white">0</span>`;

    if (sensors.length === 0) {
        container.innerHTML = '<p class="text-muted small text-center">No sensors registered yet.</p>';
        return;
    }

    container.innerHTML = sensors.map(s => `
        <div class="sensor-chip sensor-chip-enter ${STATUS_DOT[s.status] || 'offline'}">
            <span class="fs-5">${TYPE_ICON[s.type] || '🔌'}</span>
            <div class="flex-grow-1 overflow-hidden">
                <div class="sensor-chip-name text-truncate">${s.name}</div>
                <div class="sensor-chip-meta">${s.location || ''}
                    ${s.lastSeen ? '· ' + timeAgo(s.lastSeen) : ''}</div>
            </div>
            <span class="badge ${s.status === 'ONLINE' ? 'bg-success' :
                                  s.status === 'TRIGGERED' ? 'bg-danger' : 'bg-secondary'} small">
                ${s.status}
            </span>
        </div>
    `).join('');
}

// ---- Recent events ----------------------------------------
const EVENT_BADGE = {
    ALARM_TRIGGERED:    'bg-danger',
    SIREN_ACTIVE:       'bg-danger',
    ALARM_DISARMED:     'bg-success',
    MOTION_DETECTED:    'bg-warning text-dark',
    VIBRATION_DETECTED: 'bg-warning text-dark',
    DOOR_OPENED:        'bg-info text-dark',
    DOOR_CLOSED:        'bg-secondary',
    SYSTEM_ARMED:       'bg-primary',
    SYSTEM_DISARMED:    'bg-secondary',
    NODE_ONLINE:        'bg-success',
    DEFAULT:            'bg-secondary'
};

let events = [];

async function loadEvents() {
    const res = await apiFetch('/api/events');
    if (!res) return;
    events = await res.json();

    const activeAlarms = events.filter(e => !e.resolved && ALARM_EVENT_TYPES.has(e.eventType));
    const alEl = document.getElementById('diagAlarms');
    alEl.innerHTML = activeAlarms.length > 0
        ? `<span class="text-danger">${activeAlarms.length}</span>`
        : `<span class="text-white">0</span>`;

    const lastArmed    = events.find(e => e.eventType === 'SYSTEM_ARMED');
    const lastDisarmed = events.find(e => e.eventType === 'SYSTEM_DISARMED');
    document.getElementById('diagLastArmed').textContent    = lastArmed    ? timeAgo(lastArmed.timestamp)    : 'NEVER';
    document.getElementById('diagLastDisarmed').textContent = lastDisarmed ? timeAgo(lastDisarmed.timestamp) : 'NEVER';

    renderEventList(events.slice(0, 10));

    // Determine siren state from most recent relevant event on page load
    const lastSirenEvent = events.find(e =>
        ['SIREN_ACTIVE', 'ALARM_TRIGGERED', 'ALARM_DISARMED', 'SYSTEM_DISARMED', 'ALARM_CANCELLED'].includes(e.eventType));
    if (lastSirenEvent?.eventType === 'SIREN_ACTIVE') {
        _sirenActive = true;
        updatePanicNavBtn();
    }
}

function renderEventList(list) {
    const container = document.getElementById('recentEvents');
    if (list.length === 0) {
        container.innerHTML = '<p class="text-muted small text-center py-3">No events yet.</p>';
        return;
    }
    container.innerHTML = list.map(ev => `
        <div class="event-row">
            <span class="event-time">${timeAgo(ev.timestamp)}</span>
            <span class="event-type">
                <span class="badge ${EVENT_BADGE[ev.eventType] || EVENT_BADGE.DEFAULT} small">
                    ${formatEventType(ev.eventType)}
                </span>
                ${ev.sensorName ? `<span class="text-muted small ms-1">${ev.sensorName}</span>` : ''}
            </span>
        </div>
    `).join('');
}

function addEventToList(event) {
    events.unshift(event);
    renderEventList(events.slice(0, 10));

    if (!event.resolved && ALARM_EVENT_TYPES.has(event.eventType)) {
        const alEl = document.getElementById('diagAlarms');
        const current = parseInt(alEl.textContent) || 0;
        const next = current + 1;
        alEl.innerHTML = `<span class="text-danger">${next}</span>`;
    }

    if (event.eventType === 'SIREN_ACTIVE') {
        _sirenActive = true;
        updatePanicNavBtn();
    }
    if (event.eventType === 'ALARM_DISARMED') {
        _sirenActive = false;
        updatePanicNavBtn();
    }

    // When Stage 2 fires: AI stopped, siren is running — update the banner.
    // Skip if the siren was started manually via the panic button (_panicMode).
    if (event.eventType === 'SIREN_ACTIVE' && !_panicMode) {
        document.getElementById('normalStateView').classList.add('d-none');
        document.getElementById('alarmStateView').classList.remove('d-none');
        document.getElementById('countdownNumber').classList.add('d-none');
        document.getElementById('threatImminentMsg').classList.add('d-none');
        document.getElementById('sirenIcon').classList.remove('d-none');
        document.getElementById('countdownLabel').textContent = 'ALARM ACTIVE';
        document.getElementById('countdownSub').textContent = 'AI deterrence complete — siren is active';
        document.getElementById('sirenActiveMsg').classList.remove('d-none');
    }
}

// ---- Toast notification -----------------------------------
function showAlertToast(event) {
    document.getElementById('alertToastBody').textContent =
        `${formatEventType(event.eventType)}${event.sensorName ? ' — ' + event.sensorName : ''}`;
    const toastEl = document.getElementById('alertToast');
    bootstrap.Toast.getOrCreateInstance(toastEl, { delay: 6000 }).show();
}

// ---- Countdown Timer (Entry Delay) -----------------------
/**
 * Manages the ALARM_PENDING countdown banner.
 *
 * Shown when a DOOR sensor fires in ARMED_HOME or ARMED_AWAY — the
 * backend broadcasts ALARM_PENDING with the configured entry-delay seconds.
 * The user has until the counter reaches 0 to hit "DISARM NOW" before
 * Stage 1 (AI deterrence) activates on the hub.
 *
 * Clears itself automatically when:
 *   - User hits Disarm (backend sends ALARM_CANCELLED → WebSocket)
 *   - System is disarmed via any path (updateStatusBanner detects !armed)
 */
const CountdownTimer = (() => {
    let _interval = null;

    function show(sensorName, totalSeconds) {
        _stopInterval();

        const numEl  = document.getElementById('countdownNumber');
        const subEl  = document.getElementById('countdownSub');
        const senEl  = document.getElementById('countdownSensor');

        senEl.textContent = `Triggered by: ${sensorName}`;
        numEl.textContent = totalSeconds;
        numEl.classList.remove('urgent', 'threat');
        subEl.textContent = 'seconds until AI deterrence activates';
        document.getElementById('normalStateView').classList.add('d-none');
        document.getElementById('alarmStateView').classList.remove('d-none');

        let remaining = totalSeconds;
        _interval = setInterval(() => {
            remaining--;
            numEl.textContent = remaining;

            if (remaining <= 10) numEl.classList.add('urgent');

            if (remaining <= 0) {
                _stopInterval();
                numEl.classList.add('d-none');
                document.getElementById('threatImminentMsg').classList.remove('d-none');
                subEl.textContent = '';
            }
        }, 1000);
    }

    function clear() {
        _stopInterval();
        const numEl = document.getElementById('countdownNumber');
        if (numEl) {
            numEl.classList.remove('urgent', 'threat', 'd-none');
            numEl.textContent = '—';
        }
        document.getElementById('sirenIcon').classList.add('d-none');
        document.getElementById('threatImminentMsg').classList.add('d-none');
        document.getElementById('countdownLabel').textContent = 'ENTRY DELAY — ALARM PENDING';
        document.getElementById('sirenActiveMsg').classList.add('d-none');
        document.getElementById('alarmStateView').classList.add('d-none');
        document.getElementById('normalStateView').classList.remove('d-none');
    }

    function _stopInterval() {
        if (_interval) { clearInterval(_interval); _interval = null; }
    }

    return { show, clear };
})();

// ---- WebSocket (real-time) --------------------------------
function connectWebSocket() {
    const client = new StompJs.Client({
        webSocketFactory: () => new SockJS('/ws'),
        reconnectDelay: 5000,
        onConnect: () => {
            document.getElementById('statWs').innerHTML =
                '<span class="ws-live">Live</span>';

            // New events from sensors
            client.subscribe('/topic/events', (message) => {
                const event = JSON.parse(message.body);
                addEventToList(event);
                if (ALARM_EVENT_TYPES.has(event.eventType)) {
                    showAlertToast(event);
                    // Refresh sensor status when a trigger arrives
                    loadSensors();
                }
            });

            // Arm/disarm state changes
            client.subscribe('/topic/system-status', (message) => {
                const status = JSON.parse(message.body);
                updateStatusBanner(status);
            });

            // Entry-delay countdown control
            client.subscribe('/topic/alarm-pending', (message) => {
                const data = JSON.parse(message.body);
                if (data.command === 'ALARM_PENDING') {
                    CountdownTimer.show(data.sensorName, data.entryDelaySeconds);
                } else if (data.command === 'ALARM_CANCELLED') {
                    CountdownTimer.clear();
                    _sirenActive = false;
                    updatePanicNavBtn();
                }
            });
        },
        onDisconnect: () => {
            document.getElementById('statWs').innerHTML =
                '<span class="text-danger">Offline</span>';
        },
        onStompError: () => {
            document.getElementById('statWs').innerHTML =
                '<span class="text-warning">Error</span>';
        }
    });

    client.activate();
}

// ---- AI / diagnostics ------------------------------------
async function loadDiagnostics() {
    const res = await apiFetch('/api/system/diagnostics');
    if (!res) return;
    const data = await res.json();
    const el = document.getElementById('diagAiStatus');
    const colorMap  = { READY: 'text-success', BUSY: 'text-warning', OFFLINE: 'text-danger' };
    const labelMap  = { READY: 'Ready', BUSY: 'Busy', OFFLINE: 'Offline' };
    el.innerHTML = `<span class="${colorMap[data.aiStatus] || 'text-muted'}">${labelMap[data.aiStatus] || '—'}</span>`;
}

// ---- Schedule --------------------------------------------
function loadSchedule(status) {
    document.getElementById('scheduleEnabled').checked       = status.scheduleEnabled || false;
    document.getElementById('schedNightArmTime').value       = status.scheduleNightArmTime    || '';
    document.getElementById('schedNightDisarmTime').value    = status.scheduleNightDisarmTime || '';
    document.getElementById('schedHomeArmTime').value        = status.scheduleHomeArmTime     || '';
    document.getElementById('schedHomeDisarmTime').value     = status.scheduleHomeDisarmTime  || '';
    document.getElementById('schedAwayArmTime').value        = status.scheduleAwayArmTime     || '';
    document.getElementById('schedAwayDisarmTime').value     = status.scheduleAwayDisarmTime  || '';

    const days = (status.scheduleDays || '').split(',').filter(Boolean);
    document.querySelectorAll('.sched-day-btn[data-day]').forEach(btn => {
        btn.classList.toggle('active', days.includes(btn.dataset.day));
    });
}

function toggleScheduleDay(day) {
    const btn = document.querySelector(`.sched-day-btn[data-day="${day}"]`);
    if (btn) btn.classList.toggle('active');
    saveSchedule();
}

function selectAllScheduleDays() {
    const btns = document.querySelectorAll('.sched-day-btn[data-day]');
    const allActive = Array.from(btns).every(btn => btn.classList.contains('active'));
    btns.forEach(btn => btn.classList.toggle('active', !allActive));
    saveSchedule();
}

function getSelectedScheduleDays() {
    return Array.from(document.querySelectorAll('.sched-day-btn[data-day].active'))
        .map(btn => btn.dataset.day).join(',');
}

let _scheduleSaveTimer = null;
async function saveSchedule() {
    clearTimeout(_scheduleSaveTimer);
    _scheduleSaveTimer = setTimeout(async () => {
        const statusEl = document.getElementById('scheduleSaveStatus');
        statusEl.textContent = 'Saving…';
        const res = await apiFetch('/api/system/schedule', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                scheduleEnabled:        String(document.getElementById('scheduleEnabled').checked),
                scheduleDays:           getSelectedScheduleDays() || null,
                scheduleNightArmTime:   document.getElementById('schedNightArmTime').value    || null,
                scheduleNightDisarmTime:document.getElementById('schedNightDisarmTime').value || null,
                scheduleHomeArmTime:    document.getElementById('schedHomeArmTime').value     || null,
                scheduleHomeDisarmTime: document.getElementById('schedHomeDisarmTime').value  || null,
                scheduleAwayArmTime:    document.getElementById('schedAwayArmTime').value     || null,
                scheduleAwayDisarmTime: document.getElementById('schedAwayDisarmTime').value  || null,
            })
        });
        statusEl.textContent = res?.ok ? 'Saved' : 'Error';
        setTimeout(() => { statusEl.textContent = ''; }, 2000);
    }, 600);
}

// ---- Init -------------------------------------------------
loadStatus();
loadSensors();
loadEvents();
loadDiagnostics();
connectWebSocket();
