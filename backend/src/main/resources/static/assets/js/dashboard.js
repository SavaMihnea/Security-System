/**
 * dashboard.js — Dashboard logic
 * Loads system status, sensor grid, recent events.
 * Connects to WebSocket for real-time updates.
 */

requireAuth();

const STATUS_ICON = {
    DISARMED:         '🔓',
    ARMED_HOME:       '🏠',
    ARMED_HOME_NIGHT: '🌙',
    ARMED_AWAY:       '🔒'
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
}

function updateStatusBanner(status) {
    const banner  = document.getElementById('statusBanner');
    const icon    = document.getElementById('statusIcon');
    const text    = document.getElementById('statusText');

    icon.textContent = STATUS_ICON[status.armMode] || '🔓';
    text.textContent = status.armMode.replaceAll('_', ' ');

    banner.className = 'status-banner p-4 text-center';
    const isArmed = status.armed;
    const isHome  = status.armMode === 'ARMED_HOME';

    if (!isArmed) {
        banner.classList.add('status-disarmed');
        document.getElementById('btnArmAway').classList.remove('d-none');
        document.getElementById('btnArmHome').classList.remove('d-none');
        document.getElementById('btnArmHomeNight').classList.remove('d-none');
        document.getElementById('btnDisarm').classList.add('d-none');
        CountdownTimer.clear();
    } else {
        banner.classList.add(isHome ? 'status-home' : 'status-armed');
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

// ---- Sensors grid -----------------------------------------
const STATUS_DOT = { ONLINE: 'online', TRIGGERED: 'triggered', OFFLINE: 'offline', FAULT: 'offline' };
const TYPE_ICON  = { MOTION: '👁', VIBRATION: '📳', DOOR: '🚪', CENTRAL: '🖥' };

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

    // When Stage 2 fires: AI stopped, siren is running — update the banner
    if (event.eventType === 'SIREN_ACTIVE') {
        document.getElementById('normalStateView').classList.add('d-none');
        document.getElementById('alarmStateView').classList.remove('d-none');
        document.getElementById('countdownLabel').textContent = 'ALARM ACTIVE';
        document.getElementById('countdownNumber').textContent = '🚨';
        document.getElementById('countdownNumber').classList.remove('urgent', 'threat');
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
                numEl.textContent = 'THREAT IMMINENT';
                numEl.classList.remove('urgent');
                numEl.classList.add('threat');
                subEl.textContent = '';
            }
        }, 1000);
    }

    function clear() {
        _stopInterval();
        const numEl = document.getElementById('countdownNumber');
        if (numEl) numEl.classList.remove('urgent', 'threat');
        document.getElementById('countdownLabel').textContent = 'ENTRY DELAY - ALARM PENDING';
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
                '<span class="text-success">Live</span>';

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

// ---- Init -------------------------------------------------
loadStatus();
loadSensors();
loadEvents();
loadDiagnostics();
connectWebSocket();
