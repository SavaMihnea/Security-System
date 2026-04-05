/**
 * dashboard.js — Dashboard logic
 * Loads system status, sensor grid, recent events.
 * Connects to WebSocket for real-time updates.
 */

requireAuth();
document.getElementById('navUsername').textContent = localStorage.getItem('username') || '';

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
    const meta    = document.getElementById('statusMeta');


    if (status.lastUpdated) {
        meta.textContent = `Last changed ${timeAgo(status.lastUpdated)}${status.updatedBy ? ' by ' + status.updatedBy : ''}`;
    }

    icon.textContent = STATUS_ICON[status.armMode] || '🔓';
    text.textContent = status.armMode.replaceAll('_', ' ');

    banner.className = 'status-banner p-4 rounded-3 text-center';
    const isArmed = status.armed;
    const isHome  = status.armMode === 'ARMED_HOME';

    if (!isArmed) {
        banner.classList.add('status-disarmed');
        document.getElementById('btnArmAway').classList.remove('d-none');
        document.getElementById('btnArmHome').classList.remove('d-none');
        document.getElementById('btnArmHomeNight').classList.remove('d-none');
        document.getElementById('btnDisarm').classList.add('d-none');
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

async function loadSensors() {
    const container = document.getElementById('sensorGrid');
    const res = await apiFetch('/api/sensors');
    if (!res) return;
    const sensors = await res.json();

    document.getElementById('statTotalSensors').textContent = sensors.length;
    document.getElementById('statOnline').textContent =
        sensors.filter(s => s.status === 'ONLINE' || s.status === 'TRIGGERED').length;

    if (sensors.length === 0) {
        container.innerHTML = '<p class="text-muted small text-center">No sensors registered yet.</p>';
        return;
    }

    container.innerHTML = sensors.map(s => `
        <div class="sensor-chip ${STATUS_DOT[s.status] || 'offline'}">
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
    document.getElementById('statAlarms').textContent = activeAlarms.length;

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
        const current = parseInt(document.getElementById('statAlarms').textContent) || 0;
        document.getElementById('statAlarms').textContent = current + 1;
    }
}

// ---- Toast notification -----------------------------------
function showAlertToast(event) {
    document.getElementById('alertToastBody').textContent =
        `${formatEventType(event.eventType)}${event.sensorName ? ' — ' + event.sensorName : ''}`;
    const toastEl = document.getElementById('alertToast');
    bootstrap.Toast.getOrCreateInstance(toastEl, { delay: 6000 }).show();
}

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

// ---- Init -------------------------------------------------
loadStatus();
loadSensors();
loadEvents();
connectWebSocket();
