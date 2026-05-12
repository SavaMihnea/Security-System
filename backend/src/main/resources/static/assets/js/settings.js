/**
 * settings.js — Self-contained settings modal, included on every page.
 * Injects modal HTML, handles account management and password changes.
 */

const _EYE_SVG = `<svg xmlns="http://www.w3.org/2000/svg" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="square"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>`;

(function injectSettingsModal() {
    const div = document.createElement('div');
    div.innerHTML = `
<div id="settingsModal" style="display:none;position:fixed;inset:0;z-index:2000;background:rgba(0,0,0,0.82);align-items:flex-start;justify-content:center;padding:max(20px,calc(50vh - 300px)) 20px 40px;overflow-y:auto;">
  <div class="vox-card tac" style="width:100%;max-width:800px;padding:40px 44px 36px;position:relative;min-height:560px;">
    <span class="tc-tr"></span><span class="tc-bl"></span>

    <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:32px;">
      <div>
        <div class="page-eyebrow" style="margin:0 0 8px;font-size:0.72rem;letter-spacing:0.18em;">Account Settings</div>
        <div style="font-family:'JetBrains Mono',monospace;font-size:1.2rem;font-weight:700;letter-spacing:0.08em;color:var(--text-1);" id="settingsUsername"></div>
      </div>
      <button class="vox-nav-logout" style="padding:10px 18px;font-size:0.9rem;" onclick="closeSettingsModal()">&#10005;</button>
    </div>

    <div class="d-flex gap-3 mb-5">
      <button class="vox-filter-btn active" id="tabAccounts" onclick="switchTab('accounts')" style="font-size:0.82rem;padding:10px 26px;letter-spacing:0.1em;">Accounts</button>
      <button class="vox-filter-btn" id="tabPassword" onclick="switchTab('password')" style="font-size:0.82rem;padding:10px 26px;letter-spacing:0.1em;">Password</button>
    </div>

    <!-- ACCOUNTS TAB -->
    <div id="panelAccounts">
      <div class="page-eyebrow mb-3" style="font-size:0.72rem;letter-spacing:0.18em;">User Accounts</div>
      <div id="userList" style="margin-bottom:36px;"></div>

      <div class="page-eyebrow mb-3" style="font-size:0.72rem;letter-spacing:0.18em;">Create New Account</div>
      <div class="mb-3">
        <label class="login-label" style="font-size:0.74rem;margin-bottom:8px;display:block;">Username</label>
        <input type="text" id="newUserUsername" class="form-control form-control-dark"
               placeholder="Enter username" autocomplete="off"
               style="font-size:0.92rem;padding:11px 14px;">
      </div>
      <div class="mb-4">
        <label class="login-label" style="font-size:0.74rem;margin-bottom:8px;display:block;">
          Password <span style="color:var(--text-3);font-size:0.68rem;">(minimum 8 characters)</span>
        </label>
        <div class="position-relative">
          <input type="password" id="newUserPassword" class="form-control form-control-dark pe-5"
                 placeholder="Enter password" autocomplete="new-password"
                 style="font-size:0.92rem;padding:11px 14px;">
          <button type="button" class="login-pw-toggle" onclick="toggleSettingsPw('newUserPassword')"
                  tabindex="-1" aria-label="Toggle visibility">${_EYE_SVG}</button>
        </div>
      </div>
      <div class="d-flex justify-content-end">
        <button class="btn-arm btn-arm-away" style="font-size:0.8rem;padding:11px 28px;letter-spacing:0.08em;" onclick="createUser()">Create Account</button>
      </div>
      <div id="accountsError" class="login-error-msg d-none" style="margin-top:12px;font-size:0.8rem;"></div>
    </div>

    <!-- PASSWORD TAB -->
    <div id="panelPassword" style="display:none;">
      <div class="mb-4">
        <label class="login-label" style="font-size:0.74rem;margin-bottom:8px;display:block;">Current Password</label>
        <input type="password" id="pwCurrent" class="form-control form-control-dark"
               autocomplete="current-password" style="font-size:0.92rem;padding:11px 14px;">
      </div>
      <div class="mb-4">
        <label class="login-label" style="font-size:0.74rem;margin-bottom:8px;display:block;">New Password</label>
        <div class="position-relative">
          <input type="password" id="pwNew" class="form-control form-control-dark pe-5"
                 autocomplete="new-password" style="font-size:0.92rem;padding:11px 14px;">
          <button type="button" class="login-pw-toggle" onclick="toggleSettingsPw('pwNew')"
                  tabindex="-1" aria-label="Toggle visibility">${_EYE_SVG}</button>
        </div>
      </div>
      <div class="mb-4">
        <label class="login-label" style="font-size:0.74rem;margin-bottom:8px;display:block;">Confirm New Password</label>
        <div class="position-relative">
          <input type="password" id="pwConfirm" class="form-control form-control-dark pe-5"
                 autocomplete="new-password" style="font-size:0.92rem;padding:11px 14px;">
          <button type="button" class="login-pw-toggle" onclick="toggleSettingsPw('pwConfirm')"
                  tabindex="-1" aria-label="Toggle visibility">${_EYE_SVG}</button>
        </div>
      </div>
      <div id="pwError" class="login-error-msg d-none" style="margin-bottom:16px;font-size:0.8rem;"></div>
      <div id="pwSuccess" class="d-none" style="margin-bottom:16px;font-size:0.8rem;font-family:'JetBrains Mono',monospace;color:var(--green);padding:8px 12px;border-left:2px solid var(--green);background:rgba(0,230,118,0.06);"></div>
      <div class="d-flex gap-3 justify-content-end">
        <button class="vox-filter-btn" style="font-size:0.8rem;padding:10px 22px;" onclick="closeSettingsModal()">Cancel</button>
        <button class="btn-arm btn-arm-away" style="font-size:0.8rem;padding:11px 28px;" onclick="submitPasswordChange()">Update Password</button>
      </div>
    </div>

  </div>
</div>`;
    document.body.appendChild(div.firstElementChild);

    document.getElementById('settingsModal').addEventListener('click', function (e) {
        if (e.target === this) closeSettingsModal();
    });

    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') closeSettingsModal();
    });
})();

