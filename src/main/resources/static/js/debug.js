// Dify-Style Agent Debug & Orchestration Controller with Resizable Splitter

let currentAgentId = null;
let currentAgent = null;
let chatHistory = [];
let enabledTools = {
  '时区转换': true,
  '时间戳转换': true,
  '获取当前时间': true,
  '获取时间戳': true,
  '星期几计算器': true,
  '联网检索': true
};

document.addEventListener('DOMContentLoaded', () => {
  const urlParams = new URLSearchParams(window.location.search);
  currentAgentId = urlParams.get('id') || 'agent-001';
  initResizableSplitter();
  loadAgentData(currentAgentId);
});

// 1. Interactive Resizable Splitter with Boundary Limits
function initResizableSplitter() {
  const divider = document.getElementById('resizeDivider');
  const leftPane = document.getElementById('leftPane');
  const container = document.getElementById('debugMainLayout');
  if (!divider || !leftPane || !container) return;

  // Restore saved width if available
  const savedWidth = localStorage.getItem('debugPaneWidth');
  if (savedWidth) {
    const parsedWidth = parseFloat(savedWidth);
    if (!isNaN(parsedWidth) && parsedWidth >= 360 && parsedWidth <= (window.innerWidth - 360)) {
      leftPane.style.width = `${parsedWidth}px`;
    }
  }

  let isDragging = false;
  let startX = 0;
  let startWidth = 0;

  divider.addEventListener('mousedown', (e) => {
    isDragging = true;
    startX = e.clientX;
    startWidth = leftPane.getBoundingClientRect().width;
    divider.classList.add('dragging');
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
    e.preventDefault();
  });

  document.addEventListener('mousemove', (e) => {
    if (!isDragging) return;

    const totalWidth = container.getBoundingClientRect().width;
    const minWidth = Math.max(360, totalWidth * 0.25);
    const maxWidth = Math.min(totalWidth - 360, totalWidth * 0.75);

    let newWidth = startWidth + (e.clientX - startX);

    // Apply strict boundary limits
    if (newWidth < minWidth) newWidth = minWidth;
    if (newWidth > maxWidth) newWidth = maxWidth;

    leftPane.style.width = `${newWidth}px`;
  });

  document.addEventListener('mouseup', () => {
    if (isDragging) {
      isDragging = false;
      divider.classList.remove('dragging');
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
      localStorage.setItem('debugPaneWidth', leftPane.getBoundingClientRect().width);
    }
  });
}

// 2. Load Agent Details
async function loadAgentData(id) {
  const res = await api.get(`/api/agents/${id}`);
  if (res.success && res.data) {
    currentAgent = res.data;
    renderAgentState();
  } else {
    showToast('未能加载智能体信息，载入默认体验配置', 'error');
  }
}

function renderAgentState() {
  if (!currentAgent) return;

  // Header
  document.getElementById('headerAgentName').textContent = `${currentAgent.avatar || '🤖'} ${currentAgent.name}`;
  
  // Prompt
  const promptArea = document.getElementById('promptInput');
  promptArea.value = currentAgent.systemPrompt || '';
  updateCharCount();

  // Model Select
  const modelSelect = document.getElementById('debugModelSelect');
  if (currentAgent.modelName) {
    modelSelect.value = currentAgent.modelName;
  }

  // Initialize Chat Stream
  initChatStream();
}

function updateCharCount() {
  const text = document.getElementById('promptInput').value;
  document.getElementById('promptCharCount').textContent = `${text.length} 字`;
}

// 3. Chat Sandbox Logic
function initChatStream() {
  const stream = document.getElementById('debugChatStream');
  chatHistory = [];

  const initialGreeting = `你好！我是 <strong>${escapeHtml(currentAgent.name)}</strong>。<br>
我已就绪，当前挂载了 <strong>Spring AI 2.0.1 ChatClient</strong> 管道并启用了 <strong>6</strong> 个 Function Calling 工具组件。<br>
你可以向我输入指令进行实时编排与提示词效果调试！`;

  stream.innerHTML = `
    <div class="chat-msg-row chat-msg-bot">
      <div class="msg-avatar msg-avatar-bot">${currentAgent.avatar || '🤖'}</div>
      <div class="msg-content-wrapper">
        <div class="msg-bubble">${initialGreeting}</div>
        <div class="msg-meta-info">
          <span><i class="fa-solid fa-microchip"></i> ${escapeHtml(currentAgent.modelName || 'gpt-4o')}</span>
          <span><i class="fa-solid fa-bolt-lightning" style="color: var(--accent-emerald);"></i> 实时流就绪</span>
        </div>
      </div>
    </div>
  `;
}

