/**
 * deepdive 手机版前端脚本 v0.6 —— 适配 DSH 内核 0.2.0-rc.1
 *
 * 与 v0.5（0.1.7 时期）的差异：
 *   1. 新增「内核更新提示横幅」：拉取插件路由 /dsh-update-check/status，
 *      有新版本时在顶部显示一条可关闭的提示（关闭状态按版本号记在 localStorage，
 *      同一版本不再骚扰；换新版本会重新提示）。
 *   2. Iterator polyfill 保留但已非必需：0.2.0 全树扫描确认**没有任何包**
 *      再用 `Iterator.prototype.join`（0.1.7 的 sidebar-documentpreview 用过），
 *      留着只为老 WebView 兜底，可安全删除。
 *   3. 其余机制（软键盘适配 / 回车换行 / 禁双指缩放）与内核版本无关，原样保留：
 *      · `[data-composer-card]` 标记：0.2.0 的 dsh-client-ui-conversation 里仍然存在（已核对）；
 *      · `#root` 容器：仍在 dist/index.html 中。
 */

/**
 * Iterator Helpers polyfill（0.2.0 起已无包使用，保留作老 WebView 兜底）。
 * 背景：0.1.7 的 @deepseek-ai/dsh-client-ui-sidebar-documentpreview 顶层有
 *   "function"!=typeof Iterator.prototype.join&&(Iterator.prototype.join=...)
 * 而 Iterator 是 ES2025（WebView 122+）。老 WebView 上 Iterator 未定义
 * → ReferenceError → 该插件 import 失败 → 前端白页「Failed to load plugins」。
 * 位置：mobile.js 是 body 末尾的普通脚本，早于所有 module 脚本执行 —— 时机正确。
 */
(function () {
  var iterProto = null;
  try {
    if (typeof Symbol !== 'undefined' && Symbol.iterator) {
      iterProto = Object.getPrototypeOf(Object.getPrototypeOf([][Symbol.iterator]()));
    }
  } catch (e) { iterProto = null; }

  function joinImpl(sep) {
    sep = (sep === undefined) ? ',' : String(sep);
    if (this == null || typeof this.next !== 'function') {
      throw new TypeError('Iterator.prototype.join called on incompatible receiver');
    }
    var out = '', first = true, step;
    while (!(step = this.next()).done) {
      if (!first) out += sep;
      first = false;
      var v = step.value;
      out += (v === null || v === undefined) ? '' : String(v);   // 与规范一致：null/undefined 当作空串
    }
    return out;
  }

  if (iterProto && typeof iterProto.join !== 'function') iterProto.join = joinImpl;

  if (typeof window.Iterator === 'undefined') {
    var It = function Iterator() { throw new TypeError('Iterator is not constructible'); };
    if (iterProto) { It.prototype = iterProto; } else { It.prototype.join = joinImpl; }
    window.Iterator = It;
  } else if (window.Iterator.prototype && typeof window.Iterator.prototype.join !== 'function') {
    window.Iterator.prototype.join = joinImpl;
  }
})();

/**
 * 移动端软键盘适配 v0.3
 *   VisualViewport + translateY 方案，竖屏横屏通用，rAF 节流 + 异常保护，
 *   暴露 --kb-height 供 CSS 使用；并用 pointerdown 位置判断焦点来源，
 *   程序化聚焦（切换话题/新会话自动 focus）立即 blur，不弹键盘。
 * 注：窄屏侧栏改造（三条杠 + 浮层）在核心源码 dsh-client-ui-layout，不在此文件。
 */
