// Dashboard Application Logic with Sidebar Navigation, Charts & Token Analytics

let currentTab = 'overview'; // 'overview' | 'agents' | 'knowledge'
let currentPage = 1;
const pageSize = 6;
let currentCategory = '全部';
let currentKeyword = '';
let currentAgentList = [];
let presetTemplates = [];
let currentViewMode = localStorage.getItem('agentViewMode') || 'card';

let pendingDeleteId = null;
let searchDebounceTimer = null;

// Chart Instances
let tokenTrendChartInstance = null;
let modelDistributionChartInstance = null;
let latencyBarChartInstance = null;

// Initialize on DOM load
document.addEventListener('DOMContentLoaded', () => {
  initUserProfile();
  initSidebarListeners();
  loadDashboardStats();
  loadPresetTemplates();
  initViewMode();
  loadAgentList();
  initOverviewCharts();
});

// ========================================================
// 1. Sidebar Tab Switching & Event Binding
// ========================================================
function initSidebarListeners() {
  const btnOverview = document.getElementById('navItemOverview');
  const btnAgents = document.getElementById('navItemAgents');
  const btnKnowledge = document.getElementById('navItemKnowledge');

  if (btnOverview) {
    btnOverview.addEventListener('click', (e) => {
      e.preventDefault();
      switchNavTab('overview');
    });
  }
  if (btnAgents) {
    btnAgents.addEventListener('click', (e) => {
      e.preventDefault();
      switchNavTab('agents');
    });
  }
  if (btnKnowledge) {
    btnKnowledge.addEventListener('click', (e) => {
      e.preventDefault();
      switchNavTab('knowledge');
    });
  }
}

window.switchNavTab = function(tabName, elem) {
  currentTab = tabName;

  // 1. Update Sidebar Active Style
  const itemOverview = document.getElementById('navItemOverview');
  const itemAgents = document.getElementById('navItemAgents');
  const itemKnowledge = document.getElementById('navItemKnowledge');

  if (itemOverview) itemOverview.classList.toggle('active', tabName === 'overview');
  if (itemAgents) itemAgents.classList.toggle('active', tabName === 'agents');
  if (itemKnowledge) itemKnowledge.classList.toggle('active', tabName === 'knowledge');

  // 2. Hide all subviews and show targeted subview
  const viewOverview = document.getElementById('viewOverview');
  const viewAgents = document.getElementById('viewAgents');
  const viewKnowledge = document.getElementById('viewKnowledge');

  if (viewOverview) viewOverview.classList.toggle('active', tabName === 'overview');
  if (viewAgents) viewAgents.classList.toggle('active', tabName === 'agents');
  if (viewKnowledge) viewKnowledge.classList.toggle('active', tabName === 'knowledge');

  // 3. Update topbar title
  const pageTitleElem = document.getElementById('topbarPageTitle');
  if (pageTitleElem) {
    if (tabName === 'overview') pageTitleElem.textContent = '概览仪表盘 (Overview & Analytics)';
    if (tabName === 'agents') pageTitleElem.textContent = 'Agents 智能体资产管理';
    if (tabName === 'knowledge') pageTitleElem.textContent = '企业私有知识库 (RAG)';
  }

  // 4. Safely render charts if overview
  if (tabName === 'overview') {
    setTimeout(() => {
      try {
        renderOverviewCharts();
      } catch (err) {
        console.warn('Chart render exception:', err);
      }
    }, 50);
  }
};

