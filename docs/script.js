/* ============================================================
   Nekobot 官网交互脚本
   多语言依赖 i18n.js 提供的 window.NEKOBOT_I18N（需先加载）
   ============================================================ */
(function () {
  'use strict';

  const $ = (sel, ctx = document) => ctx.querySelector(sel);
  const $$ = (sel, ctx = document) => Array.from(ctx.querySelectorAll(sel));

  /* ---------- 0. 多语言引擎 ---------- */
  const I18N_DICT = window.NEKOBOT_I18N || {};
  const I18N_DEFAULT = 'zh-CN';
  const LANGS = ['zh-CN', 'zh-TW', 'en', 'ja', 'ko'];
  const LANG_LABELS = { 'zh-CN': '简体中文', 'zh-TW': '繁體中文', 'en': 'English', 'ja': '日本語', 'ko': '한국어' };
  const LANG_HTML = { 'zh-CN': 'zh-CN', 'zh-TW': 'zh-TW', 'en': 'en', 'ja': 'ja', 'ko': 'ko' };
  // 更新日志 body 中的语言段落标题（与 RELEASE.md 的 changelog 多语言格式对应）
  const RELEASE_SECTION_NAMES = { 'en': 'English', 'zh-TW': '繁體中文', 'ja': '日本語', 'ko': '한국어' };

  let currentLang = I18N_DEFAULT;
  let chatScript = [];
  let chatGeneration = 0;
  let chatStarted = false;
  let lastReleases = null;
  let refreshDynamicA11y = null; // 由截图画廊注册，语言切换时刷新动态 aria 标签

  function detectLang() {
    // 每次打开网站都优先跟随浏览器语言；手动选择只在浏览器语言不受支持时作为回退
    const prefs = (navigator.languages && navigator.languages.length)
      ? navigator.languages
      : [navigator.language || I18N_DEFAULT];
    for (const raw of prefs) {
      const l = String(raw).toLowerCase();
      if (l.startsWith('zh')) {
        if (l.includes('tw') || l.includes('hk') || l.includes('mo') || l.includes('hant')) return 'zh-TW';
        return 'zh-CN';
      }
      if (l.startsWith('ja')) return 'ja';
      if (l.startsWith('ko')) return 'ko';
      if (l.startsWith('en')) return 'en';
    }
    let saved = null;
    try { saved = localStorage.getItem('nekobot_site_lang'); } catch {}
    if (saved && LANGS.includes(saved)) return saved;
    return I18N_DEFAULT;
  }

  function t(key, vars) {
    const d = I18N_DICT[currentLang] || {};
    let s = (d[key] != null) ? d[key] : ((I18N_DICT[I18N_DEFAULT] || {})[key]);
    if (s == null) return key;
    if (vars) {
      Object.keys(vars).forEach((k) => { s = s.replace('{' + k + '}', vars[k]); });
    }
    return s;
  }

  // 模拟聊天的发言方顺序（ai / me 交替，与文案条数一一对应）
  const CHAT_FROM_PATTERN = ['ai', 'me', 'ai', 'ai', 'me', 'ai', 'ai'];

  function applyI18n() {
    const dict = I18N_DICT[currentLang] || I18N_DICT[I18N_DEFAULT] || {};
    const fallback = I18N_DICT[I18N_DEFAULT] || {};
    $$('[data-i18n]').forEach((el) => {
      const v = dict[el.dataset.i18n] != null ? dict[el.dataset.i18n] : fallback[el.dataset.i18n];
      if (v != null) el.textContent = v;
    });
    $$('[data-i18n-html]').forEach((el) => {
      const v = dict[el.dataset.i18nHtml] != null ? dict[el.dataset.i18nHtml] : fallback[el.dataset.i18nHtml];
      if (v != null) el.innerHTML = v;
    });
    $$('[data-i18n-attr]').forEach((el) => {
      el.dataset.i18nAttr.split(';').forEach((pair) => {
        const idx = pair.indexOf(':');
        if (idx < 1) return;
        const attr = pair.slice(0, idx).trim();
        const key = pair.slice(idx + 1).trim();
        if (!attr || !key) return;
        const v = dict[key] != null ? dict[key] : fallback[key];
        if (v != null) el.setAttribute(attr, v);
      });
    });
    document.title = t('meta.title');
    const metaDesc = $('meta[name="description"]');
    if (metaDesc) metaDesc.setAttribute('content', t('meta.description'));
    document.documentElement.lang = LANG_HTML[currentLang] || I18N_DEFAULT;
    const rawChat = Array.isArray(dict.chat) && dict.chat.length ? dict.chat : (I18N_DICT[I18N_DEFAULT].chat || []);
    chatScript = rawChat.map((text, i) => ({ from: CHAT_FROM_PATTERN[i % CHAT_FROM_PATTERN.length], text }));
    updateLangSwitcher();
    if (refreshDynamicA11y) refreshDynamicA11y();
  }

  function updateLangSwitcher() {
    const label = $('#langBtnLabel');
    if (label) label.textContent = LANG_LABELS[currentLang] || currentLang;
    const btn = $('#langBtn');
    if (btn) btn.setAttribute('aria-label', t('lang.aria'));
    const menu = $('#langMenu');
    if (menu) {
      $$('button[data-lang]', menu).forEach((b) => {
        const active = b.dataset.lang === currentLang;
        b.classList.toggle('active', active);
        b.setAttribute('aria-selected', String(active));
      });
    }
  }

  function closeLangMenu() {
    const sw = $('#langSwitch');
    const btn = $('#langBtn');
    if (sw && sw.classList.contains('open')) {
      sw.classList.remove('open');
      if (btn) btn.setAttribute('aria-expanded', 'false');
    }
  }

  function setLang(lang) {
    if (!LANGS.includes(lang) || lang === currentLang) { closeLangMenu(); return; }
    currentLang = lang;
    try { localStorage.setItem('nekobot_site_lang', lang); } catch {}
    applyI18n();
    if (lastReleases) renderReleases(lastReleases);
    chatGeneration += 1;
    if (chatStarted) playChat();
  }

  currentLang = detectLang();
  applyI18n();

  // 语言切换器交互
  (() => {
    const sw = $('#langSwitch');
    const btn = $('#langBtn');
    const menu = $('#langMenu');
    if (!sw || !btn || !menu) return;
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const open = sw.classList.toggle('open');
      btn.setAttribute('aria-expanded', String(open));
    });
    menu.addEventListener('click', (e) => {
      const b = e.target.closest('button[data-lang]');
      if (!b) return;
      setLang(b.dataset.lang);
    });
    document.addEventListener('click', (e) => {
      if (!sw.contains(e.target)) closeLangMenu();
    });
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') closeLangMenu();
    });
  })();

  /* ---------- 1. 导航栏滚动毛玻璃 ---------- */
  const nav = $('#nav');
  const toTop = $('#toTop');
  const onScroll = () => {
    nav.classList.toggle('scrolled', window.scrollY > 24);
    toTop.classList.toggle('show', window.scrollY > 560);
  };
  window.addEventListener('scroll', onScroll, { passive: true });
  onScroll();

  /* ---------- 2. 移动端汉堡菜单 ---------- */
  const burger = $('#navBurger');
  const navLinks = $('#navLinks');
  burger.addEventListener('click', () => {
    const open = navLinks.classList.toggle('open');
    burger.classList.toggle('open', open);
    burger.setAttribute('aria-expanded', String(open));
  });
  navLinks.addEventListener('click', (e) => {
    if (e.target.tagName === 'A') {
      navLinks.classList.remove('open');
      burger.classList.remove('open');
      burger.setAttribute('aria-expanded', 'false');
    }
  });

  /* ---------- 3. 滚动高亮当前导航（Scroll Spy） ---------- */
  const sections = ['screenshots', 'features', 'modes', 'role', 'agent', 'advanced', 'plugins', 'changelog', 'faq', 'download']
    .map((id) => document.getElementById(id))
    .filter(Boolean);
  const linkMap = new Map(
    $$('.nav-links a').map((a) => [a.getAttribute('href').slice(1), a])
  );
  const spy = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        const link = linkMap.get(entry.target.id);
        if (!link) return;
        if (entry.isIntersecting) {
          $$('.nav-links a').forEach((a) => a.classList.remove('active'));
          link.classList.add('active');
        }
      });
    },
    { rootMargin: '-38% 0px -55% 0px' }
  );
  sections.forEach((s) => spy.observe(s));

  /* ---------- 4. 滚动渐入动画 ---------- */
  let revealIO = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add('visible');
          revealIO.unobserve(entry.target);
        }
      });
    },
    { threshold: 0.12 }
  );
  $$('.reveal').forEach((el) => revealIO.observe(el));

  /* ---------- 5. 手机模型 · 模拟聊天循环（文案随语言切换） ---------- */
  const chatBody = $('#chatBody');
  const MSG_GAP = 1300;   // 两条消息间隔
  const TYPING_TIME = 900; // “正在输入”时长
  const LOOP_PAUSE = 3400; // 一轮结束后的停顿

  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  function addMsg(from, text) {
    const div = document.createElement('div');
    div.className = `msg ${from}`;
    div.textContent = text;
    chatBody.appendChild(div);
    // 超出屏幕时温和地上移（保持最新可见）
    while (chatBody.scrollHeight > chatBody.clientHeight + 4 && chatBody.children.length > 2) {
      chatBody.removeChild(chatBody.firstElementChild);
    }
  }

  function addTyping() {
    const div = document.createElement('div');
    div.className = 'msg typing';
    div.innerHTML = '<i></i><i></i><i></i>';
    chatBody.appendChild(div);
    return div;
  }

  async function playChat() {
    if (!chatBody || !chatScript.length) return;
    const gen = chatGeneration;
    chatBody.innerHTML = '';
    for (const item of chatScript) {
      if (gen !== chatGeneration) return; // 语言已切换，放弃本轮
      if (item.from === 'ai') {
        const typing = addTyping();
        await sleep(TYPING_TIME);
        typing.remove();
      }
      addMsg(item.from, item.text);
      await sleep(MSG_GAP);
    }
    await sleep(LOOP_PAUSE);
    if (gen !== chatGeneration) return;
    playChat();
  }

  // 等手机滚入视野后再开始播放，省电也更自然
  if (chatBody) {
    const phoneIO = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting) {
          phoneIO.disconnect();
          chatStarted = true;
          playChat();
        }
      },
      { threshold: 0.3 }
    );
    phoneIO.observe(chatBody);
  }

  /* ---------- 6. 功能展示 Tab 切换 ---------- */
  const tabs = $$('.sc-tab');
  const panels = $$('.sc-panel');
  tabs.forEach((tab) => {
    tab.addEventListener('click', () => {
      const key = tab.dataset.tab;
      tabs.forEach((t2) => {
        const active = t2 === tab;
        t2.classList.toggle('active', active);
        t2.setAttribute('aria-selected', String(active));
      });
      panels.forEach((p) => p.classList.toggle('active', p.dataset.panel === key));
    });
  });

  /* ---------- 7. 截图画廊：横向滚动 + 箭头分页 + 进度条 + 灯箱 ---------- */
  const screenshotGallery = $('.screenshot-gallery');
  if (screenshotGallery) {
    // 纵向滚轮转换为横向滚动
    screenshotGallery.addEventListener('wheel', (event) => {
      if (Math.abs(event.deltaY) <= Math.abs(event.deltaX)) return;

      const unit = event.deltaMode === 1
        ? 32
        : event.deltaMode === 2
          ? screenshotGallery.clientWidth
          : 1;
      const distance = event.deltaY * unit;
      const maxScrollLeft = screenshotGallery.scrollWidth - screenshotGallery.clientWidth;
      const canScrollLeft = distance < 0 && screenshotGallery.scrollLeft > 1;
      const canScrollRight = distance > 0 && screenshotGallery.scrollLeft < maxScrollLeft - 1;
      if (!canScrollLeft && !canScrollRight) return;

      event.preventDefault();
      screenshotGallery.scrollBy({
        left: distance,
        behavior: 'auto',
      });
    }, { passive: false });

    // 箭头按钮 + 圆点 + 标题联动
    const prevBtn = $('#galleryPrev');
    const nextBtn = $('#galleryNext');
    const dotsBox = $('#galleryDots');
    const shotTitle = $('#shotTitle');
    const shotDesc = $('#shotDesc');
    const shotCaption = $('.screenshot-caption');
    const shotCards = $$('.screenshot-card', screenshotGallery);
    let activeShot = 0;

    const goTo = (i, smooth = true) => {
      const clamped = Math.max(0, Math.min(shotCards.length - 1, i));
      screenshotGallery.scrollTo({ left: clamped * screenshotGallery.clientWidth, behavior: smooth ? 'smooth' : 'auto' });
    };

    // 生成轮播圆点
    const dots = shotCards.map((card, i) => {
      const dot = document.createElement('button');
      dot.type = 'button';
      dot.addEventListener('click', () => goTo(i));
      if (dotsBox) dotsBox.appendChild(dot);
      return dot;
    });

    const updateGalleryUi = () => {
      const cw = screenshotGallery.clientWidth || 1;
      const sl = screenshotGallery.scrollLeft;
      const idx = Math.max(0, Math.min(shotCards.length - 1, Math.round(sl / cw)));
      if (prevBtn) prevBtn.disabled = sl <= 1;
      if (nextBtn) nextBtn.disabled = sl >= screenshotGallery.scrollWidth - cw - 1;
      dots.forEach((d, i) => d.classList.toggle('active', i === idx));
      if (idx !== activeShot) {
        activeShot = idx;
        const card = shotCards[idx];
        const capB = $('figcaption b', card);
        const capS = $('figcaption span', card);
        if (shotTitle) shotTitle.textContent = capB ? capB.textContent : '';
        if (shotDesc) shotDesc.textContent = capS ? capS.textContent : '';
        if (shotCaption && shotCaption.animate) {
          shotCaption.animate(
            [{ opacity: 0, transform: 'translateY(8px)' }, { opacity: 1, transform: 'none' }],
            { duration: 320, easing: 'ease-out' }
          );
        }
      }
    };
    if (prevBtn) prevBtn.addEventListener('click', () => goTo(activeShot - 1));
    if (nextBtn) nextBtn.addEventListener('click', () => goTo(activeShot + 1));
    screenshotGallery.addEventListener('scroll', updateGalleryUi, { passive: true });
    window.addEventListener('resize', () => { goTo(activeShot, false); updateGalleryUi(); });
    window.addEventListener('load', updateGalleryUi);
    updateGalleryUi();

    // 灯箱预览（点击卡片放大，支持左右切换与键盘操作）
    const lightbox = document.createElement('div');
    lightbox.className = 'lightbox';
    lightbox.setAttribute('role', 'dialog');
    lightbox.setAttribute('aria-modal', 'true');
    lightbox.innerHTML = `
      <div class="lightbox-backdrop"></div>
      <figure class="lightbox-figure">
        <img alt="" />
        <figcaption><b></b><span></span></figcaption>
      </figure>
      <button class="lightbox-nav prev" type="button"><svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg></button>
      <button class="lightbox-nav next" type="button"><svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg></button>
      <button class="lightbox-close" type="button"><svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg></button>`;
    document.body.appendChild(lightbox);

    const lbImg = $('.lightbox-figure img', lightbox);
    const lbTitle = $('.lightbox-figure b', lightbox);
    const lbDesc = $('.lightbox-figure span', lightbox);
    let lbIndex = 0;

    // 语言切换时刷新依赖文案的动态 aria / 标题
    refreshDynamicA11y = () => {
      lightbox.setAttribute('aria-label', t('js.lightboxLabel'));
      const lbPrev = $('.lightbox-nav.prev', lightbox);
      const lbNext = $('.lightbox-nav.next', lightbox);
      const lbClose = $('.lightbox-close', lightbox);
      if (lbPrev) lbPrev.setAttribute('aria-label', t('js.lbPrev'));
      if (lbNext) lbNext.setAttribute('aria-label', t('js.lbNext'));
      if (lbClose) lbClose.setAttribute('aria-label', t('js.lbClose'));
      shotCards.forEach((card, i) => {
        const capB = $('figcaption b', card);
        const name = capB ? capB.textContent : t('js.defaultShot');
        if (dots[i]) dots[i].setAttribute('aria-label', t('js.galleryView', { name }));
        card.setAttribute('aria-label', t('js.galleryZoom', { name }));
      });
    };
    refreshDynamicA11y();

    function syncLightbox() {
      const card = shotCards[lbIndex];
      if (!card) return;
      const img = $('img', card);
      lbImg.src = img.currentSrc || img.src;
      lbImg.alt = img.alt;
      const capB = $('figcaption b', card);
      const capS = $('figcaption span', card);
      lbTitle.textContent = capB ? capB.textContent : '';
      lbDesc.textContent = capS ? capS.textContent : '';
    }
    function openLightbox(index) {
      lbIndex = index;
      syncLightbox();
      lightbox.classList.add('open');
      document.body.classList.add('lightbox-open');
    }
    function closeLightbox() {
      lightbox.classList.remove('open');
      document.body.classList.remove('lightbox-open');
    }
    function stepLightbox(delta) {
      lbIndex = (lbIndex + delta + shotCards.length) % shotCards.length;
      syncLightbox();
    }

    shotCards.forEach((card, i) => {
      card.tabIndex = 0;
      card.setAttribute('role', 'button');
      card.addEventListener('click', () => openLightbox(i));
      card.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openLightbox(i); }
      });
    });
    $('.lightbox-backdrop', lightbox).addEventListener('click', closeLightbox);
    $('.lightbox-close', lightbox).addEventListener('click', closeLightbox);
    $('.lightbox-nav.prev', lightbox).addEventListener('click', () => stepLightbox(-1));
    $('.lightbox-nav.next', lightbox).addEventListener('click', () => stepLightbox(1));
    document.addEventListener('keydown', (e) => {
      if (!lightbox.classList.contains('open')) return;
      if (e.key === 'Escape') closeLightbox();
      else if (e.key === 'ArrowLeft') stepLightbox(-1);
      else if (e.key === 'ArrowRight') stepLightbox(1);
    });
  }

  /* ---------- 8. 回到顶部 ---------- */
  toTop.addEventListener('click', () => {
    window.scrollTo({ top: 0, behavior: 'smooth' });
  });

  /* ---------- 9. FAQ 手风琴（同时只展开一个） ---------- */
  const faqItems = $$('.faq-item');
  faqItems.forEach((item) => {
    item.addEventListener('toggle', () => {
      if (item.open) {
        faqItems.forEach((other) => {
          if (other !== item) other.open = false;
        });
      }
    });
  });

  /* ---------- 10. 最新 APK 直链 ---------- */
  (async () => {
    const apkBtns = $$('[data-dl="apk"]');
    if (!apkBtns.length) return;
    try {
      const r = await fetch('https://api.github.com/repos/asukaneko/Nekobot-Android/releases/latest', {
        headers: { Accept: 'application/vnd.github.v3+json' },
        signal: AbortSignal.timeout(5000),
      });
      if (!r.ok) throw new Error(String(r.status));
      const data = await r.json();
      const asset = data.assets?.[0];
      if (asset?.browser_download_url) {
        apkBtns.forEach((btn) => { btn.href = asset.browser_download_url; });
      }
    } catch {}
  })();

  /* ---------- 11. 从 GitHub Releases 拉取更新日志 ----------
     release body 支持多语言段落（见 RELEASE.md）：
     中文条目置顶，其后用 "### English" / "### 繁體中文" /
     "### 日本語" / "### 한국어" 小节分隔；
     缺少当前语言的段落时回退显示中文部分。 */
  function splitReleaseBody(body) {
    const parts = { default: [] };
    let cur = 'default';
    (body || '').split(/\r?\n/).forEach((line) => {
      const m = line.match(/^#{2,4}\s*\[?(English|繁體中文|日本語|한국어)\]?\s*$/);
      if (m) {
        cur = Object.keys(RELEASE_SECTION_NAMES).find((k) => RELEASE_SECTION_NAMES[k] === m[1]) || 'default';
        if (!parts[cur]) parts[cur] = [];
        return;
      }
      const s = line.trim();
      if (s.startsWith('-') || s.startsWith('*')) parts[cur].push(s.replace(/^[-*]\s*/, ''));
    });
    return parts;
  }

  function releaseItemsForLang(body) {
    const parts = splitReleaseBody(body);
    const section = currentLang !== I18N_DEFAULT ? parts[currentLang] : null;
    if (section && section.length) return section;
    return parts.default;
  }

  const FALLBACK_RELEASES = [
    {
      tag_name: 'v0.8.0', published_at: '2026-09-30',
      body: '- 本地角色会话新增可配置的延迟回复，操作失败不丢消息，跨会话切换保留\n- 后台任务完成自动唤醒会话汇报结果，后台任务独立槽位保活不随进程回收\n- 子代理进度卡片持久化与嵌套通知按层级路由，退出会话重进后仍在\n- 子代理任务清单隔离，不再覆盖主会话任务列表；新增子代理 Token 用量统计\n- 缓存 token 计价与用量展示：缓存命中价/写入价、平均缓存命中率、命中徽章与构成查看\n- 会话列表为 AI 生成中的会话显示主题色运行竖条\n- 后台生成阶段保留停止按钮，可直接停止或发消息抢占\n- Agent 设置支持编辑最大后台子代理数量（1-10）\n- 数据维护新增会话工作区文件管理，支持清理已删除会话的残留文件\n- 消息生图前用聊天模型优化提示词，减少设定文字被画进图片\n- 文件预览弹窗支持通过系统分享面板分享文件\n- 修复 git 摘要对含 pack 仓库静默失效与模型编辑弹窗已保存 Key 回显\n\n### English\n- Configurable delayed replies for local character sessions; failed operations no longer lose messages, and delayed replies survive session switches\n- Finished background tasks wake the session to report results; background tasks keep a dedicated slot and survive process recycling\n- Sub-agent progress cards persist; nested notifications are routed by hierarchy and remain after re-entering a session\n- Sub-agent task lists are isolated from the main session; added sub-agent token usage stats\n- Cache token pricing and usage display: cache hit/write prices, average hit rate, hit badges and composition view\n- Session list shows a themed running bar for sessions being generated\n- Stop button stays available during background generation; stop directly or preempt with a message\n- Agent settings allow editing the max number of background sub-agents (1-10)\n- Data maintenance adds session workspace file management, including cleanup of leftovers from deleted sessions\n- Image prompts are optimized by the chat model before generation, so setup text is less likely to be painted into images\n- File preview dialog supports sharing via the system share sheet\n- Fixed git digest silently failing on repos with pack files, and saved-key echo in the model edit dialog\n\n### 繁體中文\n- 本地角色會話新增可設定的延遲回覆，操作失敗不丟訊息，跨會話切換保留\n- 後台任務完成自動喚醒會話回報結果，後台任務獨立槽位保活不隨進程回收\n- 子代理進度卡片持久化與巢狀通知按層級路由，退出會話重進後仍在\n- 子代理任務清單隔離，不再覆蓋主會話任務列表；新增子代理 Token 用量統計\n- 快取 token 計價與用量展示：快取命中價/寫入價、平均命中率、命中徽章與構成查看\n- 會話列表為 AI 生成中的會話顯示主題色執行豎條\n- 後台生成階段保留停止按鈕，可直接停止或發訊息搶佔\n- Agent 設定支援編輯最大後台子代理數量（1-10）\n- 資料維護新增會話工作區檔案管理，支援清理已刪除會話的殘留檔案\n- 訊息生圖前用聊天模型最佳化提示詞，減少設定文字被畫進圖片\n- 檔案預覽彈窗支援透過系統分享面板分享檔案\n- 修復 git 摘要對含 pack 儲存庫靜默失效與模型編輯彈窗已儲存 Key 回顯\n\n### 日本語\n- ローカルキャラセッションに設定可能な遅延返信を追加。操作失敗でメッセージを失わず、セッション切り替えでも保持\n- バックグラウンドタスク完了時にセッションを自動起動して結果を報告。タスクは独立スロットで保持され、プロセス回収でも消えない\n- サブエージェントの進行カードを永続化し、ネスト通知を階層ルーティング。セッション再入後も維持\n- サブエージェントのタスクリストを分離し、メインのリストを上書きしない。サブエージェントの Token 使用量統計も追加\n- キャッシュ token の課金と使用量表示：ヒット価格/書き込み価格、平均ヒット率、ヒットバッジと内訳\n- 生成中のセッションにテーマカラーの実行バーを表示\n- バックグラウンド生成中も停止ボタンを維持。直接停止、またはメッセージで割り込み可能\n- Agent 設定で最大バックグラウンドサブエージェント数（1-10）を編集可能に\n- データメンテナンスにセッションワークスペースのファイル管理を追加。削除済みセッションの残りファイルも整理\n- 画像生成前にチャットモデルがプロンプトを最適化し、設定文が画像に描かれるのを軽減\n- ファイルプレビューのダイアログからシステム共有シートで共有可能に\n- pack を含むリポジトリで git サマリーが静かに失敗する問題と、モデル編集ダイアログの保存済みキー表示を修正\n\n### 한국어\n- 로컬 캐릭터 세션에 지연 응답 설정 추가. 작업 실패 시 메시지가 사라지지 않고 세션 전환에도 유지\n- 백그라운드 작업 완료 시 세션을 깨워 결과를 보고. 작업은 독립 슬롯으로 유지되어 프로세스 회수에도 살아남음\n- 서브에이전트 진행 카드 영구 저장, 중첩 알림 계층 라우팅. 세션 재진입 후에도 유지\n- 서브에이전트 작업 목록 격리로 메인 목록을 덮지 않음. 서브에이전트 Token 사용량 통계 추가\n- 캐시 token 과금·사용량 표시: 히트 가격/쓰기 가격, 평균 히트율, 히트 배지와 구성 보기\n- 생성 중인 세션에 테마 색 실행 표시줄 표시\n- 백그라운드 생성 중에도 중지 버튼 유지, 바로 중지하거나 메시지로 선점\n- Agent 설정에서 최대 백그라운드 서브에이전트 수(1-10) 편집 지원\n- 데이터 관리에 세션 작업 공간 파일 관리 추가, 삭제된 세션 잔여 파일 정리 지원\n- 이미지 생성 전 채팅 모델이 프롬프트를 최적화해 설정 문구가 그려지는 현상 감소\n- 파일 미리보기 대화상자에서 시스템 공유 패널 공유 지원\n- pack 포함 저장소에서 git 요약이 조용히 실패하던 문제와 모델 편집 대화상자의 저장된 Key 표시 수정',
    },
    {
      tag_name: 'v0.7.9', published_at: '2026-09-28',
      body: '- 聊天历史分页加载，超大会话首屏只取最近一页，滚动到顶部自动加载更早历史\n- 继承角色的 Agent 支持可选的长期单会话记忆：经历档案与记忆回查工具\n- 工作区文件浏览器新增「打开终端」入口，按会话保留终端输出历史\n- 上下文分析支持点击查看压缩摘要原文\n- 插件网络能力新增 POST 请求与自定义请求头支持\n- 修复媒体预览弹窗系统栏适配问题\n- 修复经历归档并发冲突、记忆回查工具不可见与大历史边界问题\n- 放宽会话工作区文件操作确认，仅共享工作区写入/删除需确认\n- 经历档案页改为单一滚动列表\n- 调整 Markdown 标题字号与段间距，适配窄屏排版\n\n### English\n- Paginated chat history: very large sessions load only the latest page first, with older history loaded on scroll-to-top\n- Character-bound Agents can optionally enable long-term single-session memory: experience archive and memory recall tools\n- Workspace file browser gains an "Open terminal" entry with per-session terminal output history\n- Context analysis supports viewing the original compressed summary text\n- Plugin network capability adds POST requests and custom headers\n- Fixed media preview dialog system-bar insets\n- Fixed experience-archive concurrency conflicts, invisible memory recall tools and large-history edge cases\n- Relaxed workspace file operation confirmations; only shared-workspace write/delete needs confirmation\n- Experience archive page is now a single scrolling list\n- Adjusted Markdown heading sizes and paragraph spacing for narrow screens\n\n### 繁體中文\n- 聊天歷史分頁載入，超大會話首屏只取最近一頁，捲動到頂部自動載入更早歷史\n- 繼承角色的 Agent 支援可選的長期單會話記憶：經歷檔案與記憶回查工具\n- 工作區檔案瀏覽器新增「開啟終端機」入口，按會話保留終端機輸出歷史\n- 上下文分析支援點擊查看壓縮摘要原文\n- 外掛網路能力新增 POST 請求與自訂請求標頭支援\n- 修復媒體預覽彈窗系統列適配問題\n- 修復經歷歸檔並發衝突、記憶回查工具不可見與大歷史邊界問題\n- 放寬會話工作區檔案操作確認，僅共享工作區寫入/刪除需確認\n- 經歷檔案頁改為單一捲動列表\n- 調整 Markdown 標題字號與段間距，適配窄屏排版\n\n### 日本語\n- チャット履歴のページング読み込み。巨大セッションは最新ページのみを先に表示し、最上部へのスクロールで過去履歴を読み込み\n- キャラを継承した Agent に任意の長期単一セッション記憶を追加：経験アーカイブと記憶検索ツール\n- ワークスペースファイルブラウザに「ターミナルを開く」入口を追加。セッションごとにターミナル出力履歴を保持\n- コンテキスト分析で圧縮サマリーの原文をクリック表示可能に\n- プラグインのネットワーク機能に POST リクエストとカスタムヘッダーを追加\n- メディアプレビューダイアログのシステムバー適合を修正\n- 経験アーカイブの並行競合、記憶検索ツールが表示されない問題、巨大履歴の境界問題を修正\n- ワークスペースファイル操作の確認を緩和。共有ワークスペースへの書き込み/削除のみ確認が必要\n- 経験アーカイブページを単一スクロールリストに変更\n- Markdown の見出しサイズと段落間隔を調整し、狭い画面のレイアウトに対応\n\n### 한국어\n- 채팅 기록 페이지네이션 로딩. 초대형 세션은 최근 한 페이지만 먼저 표시하고, 맨 위로 스크롤하면 이전 기록을 자동 로딩\n- 캐릭터를 상속한 Agent에 선택적 장기 단일 세션 기억 추가: 경험 아카이브와 기억 조회 도구\n- 작업 공간 파일 브라우저에 "터미널 열기" 진입점 추가, 세션별 터미널 출력 기록 유지\n- 컨텍스트 분석에서 압축 요약 원문 클릭 조회 지원\n- 플러그인 네트워크 기능에 POST 요청과 사용자 지정 헤더 추가\n- 미디어 미리보기 대화상자의 시스템 바 적합 문제 수정\n- 경험 아카이브 동시성 충돌, 기억 조회 도구 미표시, 대형 기록 경계 문제 수정\n- 세션 작업 공간 파일 작업 확인 완화, 공유 작업 공간 쓰기/삭제만 확인 필요\n- 경험 아카이브 페이지를 단일 스크롤 목록으로 변경\n- Markdown 제목 크기와 단락 간격 조정으로 좁은 화면 레이아웃 대응',
    },
    {
      tag_name: 'v0.7.8', published_at: '2026-09-26',
      body: '- 新增自定义表情包：ZIP 批量导入、管理页与聊天内发送 `[名称]` 渲染\n- 新增 list_stickers / send_sticker / view_sticker 工具与 sticker 工具集\n- 聊天输入框下方新增可配置快捷工具栏，支持增删、排序与折叠\n- 新增 Agent 悬浮窗：后台展示进度与授权确认，可拖动折叠\n- 插件支持安装工作区 ZIP 包，安装前强制第三方同意与权限勾选\n- 插件新增私有文件（files）能力：页面上传与 files.list/read/delete\n- 修复后台提问通知进入后误跳过提问，弹窗不再响应遮罩点击\n- 统一会话内联提示并锚定到触发消息下方\n- 会话列表滚动状态改由 ViewModel 持有，修复返回错位跳顶\n- AI 执行期间禁用手动压缩上下文\n\n### English\n- Custom sticker packs: batch ZIP import, a management page, and sending `[name]` in chat\n- New list_stickers / send_sticker / view_sticker tools and a sticker tool set\n- Configurable quick toolbar under the chat input with add, remove, reorder and collapse\n- New Agent floating window: background progress and approval prompts, draggable and collapsible\n- Plugins can install workspace ZIP packages, with forced third-party consent and permission checks before install\n- Plugin private files capability: page uploads and files.list / read / delete\n- Fixed background-question notifications skipping the question when opened; dialogs no longer react to backdrop clicks\n- Unified in-session inline notices anchored below the triggering message\n- Session list scroll state is now held by the ViewModel, fixing wrong jumps back to top\n- Manual context compaction is disabled while the AI is generating\n\n### 繁體中文\n- 新增自訂表情包：ZIP 批次匯入、管理頁與聊天內傳送 `[名稱]` 渲染\n- 新增 list_stickers / send_sticker / view_sticker 工具與 sticker 工具集\n- 聊天輸入框下方新增可設定快捷工具列，支援增刪、排序與摺疊\n- 新增 Agent 懸浮窗：背景顯示進度與授權確認，可拖曳摺疊\n- 外掛支援安裝工作區 ZIP 包，安裝前強制第三方同意與權限勾選\n- 外掛新增私有檔案（files）能力：頁面上傳與 files.list/read/delete\n- 修復背景提問通知進入後誤跳過提問，彈窗不再回應遮罩點擊\n- 統一會話內聯提示並錨定到觸發訊息下方\n- 會話列表捲動狀態改由 ViewModel 持有，修復返回錯位跳頂\n- AI 執行期間停用手動壓縮上下文\n\n### 日本語\n- カスタムスタンプパックを追加：ZIP 一括インポート、管理ページ、チャット内での `[名前]` 送信レンダリング\n- list_stickers / send_sticker / view_sticker ツールと sticker ツールセットを追加\n- チャット入力欄の下に設定可能なクイックツールバーを追加。追加・削除・並べ替え・折りたたみに対応\n- Agent フローティングウィンドウを追加：バックグラウンドで進捗と承認確認を表示、ドラッグと折りたたみ可能\n- プラグインがワークスペースの ZIP パッケージをインストール可能に。インストール前にサードパーティ同意と権限チェックを強制\n- プラグインにプライベートファイル（files）機能を追加：ページからのアップロードと files.list/read/delete\n- バックグラウンド質問通知を開いた際に質問を誤ってスキップする問題を修正。ダイアログは背景クリックに反応しなくなりました\n- セッション内インライン通知を統一し、トリガーメッセージの下にアンカー表示\n- セッションリストのスクロール状態を ViewModel が保持するように変更し、戻った際の先頭ジャンプを修正\n- AI の生成中は手動コンテキスト圧縮を無効化\n\n### 한국어\n- 커스텀 스티커 팩 추가: ZIP 일괄 가져오기, 관리 페이지, 채팅 내 `[이름]` 전송 렌더링\n- list_stickers / send_sticker / view_sticker 도구와 sticker 도구 세트 추가\n- 채팅 입력창 아래 설정 가능한 빠른 도구 모음 추가, 추가·삭제·정렬·접기 지원\n- Agent 플로팅 창 추가: 백그라운드에서 진행률과 승인 확인 표시, 드래그와 접기 가능\n- 플러그인이 작업 공간 ZIP 패키지 설치 지원, 설치 전 서드파티 동의와 권한 확인 강제\n- 플러그인 전용 파일(files) 기능 추가: 페이지 업로드와 files.list/read/delete\n- 백그라운드 질문 알림 진입 시 질문을 잘못 건너뛰던 문제 수정, 대화상자는 배경 클릭에 반응하지 않음\n- 세션 내 인라인 알림을 통일하고 트리거 메시지 아래에 고정\n- 세션 목록 스크롤 상태를 ViewModel이 보유하도록 변경, 복귀 시 잘못된 맨 위 점프 수정\n- AI 생성 중에는 수동 컨텍스트 압축 비활성화',
    },
  ];

  function renderReleases(releases) {
    const container = $('#changelogList');
    if (!container) return;
    container.innerHTML = releases.slice(0, 3).map((rel, i) => {
      const ver = rel.tag_name;
      const date = rel.published_at ? rel.published_at.slice(0, 10) : '';
      const items = releaseItemsForLang(rel.body || '');
      const delay = i === 0 ? '' : i === 1 ? ' d1' : ' d2';
      const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
      return `<div class="tl-item reveal${delay}"><div class="tl-dot"></div><div class="tl-card"><div class="tl-head"><b>${esc(ver)}</b><time>${esc(date)}</time></div><ul>${items.map((item) => `<li>${esc(item)}</li>`).join('')}</ul></div></div>`;
    }).join('');
    container.querySelectorAll('.reveal').forEach((el) => revealIO.observe(el));
  }

  (async () => {
    try {
      const r = await fetch('https://api.github.com/repos/asukaneko/Nekobot-Android/releases?per_page=3', {
        headers: { Accept: 'application/vnd.github.v3+json' },
        signal: AbortSignal.timeout(5000),
      });
      if (!r.ok) throw new Error(String(r.status));
      lastReleases = await r.json();
    } catch {
      lastReleases = FALLBACK_RELEASES;
    }
    renderReleases(lastReleases);
  })();

})();