(function () {
  if (!window.visualViewport) return;
  var vv = window.visualViewport;
  var app = document.getElementById('root') || document.body;
  var lastKb = 0;
  var rafId = 0;
  var raf = window.requestAnimationFrame || function (fn) { return setTimeout(fn, 16); };

  function computeKb() {
    // 键盘高度 ≈ 布局视口高度 - 视觉视口高度 - 视觉视口顶部偏移
    var kb = window.innerHeight - vv.height - vv.offsetTop;
    return Math.max(0, Math.round(kb));
  }

  function apply() {
    var kb = computeKb();
    if (Math.abs(kb - lastKb) < 6) return;
    lastKb = kb;
    document.documentElement.style.setProperty('--kb-height', kb + 'px');
    if (!app) return;
    if (kb > 120) {
      app.style.transform = 'translateY(' + (-kb) + 'px)';
      app.style.transition = 'transform 0.12s ease-out';
      document.documentElement.classList.add('kb-open');
    } else {
      app.style.transform = '';
      app.style.transition = 'transform 0.12s ease-out';
      document.documentElement.classList.remove('kb-open');
    }
  }

  function schedule() {
    if (rafId) return;
    rafId = raf(function () {
      rafId = 0;
      apply();
    });
  }

  vv.addEventListener('resize', schedule);
  vv.addEventListener('scroll', schedule);
  window.addEventListener('resize', schedule);
  window.addEventListener('orientationchange', function () {
    setTimeout(schedule, 200);
  });

  var lastPointer = null;
  document.addEventListener('pointerdown', function (e) {
    lastPointer = { x: e.clientX, y: e.clientY, t: Date.now() };
  }, true);

  function isInputLike(t) {
    return t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable);
  }

  document.addEventListener('focusin', function (e) {
    var t = e && e.target;
    if (!isInputLike(t)) return;
    var userTapped = false;
    if (lastPointer && Date.now() - lastPointer.t < 800) {
      var r = t.getBoundingClientRect();
      userTapped = r.left <= lastPointer.x && lastPointer.x <= r.right &&
                   r.top <= lastPointer.y && lastPointer.y <= r.bottom;
    }
    if (!userTapped) {
      setTimeout(function () {
        if (document.activeElement === t) t.blur();
      }, 0);
      return;
    }
    setTimeout(schedule, 150);
  });
  document.addEventListener('focusout', function (e) {
    if (isInputLike(e && e.target)) setTimeout(schedule, 150);
  });

  setTimeout(schedule, 100);
})();

/**
 * 手机端回车行为 v0.4 —— **回车换行，Ctrl/Cmd+回车 发送**
 *
 * 问题（用户反馈）：手机软键盘上按回车 = 直接把消息发出去，换不了行。
 * 成因：composer 的键盘映射对 Enter 只在 `event.shiftKey === true` 时放行换行，
 *   其余情况一律 submit()。实体键盘有 Shift，手机软键盘没有。
 *
 * 做法：**不改内核代码**。在 document 捕获阶段拦下裸 Enter（仅限聊天输入栏
 *   `[data-composer-card]` 内、且输入法未组词），阻止默认与冒泡，
 *   再补发一个 shiftKey=true 的合成 keydown —— 让编辑器走它本来就有的
 *   「Shift+Enter = 换行」路径（不自己拼 DOM / 不改 React 状态）。
 *   合成事件打 __dshSynthEnter 标记避免自拦截。
 *   KeyboardEvent 不可用时退化为 execCommand('insertLineBreak')。
 */
(function () {
  var SYNTH_FLAG = '__dshSynthEnter';

  function inComposer(t) {
    try {
      // data-composer-card 是 composer 卡片的稳定标记（0.2.0 仍在，已核对）
      return !!(t && t.closest && t.closest('[data-composer-card]'));
    } catch (e) { return false; }
  }

  document.addEventListener('keydown', function (e) {
    if (e[SYNTH_FLAG]) return;
    if (e.key !== 'Enter' && e.keyCode !== 13) return;
    if (e.isComposing || e.keyCode === 229) return;              // 输入法组词中的回车：交给输入法
    if (e.shiftKey || e.ctrlKey || e.metaKey || e.altKey) return; // 组合键保持原语义
    var t = e.target;
    if (!t || !t.isContentEditable || !inComposer(t)) return;
    e.preventDefault();
    e.stopPropagation();
    try {
      var ev = new KeyboardEvent('keydown', {
        key: 'Enter', code: 'Enter', keyCode: 13, which: 13,
        shiftKey: true, bubbles: true, cancelable: true
      });
      ev[SYNTH_FLAG] = true;
      t.dispatchEvent(ev);
    } catch (err) {
      try { document.execCommand('insertLineBreak'); } catch (e2) {}
    }
  }, true);
})();