window.changeTimeRange = function(range, btn) {
  document.querySelectorAll('.btn-time-range').forEach(b => b.classList.remove('active'));
  if (btn) btn.classList.add('active');
  
  const factor = range === 'today' ? 0.18 : (range === '30days' ? 4.2 : 1);
  const totalElem = document.getElementById('valTotalTokens');
  const promptElem = document.getElementById('valPromptTokens');
  const compElem = document.getElementById('valCompletionTokens');
  const costElem = document.getElementById('valEstCost');

  if (totalElem) totalElem.textContent = `${(24.85 * factor).toFixed(2)}M`;
  if (promptElem) promptElem.textContent = `${(14.22 * factor).toFixed(2)}M`;
  if (compElem) compElem.textContent = `${(10.63 * factor).toFixed(2)}M`;
  if (costElem) costElem.textContent = `$${(38.64 * factor).toFixed(2)}`;
  
  showToast(`已切换数据时间窗口为 [${btn ? btn.textContent : range}]`, 'info', 1500);
  renderOverviewCharts();
};

// ========================================================
// 2. Token Overview Charts (Chart.js with Fallback Protection)
// ========================================================
function initOverviewCharts() {
  try {
    renderOverviewCharts();
    renderOverviewRanking();
  } catch (e) {
    console.warn('Failed to initialize charts', e);
  }
}

function renderOverviewCharts() {
  if (typeof Chart === 'undefined') {
    console.info('Chart.js not loaded, skipping canvas rendering');
    return;
  }

  const isDark = document.documentElement.getAttribute('data-theme') !== 'light';
  const textColor = isDark ? '#94a3b8' : '#64748b';
  const gridColor = isDark ? 'rgba(255, 255, 255, 0.06)' : 'rgba(0, 0, 0, 0.06)';

  try {
    // Chart 1: Token Usage Trend (Line Chart)
    const ctxTrend = document.getElementById('tokenTrendChart');
    if (ctxTrend) {
      if (tokenTrendChartInstance) tokenTrendChartInstance.destroy();
      
      tokenTrendChartInstance = new Chart(ctxTrend, {
        type: 'line',
        data: {
          labels: ['8/22', '8/23', '8/24', '8/25', '8/26', '8/27', '8/28'],
          datasets: [
            {
              label: 'Prompt Tokens (M)',
              data: [1.8, 2.1, 1.9, 2.4, 2.8, 2.3, 2.9],
              borderColor: '#3b82f6',
              backgroundColor: 'rgba(59, 130, 246, 0.15)',
              tension: 0.35,
              fill: true,
              pointRadius: 4,
              pointHoverRadius: 6
            },
            {
              label: 'Completion Tokens (M)',
              data: [1.2, 1.5, 1.4, 1.9, 2.2, 1.7, 2.1],
              borderColor: '#10b981',
              backgroundColor: 'rgba(16, 185, 129, 0.1)',
              tension: 0.35,
              fill: true,
              pointRadius: 4,
              pointHoverRadius: 6
            }
          ]
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          interaction: { mode: 'index', intersect: false },
          plugins: {
            legend: {
              position: 'top',
              labels: { color: textColor, font: { size: 11 } }
            }
          },
          scales: {
            x: {
              grid: { color: gridColor },
              ticks: { color: textColor, font: { size: 11 } }
            },
            y: {
              grid: { color: gridColor },
              ticks: { color: textColor, font: { size: 11 } }
            }
          }
        }
      });
    }

    // Chart 2: Model Distribution Donut
    const ctxDonut = document.getElementById('modelDistributionChart');
    if (ctxDonut) {
      if (modelDistributionChartInstance) modelDistributionChartInstance.destroy();

      modelDistributionChartInstance = new Chart(ctxDonut, {
        type: 'doughnut',
        data: {
          labels: ['GPT-4o', 'DeepSeek-V4', 'Claude-3.5', 'Qwen-Max', '其他模型'],
          datasets: [{
            data: [42, 28, 16, 10, 4],
            backgroundColor: [
              '#3b82f6',
              '#8b5cf6',
              '#ec4899',
              '#f59e0b',
              '#64748b'
            ],
            borderWidth: 0,
            hoverOffset: 6
          }]
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          plugins: {
            legend: {
              position: 'right',
              labels: { color: textColor, font: { size: 11 }, padding: 14 }
            }
          },
          cutout: '70%'
        }
      });
    }

    // Chart 3: Latency Comparison Bar
    const ctxLatency = document.getElementById('latencyBarChart');
    if (ctxLatency) {
      if (latencyBarChartInstance) latencyBarChartInstance.destroy();

      latencyBarChartInstance = new Chart(ctxLatency, {
        type: 'bar',
        data: {
          labels: ['代码研发', '运维架构', '知识库客服', '产品策划', '数据分析', '内容创作'],
          datasets: [
            {
              label: 'P95 响应耗时 (ms)',
              data: [380, 490, 190, 360, 270, 310],
              backgroundColor: 'rgba(59, 130, 246, 0.8)',
              borderRadius: 6
            },
            {
              label: 'P99 响应耗时 (ms)',
              data: [450, 580, 240, 420, 340, 390],
              backgroundColor: 'rgba(139, 92, 246, 0.8)',
              borderRadius: 6
            }
          ]
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          plugins: {
            legend: {
              position: 'top',
              labels: { color: textColor, font: { size: 11 } }
            }
          },
          scales: {
            x: {
              grid: { display: false },
              ticks: { color: textColor, font: { size: 10.5 } }
            },
            y: {
              grid: { color: gridColor },
              ticks: { color: textColor, font: { size: 11 } }
            }
          }
        }
      });
    }
  } catch (err) {
    console.error('Error instantiating charts:', err);
  }
}