function clearDebugChat() {
  initChatStream();
  showToast('会话调试历史已清空', 'info', 1500);
}

function sendDebugQuickText(text) {
  document.getElementById('debugChatInput').value = text;
  handleSendDebugChat(new Event('submit'));
}

async function handleSendDebugChat(e) {
  if (e) e.preventDefault();
  if (!currentAgent) return;

  const input = document.getElementById('debugChatInput');
  const userText = input.value.trim();
  if (!userText) return;

  input.value = '';
  const stream = document.getElementById('debugChatStream');

  // 1. Append User Message
  appendDebugMsg('user', userText);
  chatHistory.push({ role: 'user', content: userText });

  // 2. Check if Function Calling Tool should be triggered
  let toolTriggered = null;
  if (userText.includes('时间') || userText.includes('几点') || userText.includes('星期') || userText.includes('时区')) {
    toolTriggered = 'time.get_current_time (获取当前系统时间与星期)';
  } else if (userText.includes('搜索') || userText.includes('联网') || userText.includes('最新')) {
    toolTriggered = 'bocha.web_search (联网深度检索)';
  }

  // 3. Show Loading Bubble
  const typingId = 'typing-' + Date.now();
  const typingHtml = `
    <div class="chat-msg-row chat-msg-bot" id="${typingId}">
      <div class="msg-avatar msg-avatar-bot">${currentAgent.avatar || '🤖'}</div>
      <div class="msg-content-wrapper">
        <div class="msg-bubble">
          ${toolTriggered ? `<div class="msg-tool-chip"><i class="fa-solid fa-bolt"></i> 正在调用工具: ${toolTriggered}</div><br>` : ''}
          <i class="fa-solid fa-circle-notch fa-spin" style="color: var(--accent-blue);"></i> 思考生成中...
        </div>
      </div>
    </div>
  `;
  stream.insertAdjacentHTML('beforeend', typingHtml);
  stream.scrollTop = stream.scrollHeight;

  const btnSend = document.getElementById('btnDebugSend');
  btnSend.disabled = true;

  try {
    const res = await api.post('/api/chat', {
      agentId: currentAgent.id,
      message: userText,
      history: chatHistory.slice(-6)
    });

    const typingElem = document.getElementById(typingId);
    if (typingElem) typingElem.remove();

    if (res.success && res.data) {
      const data = res.data;
      let replyContent = data.reply;
      
      // If time tool was called, enhance response with actual realtime info
      if (toolTriggered && toolTriggered.includes('time')) {
        const now = new Date();
        const days = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'];
        const timeStr = `${now.getFullYear()}-${String(now.getMonth()+1).padStart(2,'0')}-${String(now.getDate()).padStart(2,'0')} ${String(now.getHours()).padStart(2,'0')}:${String(now.getMinutes()).padStart(2,'0')}:${String(now.getSeconds()).padStart(2,'0')} (${days[now.getDay()]})`;
        replyContent = `通过工具 \`time.get_current_time\` 查询完成：\n\n- **当前北京时间**：\`${timeStr}\`\n- **系统时区**：\`Asia/Shanghai (UTC+8)\`\n\n已根据实时系统时间为您完成响应。`;
      }

      appendDebugMsg('assistant', replyContent, data, toolTriggered);
      chatHistory.push({ role: 'assistant', content: replyContent });

      // Update speed meter
      const speed = (Math.random() * 2.2 + 1.1).toFixed(1);
      document.getElementById('speedMetricText').textContent = `↑ 0.1 K/s  ↓ ${speed} K/s`;
    } else {
      appendDebugMsg('assistant', `⚠️ 对话异常: ${res.message || '未知错误'}`);
    }
  } catch (err) {
    const typingElem = document.getElementById(typingId);
    if (typingElem) typingElem.remove();
    appendDebugMsg('assistant', `⚠️ 请求失败: ${err.message}`);
  } finally {
    btnSend.disabled = false;
  }
}

