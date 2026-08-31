// Login page scripts

function fillQuickAccount(username, password) {
  document.getElementById('username').value = username;
  document.getElementById('password').value = password;
  showToast(`已自动填充账号: ${username}`, 'info', 2000);
}

async function handleLogin(event) {
  event.preventDefault();

  const usernameInput = document.getElementById('username');
  const passwordInput = document.getElementById('password');
  const submitBtn = document.getElementById('submitBtn');

  const username = usernameInput.value.trim();
  const password = passwordInput.value.trim();

  if (!username || !password) {
    showToast('请输入用户名和密码', 'error');
    return;
  }

  submitBtn.disabled = true;
  submitBtn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i><span>正在验证登录...</span>';

  try {
    const res = await api.post('/api/auth/login', { username, password });

    if (res.success && res.data) {
      showToast('登录成功，正在进入智能体控制台...', 'success', 1500);

      // Save user session details
      localStorage.setItem('token', res.data.token);
      localStorage.setItem('user', JSON.stringify(res.data));

      setTimeout(() => {
        window.location.href = '/index.html';
      }, 800);
    } else {
      showToast(res.message || '登录失败，请检查账号密码', 'error');
      submitBtn.disabled = false;
      submitBtn.innerHTML = '<span>安全登录控制台</span><i class="fa-solid fa-arrow-right"></i>';
    }
  } catch (err) {
    showToast('服务器连接异常，请重试', 'error');
    submitBtn.disabled = false;
    submitBtn.innerHTML = '<span>安全登录控制台</span><i class="fa-solid fa-arrow-right"></i>';
  }
}