function renderOverviewRanking() {
  const container = document.getElementById('overviewRankingList');
  if (!container) return;

  const topAgents = [
    { rank: 1, avatar: '🤖', name: '企业知识库客服助理', model: 'gpt-4o-mini', calls: '3,420 次', tokens: '6.84M Tokens' },
    { rank: 2, avatar: '🌐', name: '专业学术与商务多语翻译官', model: 'gpt-4o-mini', calls: '2,150 次', tokens: '4.30M Tokens' },
    { rank: 3, avatar: '🚀', name: '分布式系统架构专家', model: 'gpt-4o', calls: '1,582 次', tokens: '3.95M Tokens' },
    { rank: 4, avatar: '✨', name: 'AI 创意营销策划师', model: 'gpt-4o', calls: '1,280 次', tokens: '3.20M Tokens' }
  ];

  let html = '';
  topAgents.forEach(a => {
    const badgeClass = a.rank <= 3 ? `top-${a.rank}` : '';
    html += `
      <div class="ranking-item-row">
        <div class="ranking-meta-left">
          <div class="ranking-badge-idx ${badgeClass}">${a.rank}</div>
          <div class="ranking-agent-avatar">${a.avatar}</div>
          <div>
            <div class="ranking-name">${escapeHtml(a.name)}</div>
            <div class="ranking-model">${escapeHtml(a.model)}</div>
          </div>
        </div>
        <div class="ranking-stats-right">
          <div class="ranking-calls-num">${a.calls}</div>
          <div class="ranking-tokens-num">${a.tokens}</div>
        </div>
      </div>
    `;
  });
  container.innerHTML = html;
}

// ========================================================
// 3. User Profile
// ========================================================
function initUserProfile() {
  const userJson = localStorage.getItem('user');
  if (userJson) {
    try {
      const user = JSON.parse(userJson);
      const displayName = user.nickname || user.username || '管理员';
      const avatarUrl = user.avatar || `https://api.dicebear.com/7.x/bottts/svg?seed=${user.username || 'admin'}`;

      const topNameElem = document.getElementById('topbarUserName');
      const topAvatarElem = document.getElementById('topbarUserAvatar');
      const sideNameElem = document.getElementById('userName');

      if (topNameElem) topNameElem.textContent = displayName;
      if (topAvatarElem) topAvatarElem.src = avatarUrl;
      if (sideNameElem) sideNameElem.textContent = displayName;
    } catch (e) {
      console.warn('Failed to parse user session', e);
    }
  }
}

