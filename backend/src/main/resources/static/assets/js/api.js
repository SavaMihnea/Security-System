/**
 * api.js — shared utilities for all pages
 * Handles JWT storage, authenticated fetch, and auth-guard redirect.
 */

function getToken() {
    return localStorage.getItem('jwt_token');
}

function isAuthenticated() {
    return !!getToken();
}

async function requireAuth() {
    const token = getToken();
    if (!token) {
        window.location.replace('/login');
        return;
    }
    try {
        const res = await fetch('/api/system/status', {
            headers: { 'Authorization': `Bearer ${token}` }
        });
        if (res.status === 401 || res.status === 403) {
            logout();
            return;
        }
    } catch {
        // Network error — still allow page (server may be starting)
    }
    document.body.style.visibility = 'visible';
}

function logout() {
    localStorage.removeItem('jwt_token');
    localStorage.removeItem('username');
    window.location.href = '/login';
}

/**
 * Wrapper around fetch() that automatically adds the Authorization header.
 * Redirects to login on 401.
 */
async function apiFetch(endpoint, options = {}) {
    const token = getToken();
    const headers = {
        'Content-Type': 'application/json',
        ...(token ? { 'Authorization': `Bearer ${token}` } : {}),
        ...(options.headers || {})
    };

    const response = await fetch(endpoint, { ...options, headers });

    if (response.status === 401 || response.status === 403) {
        logout();
        return null;
    }

    return response;
}

function formatEventType(type) {
    return (type || '').replace(/_/g, ' ');
}

function timeAgo(dateStr) {
    const diff = Math.floor((Date.now() - new Date(dateStr)) / 1000);
    // Clamp to 0 to handle clock skew (server time ahead of client)
    const safeDiff = Math.max(0, diff);
    if (safeDiff < 60)   return `${safeDiff}s ago`;
    if (safeDiff < 3600) return `${Math.floor(safeDiff / 60)}m ago`;
    if (safeDiff < 86400) return `${Math.floor(safeDiff / 3600)}h ago`;
    return new Date(dateStr).toLocaleDateString();
}