// ---- Public API ------------------------------------------

function toggleSettingsPw(id) {
    const input = document.getElementById(id);
    if (input) input.type = input.type === 'password' ? 'text' : 'password';
}

function openSettingsModal() {
    document.getElementById('settingsUsername').textContent =
        (localStorage.getItem('username') || '') + '  [ADMIN]';
    switchTab('accounts');
    loadUserList();
    document.getElementById('settingsModal').style.display = 'flex';
}

function closeSettingsModal() {
    document.getElementById('settingsModal').style.display = 'none';
    ['pwCurrent', 'pwNew', 'pwConfirm', 'newUserUsername', 'newUserPassword'].forEach(id => {
        const el = document.getElementById(id);
        if (el) { el.value = ''; el.type = 'password'; }
    });
    ['pwError', 'pwSuccess', 'accountsError'].forEach(id =>
        document.getElementById(id)?.classList.add('d-none'));
}

function switchTab(tab) {
    document.getElementById('panelAccounts').style.display = tab === 'accounts' ? '' : 'none';
    document.getElementById('panelPassword').style.display = tab === 'password'  ? '' : 'none';
    document.getElementById('tabAccounts').classList.toggle('active', tab === 'accounts');
    document.getElementById('tabPassword').classList.toggle('active', tab === 'password');
}

async function loadUserList() {
    const res   = await apiFetch('/api/auth/users');
    const users = res?.ok ? await res.json() : [];
    document.getElementById('userList').innerHTML = users.map(u => `
      <div id="userRow_${u.username}" style="display:flex;align-items:center;justify-content:space-between;
                  padding:14px 18px;border:1px solid var(--border);margin-bottom:8px;background:var(--bg-alt);">
        <div style="display:flex;align-items:center;gap:12px;">
          <span style="font-family:'JetBrains Mono',monospace;font-size:0.92rem;
                       font-weight:600;color:var(--text-1);">${u.username}</span>
          <span class="badge ${u.role === 'ADMIN' ? 'bg-danger' : 'bg-secondary'}"
                style="font-size:0.68rem;padding:4px 9px;letter-spacing:0.06em;">${u.role}</span>
        </div>
        ${u.role !== 'ADMIN'
          ? `<div style="display:flex;gap:8px;">
               <button class="vox-filter-btn" style="font-size:0.74rem;padding:7px 16px;"
                       onclick="startUserEdit('${u.username}')">Edit</button>
               <button class="vox-filter-btn" style="font-size:0.74rem;padding:7px 16px;color:var(--red);"
                       onclick="deleteUser('${u.username}')">Delete</button>
             </div>`
          : '<span style="font-size:0.72rem;letter-spacing:0.1em;color:var(--text-3);">PROTECTED</span>'}
      </div>`).join('') || '<div style="color:var(--text-3);font-size:0.84rem;padding:16px 0;">No users found.</div>';
}