window.handleLogout = async function() {
  await api.post('/api/auth/logout');
  localStorage.removeItem('token');
  localStorage.removeItem('user');
  showToast('已安全登出控制台', 'info');
  setTimeout(() => {
    window.location.href = '/login.html';
  }, 400);
};

// ========================================================
// 4. View Mode (Card / List)
// ========================================================
function initViewMode() {
  setViewMode(currentViewMode, false);
}

window.setViewMode = function(mode, shouldReload = true) {
  currentViewMode = mode;
  localStorage.setItem('agentViewMode', mode);

  const btnCard = document.getElementById('btnViewCard');
  const btnList = document.getElementById('btnViewList');
  if (btnCard && btnList) {
    if (mode === 'card') {
      btnCard.classList.add('active');
      btnList.classList.remove('active');
    } else {
      btnList.classList.add('active');
      btnCard.classList.remove('active');
    }
  }

  if (shouldReload) {
    renderContent();
  }
};

// ========================================================
// 5. Stats & Template Loading
// ========================================================
async function loadDashboardStats() {
  const res = await api.get('/api/dashboard/stats');
  if (res.success && res.data) {
    const d = res.data;
    const totalElem = document.getElementById('statTotalAgents');
    const runElem = document.getElementById('statRunningAgents');
    const callsElem = document.getElementById('statTotalCalls');
    const latElem = document.getElementById('statAvgLatency');
    const sidebarCount = document.getElementById('sidebarAgentCount');

    if (totalElem) totalElem.textContent = d.totalAgents || 0;
    if (runElem) runElem.textContent = d.runningAgents || 0;
    if (callsElem) callsElem.textContent = Number(d.totalCalls || 0).toLocaleString();
    if (latElem) latElem.textContent = `${d.avgResponseTimeMs || 0}ms`;
    if (sidebarCount) sidebarCount.textContent = d.totalAgents || 0;
  }
}

async function loadPresetTemplates() {
  const res = await api.get('/api/agents/templates');
  if (res.success && res.data) {
    presetTemplates = res.data;
    const select = document.getElementById('presetTemplateSelect');
    if (select) {
      select.innerHTML = '<option value="">-- 选择预设专家智能体模版 (一键填充) --</option>';
      presetTemplates.forEach((t, idx) => {
        const opt = document.createElement('option');
        opt.value = idx;
        opt.textContent = `${t.avatar || '🤖'} ${t.name} (${t.category})`;
        select.appendChild(opt);
      });
    }
  }
}

window.applyPresetTemplate = function(indexStr) {
  if (indexStr === '') return;
  const t = presetTemplates[parseInt(indexStr)];
  if (!t) return;

  document.getElementById('formName').value = t.name || '';
  document.getElementById('formCode').value = 'agent_' + (t.name || 'bot').toLowerCase().replace(/[^a-zA-Z0-9]/g, '_');
  document.getElementById('formCategory').value = t.category || '通用智能';
  document.getElementById('formModel').value = t.modelName || 'gpt-4o';
  document.getElementById('formSystemPrompt').value = t.systemPrompt || '';
  document.getElementById('formDescription').value = t.description || '';
  document.getElementById('formTags').value = (t.tags || []).join(', ');
  
  if (t.temperature) {
    document.getElementById('formTemperature').value = t.temperature;
    document.getElementById('tempValueDisplay').textContent = t.temperature;
  }
  if (t.avatar) {
    selectEmoji(t.avatar);
  }
  showToast(`已成功载入模版: ${t.name}`, 'info', 2000);
};

// ========================================================
// 6. Agent List & Filter
// ========================================================
window.debounceSearch = function() {
  clearTimeout(searchDebounceTimer);
  searchDebounceTimer = setTimeout(() => {
    currentKeyword = document.getElementById('searchInput').value;
    currentPage = 1;
    loadAgentList();
  }, 300);
};

window.setCategory = function(cat, btn) {
  currentCategory = cat;
  document.querySelectorAll('#categoryPills .btn-filter-pill').forEach(b => b.classList.remove('active'));
  if (btn) btn.classList.add('active');
  currentPage = 1;
  loadAgentList();
};