/**
 * 禁止网页被双指缩放 v0.5
 *
 * 症状：**预览窗存在时**双指捏合会把整个对话页缩放；没有预览窗时又缩不动。
 * 成因：DSH index.html 的 viewport 没有 user-scalable=no / maximum-scale；
 *   WebView 的 setSupportZoom(false) 在现代 WebView 上不可靠（viewport 才是权威）。
 * 做法（两层）：① inject.sh 给 viewport 补 maximum-scale/user-scalable=no；
 *   ② 这里兜一道：捕获阶段拦 ≥2 指的 touchmove / gesture*。
 */
(function () {
  function blockMulti(e) {
    if (e && e.touches && e.touches.length > 1 && e.cancelable) e.preventDefault();
  }
  document.addEventListener('touchmove', blockMulti, { passive: false, capture: true });
  ['gesturestart', 'gesturechange', 'gestureend'].forEach(function (n) {
    document.addEventListener(n, function (e) { if (e && e.cancelable) e.preventDefault(); },
      { passive: false, capture: true });
  });
})();

/**
 * 内核更新提示横幅 v1（配合插件 @deepseek-ai/dsh-update-check）
 *
 * 数据源：GET /dsh-update-check/status（插件注册的只读路由，只含版本号）。
 * 行为：
 *   · 页面加载后延迟 3 秒查一次（不抢首屏）；
 *   · 有新版本才显示；点 × 关闭，关闭状态按「通道@版本」记在 localStorage，
 *     同一版本不再提示，出现更新的版本会重新提示；
 *   · 网络失败/插件不存在 → 静默（绝不打扰）。
 */
(function () {
  var DISMISS_KEY = 'dsh-update-dismissed';
  var CHECK_DELAY_MS = 3000;

  function dismissed(key) {
    try { return window.localStorage.getItem(DISMISS_KEY) === key; } catch (e) { return false; }
  }
  function dismiss(key) {
    try { window.localStorage.setItem(DISMISS_KEY, key); } catch (e) { /* 无痕模式等 */ }
  }

  function show(status) {
    if (document.querySelector('.dsh-update-banner')) return;
    var key = status.recommended.tag + '@' + status.recommended.version;
    if (dismissed(key)) return;

    var bar = document.createElement('div');
    bar.className = 'dsh-update-banner';
    bar.setAttribute('role', 'status');

    var text = document.createElement('span');
    text.className = 'dsh-update-banner__text';
    text.textContent = 'DSH 内核有新版本 ' + status.recommended.version +
      '（当前 ' + status.current + '，' + status.recommended.tag + ' 通道）——让电脑端重新出包即可升级';
    bar.appendChild(text);

    var close = document.createElement('button');
    close.type = 'button';
    close.className = 'dsh-update-banner__close';
    close.setAttribute('aria-label', '关闭更新提示');
    close.textContent = '×';
    close.addEventListener('click', function () {
      dismiss(key);
      if (bar.parentNode) bar.parentNode.removeChild(bar);
    });
    bar.appendChild(close);

    document.body.appendChild(bar);
  }

  function check() {
    var url = '/dsh-update-check/status';
    var options = { credentials: 'same-origin', cache: 'no-store' };
    var request = (typeof window.fetch === 'function')
      ? window.fetch(url, options)
      : new Promise(function (resolve, reject) {
          var xhr = new XMLHttpRequest();
          xhr.open('GET', url, true);
          xhr.onload = function () {
            try { resolve({ ok: xhr.status >= 200 && xhr.status < 300, json: function () { return Promise.resolve(JSON.parse(xhr.responseText)); } }); }
            catch (e) { reject(e); }
          };
          xhr.onerror = reject;
          xhr.send();
        });
    request
      .then(function (res) { return res && res.ok ? res.json() : null; })
      .then(function (status) {
        if (!status || status.ok !== true) return;
        if (status.updateAvailable !== true || !status.recommended) return;
        show(status);
      })
      .catch(function () { /* 静默：离线或插件未启用都不该打扰用户 */ });
  }

  if (document.readyState === 'complete' || document.readyState === 'interactive') {
    setTimeout(check, CHECK_DELAY_MS);
  } else {
    document.addEventListener('DOMContentLoaded', function () { setTimeout(check, CHECK_DELAY_MS); });
  }
})();
