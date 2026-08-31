// Unified API Client, Toast Notification & Theme System

function initTheme() {
  const savedTheme = localStorage.getItem('theme') || 'dark';
  document.documentElement.setAttribute('data-theme', savedTheme);
  updateThemeIcon(savedTheme);
}

function toggleTheme() {
  const current = document.documentElement.getAttribute('data-theme') || 'dark';
  const nextTheme = current === 'dark' ? 'light' : 'dark';
  document.documentElement.setAttribute('data-theme', nextTheme);
  localStorage.setItem('theme', nextTheme);
  updateThemeIcon(nextTheme);
  showToast(`已切换至 ${nextTheme === 'dark' ? '🌙 暗黑模式' : '☀️ 明亮模式'}`, 'info', 1500);
}

function updateThemeIcon(theme) {
  const icon = document.getElementById('themeIcon');
  if (icon) {
    if (theme === 'light') {
      icon.className = 'fa-solid fa-sun';
      icon.style.color = '#f59e0b';
    } else {
      icon.className = 'fa-solid fa-moon';
      icon.style.color = '#9ca3af';
    }
  }
}

// Auto init theme on script load
document.addEventListener('DOMContentLoaded', () => {
  initTheme();
});

function showToast(message, type = 'info', duration = 3500) {
  const container = document.getElementById('toastContainer');
  if (!container) return;

  const toast = document.createElement('div');
  toast.className = `toast toast-${type}`;

  let iconHtml = '<i class="fa-solid fa-circle-info toast-icon" style="color: var(--accent-blue);"></i>';
  if (type === 'success') {
    iconHtml = '<i class="fa-solid fa-circle-check toast-icon" style="color: var(--accent-emerald);"></i>';
  } else if (type === 'error') {
    iconHtml = '<i class="fa-solid fa-circle-exclamation toast-icon" style="color: var(--accent-rose);"></i>';
  }

  toast.innerHTML = `
    ${iconHtml}
    <div class="toast-message">${message}</div>
  `;

  container.appendChild(toast);

  requestAnimationFrame(() => {
    toast.classList.add('show');
  });

  setTimeout(() => {
    toast.classList.remove('show');
    setTimeout(() => {
      if (toast.parentNode) {
        toast.parentNode.removeChild(toast);
      }
    }, 400);
  }, duration);
}

const api = {
  async get(url, params = {}) {
    const query = new URLSearchParams(params).toString();
    const fullUrl = query ? `${url}?${query}` : url;
    try {
      const res = await fetch(fullUrl, {
        method: 'GET',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${localStorage.getItem('token') || ''}`
        }
      });
      return await res.json();
    } catch (err) {
      console.error('API GET Error:', err);
      return { success: false, message: '网络请求失败: ' + err.message };
    }
  },

  async post(url, body = {}) {
    try {
      const res = await fetch(url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${localStorage.getItem('token') || ''}`
        },
        body: JSON.stringify(body)
      });
      return await res.json();
    } catch (err) {
      console.error('API POST Error:', err);
      return { success: false, message: '网络请求失败: ' + err.message };
    }
  },

  async put(url, body = {}) {
    try {
      const res = await fetch(url, {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${localStorage.getItem('token') || ''}`
        },
        body: JSON.stringify(body)
      });
      return await res.json();
    } catch (err) {
      console.error('API PUT Error:', err);
      return { success: false, message: '网络请求失败: ' + err.message };
    }
  },

  async delete(url) {
    try {
      const res = await fetch(url, {
        method: 'DELETE',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${localStorage.getItem('token') || ''}`
        }
      });
      return await res.json();
    } catch (err) {
      console.error('API DELETE Error:', err);
      return { success: false, message: '网络请求失败: ' + err.message };
    }
  }
};