window.refreshDashboard = function() {
  loadDashboardStats();
  loadAgentList();
  showToast('控制台数据已刷新', 'info', 1500);
};

async function loadAgentList() {
  const filterElem = document.getElementById('statusFilter');
  const status = filterElem ? filterElem.value : '';
  const params = {
    keyword: currentKeyword,
    category: currentCategory === '全部' ? '' : currentCategory,
    status: status || '',
    page: currentPage,
    size: pageSize
  };

  const container = document.getElementById('agentContentArea');
  if (container) {
    container.innerHTML = `
      <div class="empty-state">
        <i class="fa-solid fa-spinner fa-spin"></i>
        <h4>正在检索智能体集群...</h4>
      </div>
    `;
  }

  const res = await api.get('/api/agents', params);
  if (res.success && res.data) {
    const pageResult = res.data;
    currentAgentList = pageResult.records || [];
    renderContent();
    renderPagination(pageResult);
  } else {
    if (container) {
      container.innerHTML = `
        <div class="empty-state">
          <i class="fa-solid fa-triangle-exclamation" style="color: var(--accent-rose);"></i>
          <h4>数据加载失败: ${res.message || '未知错误'}</h4>
        </div>
      `;
    }
  }
}

function renderContent() {
  const container = document.getElementById('agentContentArea');
  if (!container) return;

  if (!currentAgentList || currentAgentList.length === 0) {
    container.innerHTML = `
      <div class="empty-state">
        <i class="fa-solid fa-robot"></i>
        <h4>未找到符合条件的智能体</h4>
        <p style="font-size: 13px; margin-top: 6px;">您可以更换搜索词或点击右上角「注册新智能体」</p>
      </div>
    `;
    return;
  }

  if (currentViewMode === 'card') {
    renderCardView(container);
  } else {
    renderTableView(container);
  }
}

// 7. Card View Renderer
function renderCardView(container) {
  let html = '<div class="agent-grid">';
  currentAgentList.forEach(a => {
    let statusClass = 'badge-success';
    let statusLabel = '运行中';
    if (a.status === 'IDLE') {
      statusClass = 'badge-warning';
      statusLabel = '空闲中';
    } else if (a.status === 'DISABLED') {
      statusClass = 'badge-danger';
      statusLabel = '已停用';
    }

    const tagsHtml = (a.tags || []).map(t => `<span class="tag-item">#${escapeHtml(t)}</span>`).join('');
    const promptShort = escapeHtml(a.systemPrompt || '暂未设定 System Prompt');
    const desc = escapeHtml(a.description || '暂无描述信息');

    html += `
      <div class="agent-card">
        <div>
          <div class="agent-card-header">
            <div class="agent-meta-left">
              <div class="agent-avatar-badge">${a.avatar || '🤖'}</div>
              <div class="agent-title-box">
                <h3>${escapeHtml(a.name)}</h3>
                <div class="agent-code-tag">${escapeHtml(a.code || a.id)}</div>
              </div>
            </div>
            <div class="badge-status ${statusClass}">
              <span class="status-dot"></span>
              <span>${statusLabel}</span>
            </div>
          </div>

          <div class="agent-desc" title="${desc}">${desc}</div>

          <div class="agent-specs">
            <span class="spec-badge spec-model">
              <i class="fa-solid fa-microchip"></i>
              <span>${escapeHtml(a.modelName || 'gpt-4o')}</span>
            </span>
            <span class="spec-badge">
              <i class="fa-solid fa-temperature-half"></i>
              <span>T:${a.temperature != null ? a.temperature : 0.7}</span>
            </span>
            <span class="spec-badge">
              <i class="fa-solid fa-tag"></i>
              <span>${escapeHtml(a.category || '通用')}</span>
            </span>
          </div>

          <div class="prompt-preview-box" title="System Prompt: ${promptShort}">
            <i class="fa-solid fa-terminal" style="color: var(--accent-blue); margin-right: 6px;"></i>${promptShort}
          </div>

          <div class="agent-tags">
            ${tagsHtml}
          </div>
        </div>

        <div class="agent-footer">
          <div class="agent-stats-metric">
            <span title="调用总量"><i class="fa-regular fa-comment-dots"></i> ${Number(a.callCount || 0).toLocaleString()}</span>
            <span title="平均响应耗时"><i class="fa-regular fa-clock"></i> ${a.avgResponseTimeMs || 300}ms</span>
          </div>

          <div class="agent-actions">
            <button class="btn-card-action btn-chat-primary" onclick="navigateToDebug('${a.id}')" title="进入智能体编排与调试页面">
              <i class="fa-solid fa-sliders"></i>
              <span>调试</span>
            </button>
            <button class="btn-card-action btn-action-icon" onclick="openEditModal('${a.id}')" title="编辑智能体参数">
              <i class="fa-regular fa-pen-to-square"></i>
            </button>
            <button class="btn-card-action btn-action-icon" onclick="toggleAgentStatus('${a.id}')" title="切换启停状态">
              <i class="fa-solid fa-power-off"></i>
            </button>
            <button class="btn-card-action btn-action-icon btn-action-danger" onclick="openDeleteModal('${a.id}', '${escapeHtml(a.name)}')" title="删除智能体">
              <i class="fa-regular fa-trash-can"></i>
            </button>
          </div>
        </div>
      </div>
    `;
  });
  html += '</div>';
  container.innerHTML = html;
}

