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

function requireAuth() {
    if (!isAuthenticated()) {
        window.location.href = '/login.html';
    }
}

function logout() {
    localStorage.removeItem('jwt_token');
    localStorage.removeItem('username');
    window.location.href = '/login.html';
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
    if (diff < 60)   return `${diff}s ago`;
    if (diff < 3600) return `${Math.floor(diff / 60)}m ago`;
    if (diff < 86400) return `${Math.floor(diff / 3600)}h ago`;
    return new Date(dateStr).toLocaleDateString();
}