function startUserEdit(username) {
    const row = document.getElementById(`userRow_${username}`);
    if (!row) return;
    row.innerHTML = `
      <div style="width:100%;padding:2px 0;">
        <div style="display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-bottom:8px;">
          <input id="editUsername_${username}" class="form-control form-control-dark"
                 style="flex:1;min-width:180px;font-size:0.9rem;padding:10px 12px;"
                 value="${username}" placeholder="Username">
          <input id="editPassword_${username}" type="password" class="form-control form-control-dark"
                 style="flex:1;min-width:180px;font-size:0.9rem;padding:10px 12px;"
                 placeholder="New password (optional)">
          <button class="btn-arm btn-arm-away" style="font-size:0.76rem;padding:10px 20px;white-space:nowrap;"
                  onclick="saveUserEdit('${username}')">Save</button>
          <button class="vox-filter-btn" style="font-size:0.76rem;padding:10px 16px;"
                  onclick="loadUserList()">Cancel</button>
        </div>
        <div id="editError_${username}" class="login-error-msg d-none"
             style="font-size:0.8rem;margin-top:4px;"></div>
      </div>`;
}

async function saveUserEdit(oldUsername) {
    const newUsername = document.getElementById(`editUsername_${oldUsername}`)?.value.trim();
    const newPassword = document.getElementById(`editPassword_${oldUsername}`)?.value;
    const errEl = document.getElementById(`editError_${oldUsername}`);

    errEl?.classList.add('d-none');

    if (!newUsername) {
        if (errEl) { errEl.textContent = 'Username cannot be empty.'; errEl.classList.remove('d-none'); }
        return;
    }
    if (newPassword && newPassword.length < 8) {
        if (errEl) { errEl.textContent = 'Password must be at least 8 characters.'; errEl.classList.remove('d-none'); }
        return;
    }

    const res = await apiFetch(`/api/auth/users/${oldUsername}`, {
        method: 'PUT',
        body: JSON.stringify({ newUsername, newPassword: newPassword || null })
    });

    if (res?.ok) {
        loadUserList();
    } else {
        const data = await res?.json().catch(() => ({}));
        if (errEl) { errEl.textContent = data?.error || 'Failed to update user.'; errEl.classList.remove('d-none'); }
    }
}

async function createUser() {
    const errEl    = document.getElementById('accountsError');
    const username = document.getElementById('newUserUsername').value.trim();
    const password = document.getElementById('newUserPassword').value;
    errEl.classList.add('d-none');

    if (!username || !password) {
        errEl.textContent = 'Username and password are required.';
        errEl.classList.remove('d-none');
        return;
    }
    if (password.length < 8) {
        errEl.textContent = 'Password must be at least 8 characters.';
        errEl.classList.remove('d-none');
        return;
    }
    const res = await apiFetch('/api/auth/users', {
        method: 'POST',
        body: JSON.stringify({ username, password })
    });
    if (res?.ok) {
        document.getElementById('newUserUsername').value = '';
        document.getElementById('newUserPassword').value = '';
        document.getElementById('newUserPassword').type = 'password';
        loadUserList();
    } else {
        const data = await res?.json().catch(() => ({}));
        errEl.textContent = data?.error || 'Failed to create user.';
        errEl.classList.remove('d-none');
    }
}

async function deleteUser(username) {
    if (!confirm(`Delete account "${username}"? This cannot be undone.`)) return;
    const res = await apiFetch(`/api/auth/users/${username}`, { method: 'DELETE' });
    if (res?.ok) {
        loadUserList();
    } else {
        const data = await res?.json().catch(() => ({}));
        alert(data?.error || 'Failed to delete user.');
    }
}

async function submitPasswordChange() {
    const errEl     = document.getElementById('pwError');
    const successEl = document.getElementById('pwSuccess');
    const current   = document.getElementById('pwCurrent').value;
    const next      = document.getElementById('pwNew').value;
    const confirm   = document.getElementById('pwConfirm').value;

    errEl.classList.add('d-none');
    successEl.classList.add('d-none');

    if (!current || !next || !confirm) {
        errEl.textContent = 'Please fill in all password fields.';
        errEl.classList.remove('d-none'); return;
    }
    if (next !== confirm) {
        errEl.textContent = 'New passwords do not match.';
        errEl.classList.remove('d-none'); return;
    }
    if (next.length < 8) {
        errEl.textContent = 'New password must be at least 8 characters.';
        errEl.classList.remove('d-none'); return;
    }

    const res = await apiFetch('/api/auth/change-password', {
        method: 'POST',
        body: JSON.stringify({ currentPassword: current, newPassword: next })
    });

    if (res?.ok) {
        ['pwCurrent', 'pwNew', 'pwConfirm'].forEach(id => {
            const el = document.getElementById(id);
            if (el) { el.value = ''; el.type = 'password'; }
        });
        successEl.textContent = 'Password updated successfully.';
        successEl.classList.remove('d-none');
        setTimeout(() => successEl.classList.add('d-none'), 4000);
    } else {
        const data = await res?.json().catch(() => ({}));
        errEl.textContent = data?.error || 'Failed to update password.';
        errEl.classList.remove('d-none');
    }
}