// 8. Table View Renderer
function renderTableView(container) {
  let html = `
    <div class="table-view-card">
      <table class="agent-table">
        <thead>
          <tr>
            <th>智能体</th>
            <th>业务分类</th>
            <th>调度模型</th>
            <th>系统提示词 (Prompt)</th>
            <th>调用统计</th>
            <th>运行状态</th>
            <th style="text-align: right;">操作管理</th>
          </tr>
        </thead>
        <tbody>
  `;

  currentAgentList.forEach(a => {
    let statusClass = 'badge-success';
    let statusLabel = '运行中';
    if (a.status === 'IDLE') {
      statusClass = 'badge-warning';
      statusLabel = '空闲中';
    } else if (a.status === 'DISABLED') {
      statusClass = 'badge-danger';
      statusLabel = '已停用';
    }

    const promptShort = escapeHtml(a.systemPrompt || '暂无设定');

    html += `
      <tr>
        <td>
          <div class="table-agent-meta">
            <div class="table-agent-avatar">${a.avatar || '🤖'}</div>
            <div>
              <div class="table-agent-title">${escapeHtml(a.name)}</div>
              <div class="table-agent-code">${escapeHtml(a.code || a.id)}</div>
            </div>
          </div>
        </td>
        <td>
          <span class="spec-badge"><i class="fa-solid fa-tag"></i> ${escapeHtml(a.category || '通用')}</span>
        </td>
        <td>
          <div style="display: flex; gap: 4px; flex-direction: column;">
            <span class="spec-badge spec-model" style="display: inline-flex; width: fit-content;">${escapeHtml(a.modelName || 'gpt-4o')}</span>
            <span style="font-size: 11px; color: var(--text-muted);">Temp: ${a.temperature != null ? a.temperature : 0.7}</span>
          </div>
        </td>
        <td>
          <div class="table-prompt-cell" title="${promptShort}">
            ${promptShort}
          </div>
        </td>
        <td>
          <div style="font-size: 12px;">
            <div><i class="fa-regular fa-comment-dots" style="color: var(--accent-blue);"></i> ${Number(a.callCount || 0).toLocaleString()} 次</div>
            <div style="font-size: 11px; color: var(--text-muted);"><i class="fa-regular fa-clock"></i> ${a.avgResponseTimeMs || 300}ms</div>
          </div>
        </td>
        <td>
          <div class="badge-status ${statusClass}">
            <span class="status-dot"></span>
            <span>${statusLabel}</span>
          </div>
        </td>
        <td style="text-align: right;">
          <div class="agent-actions" style="justify-content: flex-end;">
            <button class="btn-card-action btn-chat-primary" onclick="navigateToDebug('${a.id}')" title="进入智能体编排与调试页面">
              <i class="fa-solid fa-sliders"></i>
              <span>调试</span>
            </button>
            <button class="btn-card-action btn-action-icon" onclick="openEditModal('${a.id}')" title="编辑智能体参数">
              <i class="fa-regular fa-pen-to-square"></i>
            </button>
            <button class="btn-card-action btn-action-icon" onclick="toggleAgentStatus('${a.id}')" title="切换启停状态">
              <i class="fa-solid fa-power-off"></i>
            </button>
            <button class="btn-card-action btn-action-icon btn-action-danger" onclick="openDeleteModal('${a.id}', '${escapeHtml(a.name)}')" title="删除智能体">
              <i class="fa-regular fa-trash-can"></i>
            </button>
          </div>
        </td>
      </tr>
    `;
  });

  html += `
        </tbody>
      </table>
    </div>
  `;

  container.innerHTML = html;
}