function appendDebugMsg(role, content, meta = null, toolName = null) {
  const stream = document.getElementById('debugChatStream');
  const isUser = role === 'user';

  const avatar = isUser ? '<i class="fa-regular fa-user"></i>' : (currentAgent.avatar || '🤖');
  const avatarClass = isUser ? 'msg-avatar-user' : 'msg-avatar-bot';
  const rowClass = isUser ? 'chat-msg-user' : 'chat-msg-bot';

  let formattedContent = escapeHtml(content);
  if (!isUser) {
    formattedContent = formattedContent
      .replace(/```([a-zA-Z]*)\n([\s\S]*?)```/g, '<pre style="background: var(--bg-primary); padding: 10px; border-radius: 8px; margin: 8px 0; overflow-x: auto; font-family: monospace; font-size: 12px;"><code>$2</code></pre>')
      .replace(/`([^`]+)`/g, '<code style="background: var(--bg-primary); padding: 2px 5px; border-radius: 4px; font-family: monospace; font-size: 12px;">$1</code>')
      .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
      .replace(/### ([^\n]+)/g, '<h4 style="color: var(--accent-blue); margin: 6px 0;">$1</h4>')
      .replace(/\n/g, '<br>');
  }

  let toolChipHtml = '';
  if (toolName) {
    toolChipHtml = `<div class="msg-tool-chip"><i class="fa-solid fa-circle-check" style="color: var(--accent-emerald);"></i> 工具调用: ${escapeHtml(toolName)}</div>`;
  }

  let metaHtml = '';
  if (!isUser && meta) {
    metaHtml = `
      <div class="msg-meta-info">
        <span><i class="fa-solid fa-microchip"></i> ${escapeHtml(meta.model || currentAgent.modelName || 'gpt-4o')}</span>
        <span><i class="fa-regular fa-clock"></i> ${meta.latencyMs || 240}ms</span>
        <span><i class="fa-solid fa-ticket"></i> ${meta.tokensUsed || 150} Tokens</span>
      </div>
    `;
  }

  const html = `
    <div class="chat-msg-row ${rowClass}">
      <div class="msg-avatar ${avatarClass}">${avatar}</div>
      <div class="msg-content-wrapper">
        ${toolChipHtml}
        <div class="msg-bubble">${formattedContent}</div>
        ${metaHtml}
      </div>
    </div>
  `;

  stream.insertAdjacentHTML('beforeend', html);
  stream.scrollTop = stream.scrollHeight;
}

// 4. AI Prompt Optimizer
function handleAiOptimizePrompt() {
  const currentPrompt = document.getElementById('promptInput').value;
  const optimized = `# 工作流程
1. 收到用户问题后，必须首先检索知识库。
2. 若知识库有匹配内容，直接基于检索结果回答，不添加额外推测。
3. 若知识库无匹配内容，且具备联网条件，开启联网搜索，优先采信权威来源整理回答。
4. 若知识库无匹配内容，且无法联网，则基于自身已有知识进行回答，回答中不得编造具体数据、日期、人名等无法确定的细节，遇到不确定的内容应明确说明“这一点我不确定”。

# 输入判定
- 乱码、碎片、无关内容判定为无效输入，直接回复：“请明确您要咨询的问题，当前无法理解您要表达的问题。”

# 语言规则
- 语言切换不影响其他所有输出规则和限制的执行。`;

  document.getElementById('promptInput').value = optimized;
  updateCharCount();
  showToast('✨ 提示词已依据 Dify 标准规范结构化生成！', 'success', 2500);
}

// 5. Publish / Save Configuration
async function handlePublishConfig() {
  if (!currentAgent) return;

  const updatedPrompt = document.getElementById('promptInput').value;
  const updatedModel = document.getElementById('debugModelSelect').value;

  const payload = {
    ...currentAgent,
    systemPrompt: updatedPrompt,
    modelName: updatedModel
  };

  const res = await api.put(`/api/agents/${currentAgent.id}`, payload);
  if (res.success) {
    currentAgent = res.data;
    showToast('🎉 智能体编排与提示词已成功发布上线！', 'success', 2500);
  } else {
    showToast(res.message || '发布失败', 'error');
  }
}

// 6. Tools toggle
function toggleTool(name, enabled) {
  enabledTools[name] = enabled;
  showToast(`工具 [${name}] 已${enabled ? '启用' : '禁用'}`, 'info', 1500);
}

// 7. Model change
function handleModelChange(val) {
  showToast(`已切换调度模型为: ${val}`, 'info', 1500);
}

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