// 9. Navigation to Dify-Style Debug Page
window.navigateToDebug = function(agentId) {
  window.location.href = `/debug.html?id=${agentId}`;
};

// 10. Pagination
function renderPagination(pageResult) {
  const summary = document.getElementById('pageSummary');
  if (summary) {
    summary.textContent = `共 ${pageResult.total} 个智能体 · 第 ${pageResult.page} / ${Math.max(1, pageResult.totalPages)} 页`;
  }

  const btnPrev = document.getElementById('btnPrevPage');
  const btnNext = document.getElementById('btnNextPage');
  if (btnPrev) btnPrev.disabled = pageResult.page <= 1;
  if (btnNext) btnNext.disabled = pageResult.page >= pageResult.totalPages || pageResult.totalPages === 0;

  const numbersContainer = document.getElementById('pageNumbers');
  if (numbersContainer) {
    numbersContainer.innerHTML = '';
    const totalPages = Math.max(1, pageResult.totalPages);
    for (let i = 1; i <= totalPages; i++) {
      const btn = document.createElement('button');
      btn.className = `btn-page ${i === pageResult.page ? 'active' : ''}`;
      btn.textContent = i;
      btn.onclick = () => {
        currentPage = i;
        loadAgentList();
      };
      numbersContainer.appendChild(btn);
    }
  }
}

window.changePage = function(delta) {
  currentPage = Math.max(1, currentPage + delta);
  loadAgentList();
};

// 11. Create / Edit Agent Modal
window.openCreateModal = function() {
  document.getElementById('modalTitle').textContent = '注册新智能体';
  document.getElementById('formAgentId').value = '';
  document.getElementById('agentForm').reset();
  document.getElementById('templatePresetRow').style.display = 'block';
  selectEmoji('🤖');
  updateTempValue(0.7);
  document.getElementById('agentModal').classList.add('open');
};

window.openEditModal = function(agentId) {
  const agent = currentAgentList.find(a => a.id === agentId);
  if (!agent) return;

  document.getElementById('modalTitle').textContent = `编辑智能体: ${agent.name}`;
  document.getElementById('formAgentId').value = agent.id;
  document.getElementById('formName').value = agent.name || '';
  document.getElementById('formCode').value = agent.code || '';
  document.getElementById('formCategory').value = agent.category || '通用智能';
  document.getElementById('formModel').value = agent.modelName || 'gpt-4o';
  document.getElementById('formStatus').value = agent.status || 'RUNNING';
  document.getElementById('formSystemPrompt').value = agent.systemPrompt || '';
  document.getElementById('formDescription').value = agent.description || '';
  document.getElementById('formTags').value = (agent.tags || []).join(', ');
  
  selectEmoji(agent.avatar || '🤖');
  updateTempValue(agent.temperature != null ? agent.temperature : 0.7);

  document.getElementById('templatePresetRow').style.display = 'none';
  document.getElementById('agentModal').classList.add('open');
};

window.closeAgentModal = function() {
  document.getElementById('agentModal').classList.remove('open');
};

window.selectEmoji = function(emoji, btn) {
  document.getElementById('formAvatar').value = emoji;
  document.querySelectorAll('#emojiSelector .emoji-btn').forEach(b => {
    b.classList.remove('active');
    if (b.textContent.trim() === emoji) {
      b.classList.add('active');
    }
  });
  if (btn) btn.classList.add('active');
};

window.updateTempValue = function(val) {
  document.getElementById('formTemperature').value = val;
  document.getElementById('tempValueDisplay').textContent = val;
};

window.handleSaveAgent = async function(e) {
  e.preventDefault();

  const id = document.getElementById('formAgentId').value;
  const tagsStr = document.getElementById('formTags').value;
  const tags = tagsStr ? tagsStr.split(/[,，]/).map(t => t.trim()).filter(Boolean) : [];

  const payload = {
    name: document.getElementById('formName').value.trim(),
    code: document.getElementById('formCode').value.trim(),
    avatar: document.getElementById('formAvatar').value || '🤖',
    category: document.getElementById('formCategory').value,
    modelName: document.getElementById('formModel').value,
    temperature: parseFloat(document.getElementById('formTemperature').value),
    status: document.getElementById('formStatus').value,
    systemPrompt: document.getElementById('formSystemPrompt').value.trim(),
    description: document.getElementById('formDescription').value.trim(),
    tags: tags
  };

  const btnSave = document.getElementById('btnSaveAgent');
  btnSave.disabled = true;
  btnSave.textContent = '保存中...';

  try {
    let res;
    if (id) {
      res = await api.put(`/api/agents/${id}`, payload);
    } else {
      res = await api.post('/api/agents', payload);
    }

    if (res.success) {
      showToast(res.message || '保存成功', 'success');
      closeAgentModal();
      loadAgentList();
      loadDashboardStats();
    } else {
      showToast(res.message || '保存失败', 'error');
    }
  } catch (err) {
    showToast('请求异常: ' + err.message, 'error');
  } finally {
    btnSave.disabled = false;
    btnSave.textContent = '保存并生效';
  }
};

// 12. Toggle Status
window.toggleAgentStatus = async function(id) {
  const res = await api.post(`/api/agents/${id}/toggle-status`);
  if (res.success) {
    showToast(`智能体已切换为 [${res.data.status === 'RUNNING' ? '运行中' : '已停用'}]`, 'success', 2000);
    loadAgentList();
    loadDashboardStats();
  } else {
    showToast(res.message || '状态切换失败', 'error');
  }
};

// 13. Delete Agent Modal
window.openDeleteModal = function(id, name) {
  pendingDeleteId = id;
  document.getElementById('deleteAgentName').textContent = name;
  document.getElementById('deleteModal').classList.add('open');
};

window.closeDeleteModal = function() {
  pendingDeleteId = null;
  document.getElementById('deleteModal').classList.remove('open');
};

window.confirmDeleteAgent = async function() {
  if (!pendingDeleteId) return;

  const btn = document.getElementById('btnConfirmDelete');
  btn.disabled = true;
  btn.textContent = '正在删除...';

  try {
    const res = await api.delete(`/api/agents/${pendingDeleteId}`);
    if (res.success) {
      showToast('智能体已成功删除', 'success');
      closeDeleteModal();
      loadAgentList();
      loadDashboardStats();
    } else {
      showToast(res.message || '删除失败', 'error');
    }
  } catch (err) {
    showToast('删除异常', 'error');
  } finally {
    btn.disabled = false;
    btn.textContent = '确认删除';
  }
};

// Helpers
function escapeHtml(str) {
  if (!str) return '';
  return str
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}
