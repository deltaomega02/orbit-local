/**
 * 화면 상태와 렌더링. 루트 탭 4개와 상세 화면 2개를 스택 라우터로 관리한다.
 * alert·confirm 은 쓰지 않고, 오래 걸리는 버튼은 누르는 즉시 잠근다.
 */
(function () {
  'use strict';

  var api = window.OrbitApi;

  // --- 유틸
  function $(sel, root) { return (root || document).querySelector(sel); }
  function $$(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }

  function esc(v) {
    return String(v == null ? '' : v)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function show(el, on) { if (el) el.hidden = !on; }
  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }

  // --- IME
  // 일본어 변환 확정 Enter 가 한 줄 입력칸에서 폼 제출로 새지 않게 한다.
  // 조합을 끝내는 Enter 에서 isComposing 이 false 인 브라우저가 있어 keyCode 229 와 composition 상태도 본다.
  function isComposingEvent(e, el) {
    return !!(e.isComposing || e.keyCode === 229 || (el && el.dataset.composing === '1'));
  }

  /** 조합 중 상태를 요소에 표시해 둔다. keydown 이 그것을 읽는다. */
  function trackComposition(root) {
    root.addEventListener('compositionstart', function (e) {
      if (e.target && e.target.dataset) e.target.dataset.composing = '1';
    }, true);
    root.addEventListener('compositionend', function (e) {
      if (e.target && e.target.dataset) delete e.target.dataset.composing;
    }, true);
  }
  trackComposition(document);

  /** 변환 확정 Enter 의 폼 제출을 막는다. textarea 는 제출되지 않으므로 대상이 아니다. */
  function guardImeSubmit(form) {
    if (!form) return;
    form.addEventListener('keydown', function (e) {
      if (e.key !== 'Enter') return;
      var el = e.target;
      if (!el || el.tagName !== 'INPUT') return;
      if (isComposingEvent(e, el)) e.preventDefault();
    });
  }

  /** 방금 펼친 요소를 화면 안으로 스크롤한다. 탭바에 가리지 않을 여백은 CSS scroll-margin-bottom 이 맡는다. */
  function scrollIntoViewSafely(el) {
    if (!el || el.hidden) return;
    requestAnimationFrame(function () {
      try {
        el.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
      } catch (e) {
        el.scrollIntoView(false);
      }
    });
  }

  /** 스프라이트 아이콘. 굵기·크기는 CSS(--ico-size, --ico-stroke)가 정한다. */
  function icon(name, cls) {
    return '<svg class="ico' + (cls ? ' ' + cls : '') + '" aria-hidden="true">' +
      '<use href="#i-' + name + '" /></svg>';
  }

  /** 버튼을 "처리 중" 상태로. 되돌리는 함수를 반환한다. */
  function busy(el, labelWhileBusy) {
    if (!el) return function () {};
    var textEl = $('.btn__text', el) || $('.action__title', el);
    var original = textEl ? textEl.textContent : null;
    el.disabled = true;
    el.classList.add('is-busy');
    el.setAttribute('aria-busy', 'true');
    if (textEl && labelWhileBusy) textEl.textContent = labelWhileBusy;
    return function done() {
      el.disabled = false;
      el.classList.remove('is-busy');
      el.removeAttribute('aria-busy');
      if (textEl && original !== null) textEl.textContent = original;
    };
  }

  function setNote(el, message, msgEl) {
    if (!el) return;
    if (message) {
      (msgEl || el).textContent = message;
      el.hidden = false;
    } else {
      el.hidden = true;
    }
  }

  // --- 토스트
  var toastHost = $('#toasts');

  /** showModal() 한 dialog 는 top layer 라 z-index 로 못 넘는다. 열린 동안 토스트를 시트 안으로 옮긴다. */
  function relocateToasts(target) {
    var host = target || document.body;
    if (toastHost.parentNode !== host) host.appendChild(toastHost);
  }

  function toast(message, kind) {
    if (!message) return;
    var t = document.createElement('div');
    t.className = 'toast' + (kind ? ' toast--' + kind : '');
    t.setAttribute('role', kind === 'error' ? 'alert' : 'status');
    t.textContent = message;
    toastHost.appendChild(t);
    requestAnimationFrame(function () { t.classList.add('is-in'); });
    setTimeout(function () {
      t.classList.remove('is-in');
      setTimeout(function () { t.remove(); }, 280);
    }, kind === 'error' ? 4200 : 2600);
  }

  // --- 오류 문구
  // 화면 오류 문구는 모두 여기서 만든다. 서버 detail 은 쓰지 않고 error 코드만 읽는다.
  function humanError(err) {
    if (!err) return '原因のわからない問題が起きました。';
    if (err.name === 'AbortError') return null;
    if (!err.isApiError) return '問題が起きました。しばらくしてからもう一度お試しください。';

    switch (err.code) {
      case 'not_enough_clothes': return 'トップスとボトムスを最低一着ずつ登録してください。';
      case 'no_body_photo': return '試着には全身写真が必要です。';
      case 'no_session':
      case 'session_expired': return 'セッションを続けられませんでした。しばらくしてからもう一度お試しください。';
      case 'network': return 'サーバーに接続できません。Orbit が起動しているか確認してください。';
      case 'clothes_in_use': return 'コーデに使われているため、今は削除できません。';
      case 'invalid_key': return 'キーが正しくありません。コピーした文字をもう一度確認してください。';
      // AI 가 상의·하의 조합을 못 만든 경우. 사용자가 고칠 것이 없어 재시도만 안내한다.
      case 'ai_invalid_response': return 'コーデを作れませんでした。しばらくしてからもう一度お試しください。';
      // 대부분 iPhone 기본 형식 HEIC. Java 디코더가 없고 브라우저도 못 그려서 형식명을 알려 준다.
      case 'unsupported_image_type':
        return 'この形式の写真は使えません。iPhone の HEIC 形式かもしれません。'
          + 'JPEG（.jpg）で保存し直してからお試しください。';
    }
    switch (err.status) {
      case 0: return 'サーバーに接続できません。Orbit が起動しているか確認してください。';
      case 400: return '入力した内容をもう一度確認してください。';
      case 401: return 'セッションを続けられませんでした。しばらくしてからもう一度お試しください。';
      case 403: return '権限がありません。';
      case 404: return '見つかりませんでした。再読み込みしてからもう一度お試しください。';
      // 서버에 크기 상한이 없으므로 413 은 사진이 아니거나 잘못된 파일이다.
      case 413: return 'この写真は読み込めませんでした。別の写真でお試しください。';
      case 502: return 'コーデを作れませんでした。しばらくしてからもう一度お試しください。';
      case 503: return 'AI が応答していません。しばらくしてからもう一度お試しください。';
    }
    return '問題が起きました。しばらくしてからもう一度お試しください。';
  }

  function isExpired(err) { return err && err.isApiError && err.code === 'session_expired'; }

  // --- 카테고리
  /** initial 은 사진 없는 옷의 바닥판에 찍히는 글자. 축약어(BTM) 대신 실제 단어를 쓴다. */
  var CATEGORY = {
    TOP:    { label: 'トップス', en: 'Top',    initial: 'TOP' },
    BOTTOM: { label: 'ボトムス', en: 'Bottom', initial: 'BOTTOM' },
    OUTER:  { label: 'アウター', en: 'Outer',  initial: 'OUTER' }
  };
  function catOf(key) { return CATEGORY[key] || { label: 'その他', en: 'Item', initial: 'ITEM' }; }

  // --- 번호 · 날짜
  /** 표시용 번호는 서버가 계정별로 매긴 lookNo 를 쓴다. id 는 전역 시퀀스라 링크와 API 에만 쓴다. */
  function lookLabel(c) {
    var n = c && c.lookNo;
    if (typeof n !== 'number' || !isFinite(n)) return 'Look';   // 서버가 못 준 경우
    return 'Look ' + (n < 100 ? ('00' + n).slice(-3) : String(n));
  }
  /** 일본식 숫자 날짜 2026/08/13. 시각은 24시간제. */
  function dateOf(iso) {
    var d = new Date(iso);
    if (isNaN(d)) return '';
    return d.getFullYear() + '/' + String(d.getMonth() + 1).padStart(2, '0') +
           '/' + String(d.getDate()).padStart(2, '0');
  }
  function timeOf(iso) {
    var d = new Date(iso);
    if (isNaN(d)) return '';
    return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
  }
  function isToday(iso) {
    var d = new Date(iso);
    if (isNaN(d)) return false;
    var t = new Date();
    return d.getFullYear() === t.getFullYear() && d.getMonth() === t.getMonth() && d.getDate() === t.getDate();
  }
  function stampOf(iso) { return isToday(iso) ? dateOf(iso) + ' ' + timeOf(iso) : dateOf(iso); }

  // --- 이미지
  /**
   * data-src 이미지를 인증된 blob URL 로 채운다. <img src> 로는 토큰을 못 보내기 때문.
   * innerHTML 로 그린 직후 호출한다.
   */
  function hydrateImages(root) {
    $$('img[data-src]', root).forEach(function (img) {
      var src = img.getAttribute('data-src');
      img.removeAttribute('data-src');
      api.media.objectUrl(src).then(function (url) {
        img.addEventListener('load', function () { img.classList.add('is-loaded'); }, { once: true });
        img.src = url;
        if (img.complete && img.naturalWidth) img.classList.add('is-loaded');
      }).catch(function () {
        img.remove(); // 뒤에 이니셜 바닥이 남는다
      });
    });
  }

  // 깨진 이미지 제거. error 는 버블링하지 않아 캡처로 받는다.
  document.addEventListener('error', function (e) {
    var t = e.target;
    if (t && t.tagName === 'IMG' && t.closest('.frame, .strip__cell')) t.remove();
  }, true);

  /** 화면에서 사라지는 로컬 미리보기 URL 을 되돌려 준다. */
  var localUrls = [];
  function localPreview(file) {
    var url = URL.createObjectURL(file);
    localUrls.push(url);
    return url;
  }
  function releaseLocalPreviews() {
    localUrls.splice(0).forEach(function (u) { URL.revokeObjectURL(u); });
  }

  function imgTag(url, alt) {
    return '<img data-src="' + esc(url) + '" alt="' + esc(alt || '') + '" decoding="async" />';
  }

  /** 옷 사진 프레임. 사진이 없으면 카테고리 이니셜이 남는다. */
  function itemFrameHtml(item, cls) {
    var c = catOf(item && item.mainCategory);
    return '<span class="frame ' + (cls || 'frame--item') + '">' +
      '<span class="frame__initial" aria-hidden="true">' + esc(c.initial) + '</span>' +
      (item && item.imageUrl ? imgTag(item.imageUrl, '') : '') +
    '</span>';
  }

  function sortedItems(c) {
    return ((c && c.items) || []).slice().sort(function (a, b) {
      return (a.layerOrder || 0) - (b.layerOrder || 0);
    });
  }

  /** 코디 프레임 3:4. 착용 사진이 없으면 구성 옷 사진 스트립으로 대신한다. */
  function tryOnFrameHtml(c, extra) {
    return '<span class="frame frame--look"' + (extra || '') + '>' +
      imgTag(c.tryOnImageUrl, 'AI が作った試着姿') + '</span>';
  }

  /** 원본 옷 사진 스트립. 착용 사진이 있어도 따로 보여 준다. */
  function itemsFrameHtml(c, extra) {
    var items = sortedItems(c).slice(0, 3);
    if (!items.length) {
      return '<span class="frame frame--look"' + (extra || '') +
        '><span class="frame__initial" aria-hidden="true">No Photo</span></span>';
    }
    return '<span class="frame frame--look"' + (extra || '') + '><span class="strip">' +
      items.map(function (it) {
        var cat = catOf(it.mainCategory);
        return '<span class="strip__cell">' +
          '<span class="frame__initial" aria-hidden="true">' + esc(cat.initial) + '</span>' +
          (it.imageUrl ? imgTag(it.imageUrl, '') : '') +
        '</span>';
      }).join('') +
    '</span></span>';
  }

  function lookFrameHtml(c) {
    return c.tryOnImageUrl ? tryOnFrameHtml(c) : itemsFrameHtml(c);
  }

  /** 사진 위 pill. 착용 사진이 실제 모습으로 오해되지 않게 AI 생성 이미지임을 표시한다. */
  function aiPillHtml(c) {
    return '<span class="pill" data-ai-pill><span class="pill__dot" aria-hidden="true"></span>' +
      (c && c.tryOnImageUrl ? 'AI生成画像' : 'AI提案') + '</span>';
  }

  function favBtnHtml(c) {
    return '<button class="photobtn' + (c.favorite ? ' is-on' : '') + '" type="button" ' +
      'data-fav="' + esc(c.id) + '" aria-pressed="' + (c.favorite ? 'true' : 'false') + '" ' +
      'aria-label="お気に入り">' + icon(c.favorite ? 'heart-fill' : 'heart') + '</button>';
  }

  /** 그날 적어 둔 한 줄. 없으면 빈 문자열. */
  function situationOf(c) {
    var s = c && c.situation;
    return (typeof s === 'string' && s.trim()) ? s.trim() : '';
  }

  /** LOOK 카드 */
  function lookHtml(c) {
    var items = sortedItems(c);
    var title = c.title || '今日のコーデ';
    var situation = situationOf(c);
    return '<article class="look" data-id="' + esc(c.id) + '">' +
      '<div class="look__head">' +
        '<span class="indexlabel">' + esc(lookLabel(c)) + '</span>' +
        '<span class="num">' + esc(stampOf(c.createdAt)) + '</span>' +
      '</div>' +
      '<div class="look__figure">' +
        '<button class="look__open" type="button" data-coord-open="' + esc(c.id) + '" ' +
                'data-coord-title="' + esc(title) + '" aria-label="' + esc(title) + ' の詳細を見る">' +
          lookFrameHtml(c) + aiPillHtml(c) +
        '</button>' +
        favBtnHtml(c) +
      '</div>' +
      '<h3 class="look__title">' + esc(title) + '</h3>' +
      '<p class="look__items">' + esc(items.map(function (i) { return i.name; }).join(' · ')) + '</p>' +
      (situation ? '<p class="look__note">' + esc(situation) + '</p>' : '') +
      // 좁은 2열 카드에서 머리의 날짜 대신 표시되는 줄
      '<p class="look__stamp num">' + esc(stampOf(c.createdAt)) + '</p>' +
    '</article>';
  }

  /** 축소판 LOOK 카드. 옷 상세의 "이 옷이 쓰인 코디" 가로 스크롤에서만 쓴다. */
  function miniLookHtml(c) {
    var title = c.title || '今日のコーデ';
    return '<button class="minilook" type="button" data-coord-open="' + esc(c.id) + '" ' +
            'data-coord-title="' + esc(title) + '">' +
      lookFrameHtml(c) +
      '<span class="indexlabel minilook__no">' + esc(lookLabel(c)) + '</span>' +
      '<span class="minilook__title">' + esc(title) + '</span>' +
      '<span class="num minilook__date">' + esc(dateOf(c.createdAt)) + '</span>' +
    '</button>';
  }

  // --- 상태
  var state = {
    user: null,
    // configured 는 서버 응답값이라 화면에서 덮어쓰지 않는다. 키 재입력 중인지는 editing 으로 따로 둔다.
    ai: { configured: false, masked: null, checked: false, editing: false },

    closet: { items: [], page: 0, totalPages: 0, totalElements: 0, filter: 'ALL', loaded: false },
    home: { today: [], recent: [], loaded: false, lookCount: 0 },
    history: { items: [], page: 0, totalPages: 0, totalElements: 0, filter: 'ALL', loaded: false },
    coord: { id: null, data: null, media: 'tryon' },
    item: { id: null, data: null, usedIn: [], usedInUnavailable: false },
    stats: { data: null, loaded: false },

    /** 입어보기에서 전신 사진 등록으로 보낼 때 돌아올 자리. */
    pendingTryOn: null,

    /**
     * 추천 직후 자동 생성이 못 끝난 이유. 코디 상세의 입어보기 자리에 그대로 실린다.
     * { id, kind:'guide'|'error', message, action?, actionLabel? }
     */
    tryOnNote: null,

    addImage: null,
    analyzeAbort: null,
    /** 옷 정보 수정 중이면 그 옷의 id. null 이면 새 옷 등록이다. */
    editItemId: null
  };

  // --- 라우터
  var VIEWS = {
    home:    { el: '#view-home',    root: true,  title: 'ホーム' },
    closet:  { el: '#view-closet',  root: true,  title: 'クローゼット' },
    history: { el: '#view-history', root: true,  title: 'コーデの記録' },
    more:    { el: '#view-more',    root: true,  title: 'その他' },
    coord:   { el: '#view-coord',   root: false, title: 'コーデ' },
    item:    { el: '#view-item',    root: false, title: 'アイテム' }
  };

  var stack = [{ name: 'home', params: {}, scroll: 0 }];

  function current() { return stack[stack.length - 1]; }

  /** 현재 화면을 해시로 표현한다. 서버 라우팅 설정 없이 새로고침·즐겨찾기가 동작한다. */
  function hashOf(route) {
    switch (route.name) {
      case 'closet':  return '#/closet';
      case 'history': return '#/history';
      case 'more':    return '#/more';
      case 'coord':   return '#/look/' + encodeURIComponent(route.params.id);
      case 'item':    return '#/item/' + encodeURIComponent(route.params.id);
      default:        return '#/';
    }
  }

  /** 모르는 주소는 null. 부르는 쪽이 홈으로 되돌린다. */
  function parseHash(hash) {
    var seg = String(hash || '').replace(/^#\/?/, '').split('/').filter(Boolean);
    if (!seg.length) return { name: 'home', params: {} };
    if (seg[0] === 'look' && seg[1]) return { name: 'coord', params: { id: decodeURIComponent(seg[1]) } };
    if (seg[0] === 'item' && seg[1]) return { name: 'item', params: { id: decodeURIComponent(seg[1]) } };
    if (VIEWS[seg[0]] && VIEWS[seg[0]].root) return { name: seg[0], params: {} };
    return null;
  }

  function sameRoute(a, b) {
    return !!a && !!b && a.name === b.name &&
      String((a.params || {}).id || '') === String((b.params || {}).id || '');
  }

  /** 주소로 곧장 들어온 상세 화면도 뒤로가면 홈으로 나갈 수 있어야 한다. */
  function stackFor(target) {
    var route = { name: target.name, params: target.params || {}, scroll: 0 };
    if (VIEWS[route.name].root) return [route];
    return [{ name: 'home', params: {}, scroll: 0 }, route];
  }

  /**
   * 이 앱이 쌓은 history 위치. 0 이면 주소로 상세 화면에 곧장 들어온 경우라
   * history.back() 이 앱 밖으로 나가므로 뒤로가기를 직접 처리한다.
   */
  var histIndex = 0;
  var histSeq = 0;

  /** 지금 스택을 주소창에 반영한다. 같은 주소면 기록을 새로 쌓지 않는다. */
  function syncUrl(replace) {
    if (!window.history || !window.history.pushState) return;
    var h = hashOf(current());
    try {
      if (replace || window.location.hash === h) {
        window.history.replaceState({ idx: histIndex, depth: stack.length }, '', h);
      } else {
        histIndex = ++histSeq;
        window.history.pushState({ idx: histIndex, depth: stack.length }, '', h);
      }
    } catch (e) { /* noop */ }
  }

  function navigate(name, params, opts) {
    opts = opts || {};
    var route = { name: name, params: params || {}, scroll: 0 };

    if (VIEWS[name].root) {
      stack = [route];   // 같은 탭을 다시 누르면 그 탭의 뿌리로 돌아간다
    } else {
      current().scroll = window.scrollY;
      stack.push(route);
    }

    if (!opts.fromHistory) syncUrl(opts.replaceUrl);
    applyRoute(opts);
  }

  function back() {
    // 앱이 쌓은 기록이 있으면 브라우저에 맡긴다 (popstate 가 스택을 맞춘다).
    if (histIndex > 0 && window.history && window.history.back) {
      window.history.back();
      return;
    }
    // 주소로 곧장 들어온 경우. 스택만 내리고 주소를 덮어쓴다.
    if (stack.length > 1) {
      stack.pop();
      syncUrl(true);
      applyRoute({ restoreScroll: true });
      return;
    }
    if (current().name !== 'home') navigate('home');
  }

  /** 뒤로·앞으로·주소 수정 모두 pop 대신 주소가 가리키는 화면에 스택을 맞춘다. */
  window.addEventListener('popstate', function () {
    var st = window.history.state;
    histIndex = (st && typeof st.idx === 'number') ? st.idx : 0;

    var target = parseHash(window.location.hash);
    if (!target) { syncUrl(true); return; }

    var idx = -1;
    for (var i = stack.length - 1; i >= 0; i--) {
      if (sameRoute(stack[i], target)) { idx = i; break; }
    }
    stack = (idx >= 0) ? stack.slice(0, idx + 1) : stackFor(target);

    applyRoute({ restoreScroll: true, fromHistory: true });
  });

  function applyRoute(opts) {
    opts = opts || {};
    var route = current();
    var def = VIEWS[route.name];

    releaseLocalPreviews();

    Object.keys(VIEWS).forEach(function (k) {
      var el = $(VIEWS[k].el);
      var on = k === route.name;
      show(el, on);
      el.classList.remove('is-entering');
      if (on) {
        void el.offsetWidth;  // 애니메이션 재시작
        el.classList.add('is-entering');
      }
    });

    $$('.tabbar__item').forEach(function (b) {
      var active = b.dataset.nav === route.name;
      b.classList.toggle('is-active', active);
      if (active) b.setAttribute('aria-current', 'page');
      else b.removeAttribute('aria-current');
    });

    var isRoot = def.root;
    show($('#topbar-brand'), isRoot);
    show($('#topbar-title'), !isRoot);
    show($('#btn-back'), !isRoot);
    $('#topbar').classList.toggle('is-detail', !isRoot);
    if (!isRoot) $('#topbar-title').textContent = route.params.title || def.title;
    show($('#btn-topbar-action'), false);

    window.scrollTo(0, opts.restoreScroll ? (route.scroll || 0) : 0);
    enterView(route);
  }

  function enterView(route) {
    switch (route.name) {
      case 'home':    onHomeEnter(); break;
      case 'closet':  onClosetEnter(); break;
      case 'history': onHistoryEnter(); break;
      case 'more':    onMoreEnter(); break;
      case 'coord':   onCoordEnter(route.params.id); break;
      case 'item':    onItemEnter(route.params.id); break;
    }
  }

  $('#btn-back').addEventListener('click', function () { back(); });

  $$('.tabbar__item').forEach(function (b) {
    b.addEventListener('click', function () {
      var target = b.dataset.nav;
      if (target === 'add') { openAddSheet(); return; }
      navigate(target);
    });
  });

  // --- 셸 전환 (앱 / 부팅 화면. 세션은 api.js 가 받는다)
  function showApp() {
    show($('#app-shell'), true);
    show($('#boot'), false);
    // 주소가 가리키는 화면에서 시작한다.
    stack = stackFor(parseHash(window.location.hash) || { name: 'home', params: {} });
    syncUrl(true);
    applyRoute();
    loadAiState();
  }

  /** 세션을 못 받았을 때. 원인 한 줄과 재시도 버튼만 보여 준다. */
  function showBootError(message) {
    show($('#app-shell'), false);
    show($('#boot'), true);
    $('#boot-label').textContent = 'Offline';
    $('#boot-label').style.animation = 'none';
    setNote($('#boot-msg'), message || humanError(null));
    show($('#btn-boot-retry'), true);
  }

  function showBootLoading() {
    $('#boot-label').textContent = 'Loading';
    $('#boot-label').style.animation = '';
    show($('#boot-msg'), false);
    show($('#btn-boot-retry'), false);
    show($('#boot'), true);
  }

  api.onSessionExpired(function () {
    showBootError('セッションを続けられませんでした。しばらくしてからもう一度お試しください。');
  });

  // --- AI 연결 (Gemini 키)
  function loadAiState() {
    return api.settings.geminiKey().then(function (res) {
      state.ai.configured = !!(res && res.configured);
      state.ai.masked = res && res.masked;
      state.ai.checked = true;
      // 서버 응답을 받으면 편집 모드를 해제한다.
      state.ai.editing = false;
      renderAiState();
    }).catch(function (err) {
      if (isExpired(err)) return;
      state.ai.checked = false;
      renderAiState();
    });
  }

  function renderAiState() {
    var connected = state.ai.configured;
    // 연결돼 있어도 키 재입력 중이면 입력 폼을 보여 준다. configured 는 true 그대로다.
    var editing = connected && state.ai.editing;

    show($('#key-connected'), connected && !editing);
    show($('#key-setup'), !connected || editing);
    if (connected) $('#key-masked').textContent = state.ai.masked || '';

    // 취소는 이미 연결돼 있을 때만 보인다.
    show($('#btn-key-cancel'), editing);
    var submitText = $('.btn__text', $('#form-key button[type=submit]'));
    if (submitText) submitText.textContent = editing ? '新しいキーに変更' : '連携する';

    // 해제 확인은 패널을 다시 그릴 때마다 접어 둔다.
    showKeyRemoveConfirm(false);

    // 홈 문구는 편집 모드와 무관하게 서버 응답(configured)만 따른다.
    var sub = $('#recommend-sub');
    if (sub) {
      // 미연결 안내가 우선이고, 그 외에는 오늘 상황 문구를 쓴다.
      if (state.ai.checked && !state.ai.configured) sub.textContent = 'AI連携を済ませるとすぐ使えます';
      else syncSituation();
    }
  }

  function showKeyRemoveConfirm(on) {
    show($('#key-remove-confirm'), on);
    show($('#btn-key-ask-remove'), !on);
  }

  var guideSheet = $('#guide-sheet');

  /** AI 미연결 안내 시트. 필요한 것을 알려 주고 설정 화면으로 보낸다. */
  function openGuide(opts) {
    opts = opts || {};
    $('#guide-title').textContent = opts.title || 'AI連携がまだ済んでいません';
    $('#guide-desc').textContent = opts.desc ||
      'コーデを選ぶには、Google AI Studio で発行する無料のキーが一つ必要です。「その他」の画面で1分ほどで終わります。';
    $('.btn__text', $('#btn-guide-go')).textContent = opts.action || '連携しに行く';
    guideSheet.__go = opts.go || function () { navigate('more'); focusKeyPanel(); };
    openSheet(guideSheet);
  }

  $('#btn-guide-go').addEventListener('click', function () {
    var go = guideSheet.__go;
    closeSheet(guideSheet);
    if (go) go();
  });
  $('#btn-guide-later').addEventListener('click', function () { closeSheet(guideSheet); });

  /** AI 가 필요한 행동 앞에서 부른다. 준비가 안 됐으면 안내를 띄우고 false. */
  function requireAi() {
    if (state.ai.configured || !state.ai.checked) return true;
    openGuide();
    return false;
  }

  function focusKeyPanel() {
    var panel = $('#panel-key');
    if (!panel) return;
    panel.classList.add('is-highlight');
    setTimeout(function () { panel.classList.remove('is-highlight'); }, 2400);
    setTimeout(function () {
      panel.scrollIntoView({ block: 'start', behavior: 'smooth' });
      var input = $('#key-input');
      if (input && !$('#key-setup').hidden) setTimeout(function () { input.focus(); }, 320);
    }, 60);
  }

  // --- 홈
  function onHomeEnter() {
    if (!state.home.loaded) loadHome();
    else renderHome();
  }

  function loadHome() {
    show($('#home-skeleton'), true);
    show($('#home-body'), false);
    setNote($('#home-error'), '', $('#home-error-msg'));

    return Promise.all([
      api.coordinations.today().catch(function (err) {
        if (isExpired(err)) throw err;
        return [];
      }),
      api.coordinations.list({ page: 0, size: 4 }).catch(function (err) {
        if (isExpired(err)) throw err;
        return { content: [] };
      }),
      // 옷장이 비었는지 확인용
      api.clothes.list({ page: 0, size: 1 }).catch(function () { return null; }),
      // 홈 색인용. 없으면 색인 줄만 접힌다.
      loadStats().catch(function () { return null; })
    ]).then(function (res) {
      var today = (res[0] || []).slice().sort(function (a, b) {
        return new Date(b.createdAt) - new Date(a.createdAt);
      });
      state.home.today = today;

      var page = res[1] || {};
      var recent = page.content || [];
      // 누적 LOOK 수. 기록 API 가 없으면 오늘 것만 센다.
      state.home.lookCount = (page.totalElements != null && !page.__unavailable)
        ? page.totalElements
        : (recent.length || today.length);

      if (!recent.length) recent = today;   // 기록 API 가 아직 없으면 오늘 것으로 채운다
      state.home.recent = recent.filter(function (c) {
        return !today.length || String(c.id) !== String(today[0].id);
      }).slice(0, 4);

      if (res[2]) state.closet.totalElements = res[2].totalElements || 0;
      state.home.loaded = true;
      renderHome();
    }).catch(function (err) {
      if (isExpired(err)) return;
      state.home.loaded = true;
      renderHome();
      setNote($('#home-error'), humanError(err), $('#home-error-msg'));
    }).finally(function () {
      show($('#home-skeleton'), false);
      show($('#home-body'), true);
    });
  }

  function renderHome() {
    var today = state.home.today;
    var closetEmpty = state.closet.loaded
      ? state.closet.items.length === 0
      : state.closet.totalElements === 0;

    show($('#home-today-label'), today.length === 0);
    $('#home-today').innerHTML = today.length
      ? lookHtml(today[0])
      : '<div class="empty empty--inline">' +
          '<h2 class="empty__title">今日の記録はまだありません</h2>' +
          '<p class="empty__desc">' + (closetEmpty
            ? '先に服を登録すると、今日の組み合わせを作れます。'
            : 'コーデを選んでもらうと、今日の組み合わせがここに残ります。') + '</p>' +
        '</div>';
    hydrateImages($('#home-today'));

    show($('#home-onboard'), closetEmpty);
    show($('#btn-recommend'), !closetEmpty);

    var recent = state.home.recent;
    show($('#home-recent-section'), recent.length > 0);
    if (recent.length) {
      $('#home-recent').innerHTML = recent.map(lookHtml).join('');
      hydrateImages($('#home-recent'));
    }
    renderHomeIndex();
    renderAiState();
  }

  /** 홈의 옷장 색인 (LOOK 수, 옷 수, 안 입은 옷 수). */
  function renderHomeIndex() {
    var s = state.stats.data;
    var looks = state.home.lookCount || 0;
    var total = (s && s.total != null) ? s.total : state.closet.totalElements;
    var never = (s && s.neverUsed != null) ? s.neverUsed : null;

    // 전부 0 이면 숨긴다.
    var meaningful = looks > 0 || (total || 0) > 0;
    show($('#home-index-section'), meaningful);
    if (!meaningful) return;

    // 조수사: 기록은 件, 옷은 着
    var cells = [
      { label: 'Looks', value: looks, unit: '件', to: 'history' },
      { label: 'Wardrobe', value: total || 0, unit: '着', to: 'closet' }
    ];
    if (never != null) cells.push({ label: 'Never worn', value: never, unit: '着', to: 'closet' });

    $('#home-index').innerHTML = cells.map(function (c) {
      return '<button class="statcell" type="button" data-index-go="' + esc(c.to) + '">' +
        '<span class="statcell__label indexlabel">' + esc(c.label) + '</span>' +
        '<span class="statcell__value num">' + esc(c.value) +
          '<span class="statcell__unit">' + esc(c.unit) + '</span></span>' +
      '</button>';
    }).join('');
  }

  $('#home-index').addEventListener('click', function (e) {
    var cell = e.target.closest('[data-index-go]');
    if (cell) navigate(cell.dataset.indexGo);
  });

  $('#btn-see-all').addEventListener('click', function () { navigate('history'); });
  $('#btn-history-go-home').addEventListener('click', function () { navigate('home'); });

  /** LOOK 카드는 홈·기록·옷 상세에서 함께 쓴다. 클릭 처리도 한 곳에서 한다. */
  function bindLookList(root) {
    root.addEventListener('click', function (e) {
      var fav = e.target.closest('[data-fav]');
      if (fav) { toggleFavorite(fav.dataset.fav); return; }
      var open = e.target.closest('[data-coord-open]');
      if (open) openCoord(open.dataset.coordOpen, open.dataset.coordTitle);
    });
  }
  bindLookList($('#home-today'));
  bindLookList($('#home-recent'));
  bindLookList($('#history-list'));

  // --- 오늘 상황
  // 이번 추천에만 쓰는 선택 입력. 늘 반영되는 설정의 스타일 선호도와는 별개다.
  var LAST_SITUATION_KEY = 'orbit.situation.last';
  var situationInput = $('#situation-input');

  function situationValue() {
    return situationInput ? situationInput.value.trim().slice(0, 100) : '';
  }

  /** 입력을 쉼표로 나눈 목록. 일본어 입력의 읽점 、 과 전각 ， 도 구분자로 본다. */
  var SEPARATOR = /[,、，]/;

  function situationParts() {
    return situationValue().split(SEPARATOR).map(function (s) { return s.trim(); })
      .filter(function (s) { return s.length > 0; });
  }

  function syncSituation() {
    var parts = situationParts();
    var value = situationValue();
    $$('#situation-chips [data-situation]').forEach(function (chip) {
      var on = parts.indexOf(chip.dataset.situation) >= 0;
      chip.classList.toggle('is-active', on);
      chip.setAttribute('aria-pressed', on ? 'true' : 'false');
    });
    var count = $('#situation-count');
    if (count) count.textContent = String(value.length);
    show($('#btn-situation-clear'), value.length > 0);

    // 추천 버튼 아래에 반영할 상황을 표시한다.
    var sub = $('#recommend-sub');
    if (sub && state.ai.checked && !state.ai.configured) return;
    if (sub) {
      sub.textContent = value
        ? '「' + value + '」を反映して選びます'
        : 'クローゼットを見て今日の組み合わせを選びます';
    }
  }

  function setSituation(value) {
    if (!situationInput) return;
    situationInput.value = String(value || '').slice(0, 100);
    syncSituation();
  }

  /** 지난번 입력은 기본값이 아니라 placeholder 힌트로만 쓴다. */
  function rememberSituation(value) {
    try {
      if (value) window.localStorage.setItem(LAST_SITUATION_KEY, value);
      else window.localStorage.removeItem(LAST_SITUATION_KEY);
    } catch (e) { /* 저장 실패는 무시 */ }
    applySituationHint();
  }

  function applySituationHint() {
    if (!situationInput) return;
    var last = null;
    try { last = window.localStorage.getItem(LAST_SITUATION_KEY); } catch (e) { last = null; }
    situationInput.placeholder = last ? '前回は「' + last + '」' : '例）雨で肌寒い';
  }

  if (situationInput) {
    situationInput.addEventListener('input', syncSituation);
    applySituationHint();
    syncSituation();

    $('#situation-chips').addEventListener('click', function (e) {
      var chip = e.target.closest('[data-situation]');
      if (!chip) return;
      var word = chip.dataset.situation;
      var parts = situationParts();
      var at = parts.indexOf(word);
      if (at >= 0) parts.splice(at, 1);
      else parts.push(word);
      // 이어 붙일 때는 일본어 읽점을 쓴다.
      setSituation(parts.join('、'));
      situationInput.focus();
    });

    $('#btn-situation-clear').addEventListener('click', function () {
      setSituation('');
      situationInput.focus();
    });

    // 폼이 아니라서 Enter 로 추천을 직접 실행한다. IME 변환 확정 Enter 는 제외.
    situationInput.addEventListener('keydown', function (e) {
      if (e.key !== 'Enter') return;
      if (isComposingEvent(e, situationInput)) return;
      e.preventDefault();
      var btn = $('#btn-recommend');
      if (btn && !btn.disabled && !btn.hidden) btn.click();
    });
  }

  // --- 추천받기 (409 자동 재시도)
  var MAX_DUP_RETRY = 3;
  /** 이보다 오래 걸리면 지연 안내를 띄운다. */
  var SLOW_HINT_MS = 6000;

  var recSlowTimer = null;
  var recAttempt = 0;
  var recProgress = null;

  /** 추천 대기 단계 문구. 입어보기와 같은 진행 바를 쓴다. */
  var REC_STAGES = [
    [0,  'クローゼットを開いています…'],
    [22, '今日着られそうな服を絞り込んでいます…'],
    [50, '色と素材を合わせています…'],
    [75, '選んだ理由を書いています…'],
    [92, 'もうすぐです…']
  ];

  function recStatus(msg) {
    $('#home-retry-msg').textContent = msg;
    show($('#home-retry'), true);
  }

  function recArmSlowHint() {
    clearTimeout(recSlowTimer);
    recSlowTimer = setTimeout(function () {
      recStatus('思ったより時間がかかっています。もう少しお待ちください。');
    }, SLOW_HINT_MS);
  }

  /** 2단계: 고른 조합의 착용 이미지 생성 중 (20~30초). */
  var REC_TRYON_STAGES = [
    [0,  '着た姿を作っています…'],
    [20, '全身写真を確認しています…'],
    [45, '服を一つずつ着せています…'],
    [72, '仕上げています…もう少しお待ちください'],
    [92, 'もうすぐです…']
  ];

  function recStart() {
    recStop();
    show($('#home-progress'), true);
    recProgress = startProgress($('#home-progress-fill'), $('#home-progress-label'), REC_STAGES);
    recArmSlowHint();
  }

  function recStartTryOn() {
    // 원래 오래 걸리는 단계라 지연 안내 대신 예상 시간을 보여 준다.
    clearTimeout(recSlowTimer);
    recSlowTimer = null;
    if (recProgress) recProgress.stop();
    show($('#home-progress'), true);
    recProgress = startProgress($('#home-progress-fill'), $('#home-progress-label'), REC_TRYON_STAGES);
    recStatus('着た姿を作るのに20〜30秒ほどかかります。');
  }

  function recStop() {
    clearTimeout(recSlowTimer);
    recSlowTimer = null;
    recAttempt = 0;
    if (recProgress) { recProgress.stop(); recProgress = null; }
    show($('#home-progress'), false);
    show($('#home-retry'), false);
  }

  $('#btn-recommend').addEventListener('click', function () {
    if (!requireAi()) return;

    var btn = this;
    var situation = situationValue();
    var done = busy(btn, '選んでいます…');
    setNote($('#home-error'), '', $('#home-error-msg'));
    show($('#home-error-action'), false);
    recStart();

    recommendWithRetry(situation, function (retry) {
      // 재시도 중임을 진행 바 문구로 알린다.
      recAttempt = retry.n;
      if (recProgress) {
        recProgress.hold(retry.kind === 'dup'
          ? '同じ組み合わせが出たので選び直しています…（' + retry.n + '/' + retry.max + '）'
          : 'うまく組み合わせられなかったので選び直しています…');
      }
      recArmSlowHint();
    }).then(function (created) {
      if (recProgress) recProgress.finish('選びました！');
      clearTimeout(recSlowTimer);
      show($('#home-retry'), false);
      // 오늘 상황은 한 번만 쓰고 비운다. 방금 쓴 말은 placeholder 힌트로 남긴다.
      setSituation('');
      rememberSituation(situation);
      state.history.loaded = false;
      state.stats.loaded = false;
      // 이어서 착용 이미지를 만든다.
      return autoTryOn(created).then(function (outcome) {
        // 오늘 목록은 로컬에서 합치지 않고 서버에서 다시 읽는다.
        return loadHome().then(function () {
          recStop();
          toast(outcome.message);
          if (created && created.id) openCoord(created.id, created.title);
        });
      });
    }).catch(function (err) {
      recStop();
      if (isExpired(err)) return;

      if (err && err.code === 'duplicate_exhausted') {
        homeError('今日出せる組み合わせは出尽くしました。服を追加すると新しい組み合わせが生まれます。', {
          label: '服を追加', run: function () { navigate('closet'); openAddSheet(); }
        });
        return;
      }
      if (err.isApiError && err.code === 'not_enough_clothes') {
        homeError(humanError(err), {
          label: '服を登録', run: function () { navigate('closet'); openAddSheet(); }
        });
        return;
      }
      if (err.isApiError && err.status === 503) {
        openGuide({
          title: 'AI が応答していません',
          desc: '連携したキーが有効か確認して、しばらくしてからもう一度お試しください。',
          action: '連携を確認しに行く'
        });
        return;
      }
      // 구성이 어긋난 AI 응답(502). 자동 재시도 후에도 실패했으니 재시도 버튼만 둔다.
      if (err.isApiError && (err.code === 'ai_invalid_response' || err.status === 502)) {
        homeError('コーデを作れませんでした。しばらくしてからもう一度お試しください。', {
          label: 'もう一度試す', run: function () { $('#btn-recommend').click(); }
        });
        return;
      }
      homeError(humanError(err));
    }).finally(done);
  });

  /**
   * 추천 직후 착용 이미지 생성. 요청을 나눠야 단계별 진행을 보여 주고, 이미지가 실패해도 추천이 남는다.
   * 유료 호출이라 자동 재시도하지 않는다. 추천까지 실패로 만들지 않도록 reject 하지 않는다.
   */
  function autoTryOn(created) {
    var id = created && created.id;
    if (!id) return Promise.resolve({ message: '今日のコーデを選びました。' });

    function guide() {
      // 전신 사진이 없다. 추천은 두고 등록 안내만 한다.
      state.tryOnNote = {
        id: id, kind: 'guide',
        message: '全身写真を登録すると、このコーデを着た姿を作れます。',
        action: 'go-body-photo', actionLabel: '全身写真を登録'
      };
      return { message: '今日のコーデを選びました。全身写真を登録すると着た姿も作れます。' };
    }
    function failed(msg) {
      state.tryOnNote = {
        id: id, kind: 'error',
        message: msg || '着た姿を作れませんでした。下のボタンでもう一度作れます。'
      };
      return { message: '今日のコーデを選びました。着た姿は作れませんでした。' };
    }

    if (!(state.user && state.user.bodyPhotoUrl)) return Promise.resolve(guide());

    recStartTryOn();
    return api.coordinations.tryOn(id).then(function (res) {
      var url = res && res.tryOnImageUrl;
      if (!url) return failed();
      state.tryOnNote = null;
      if (state.coord.data && String(state.coord.data.id) === String(id)) {
        state.coord.data.tryOnImageUrl = url;
      }
      if (recProgress) recProgress.finish('完成しました！');
      return { message: '今日のコーデと着た姿ができました。' };
    }).catch(function (err) {
      if (isExpired(err)) throw err;
      if (err && err.isApiError && err.code === 'no_body_photo') return guide();
      if (err && err.isApiError && err.status === 503) {
        return failed('AI が応答しないため、着た姿を作れませんでした。しばらくしてからもう一度お試しください。');
      }
      return failed();
    });
  }

  function homeError(msg, action) {
    setNote($('#home-error'), msg, $('#home-error-msg'));
    var btn = $('#home-error-action');
    if (action) {
      btn.textContent = action.label;
      btn.hidden = false;
      btn.onclick = action.run;
    } else {
      btn.hidden = true;
      btn.onclick = null;
    }
  }

  /**
   * 409 duplicate 는 최대 3회(1초 간격), 502 ai_invalid 는 유료 호출이라 1회만 재시도한다.
   * 카운터를 따로 둬야 409 로 몫을 다 쓴 뒤 온 502 도 재시도된다.
   */
  var MAX_INVALID_RETRY = 1;

  function recommendWithRetry(situation, onRetry) {
    var dup = 0;
    var invalid = 0;

    function attempt() {
      return api.coordinations.recommend(situation).catch(function (err) {
        if (!err.isApiError) throw err;

        if (err.status === 409) {
          if (dup >= MAX_DUP_RETRY) {
            var exhausted = new Error('duplicate_exhausted');
            exhausted.code = 'duplicate_exhausted';
            throw exhausted;
          }
          dup += 1;
          onRetry({ kind: 'dup', n: dup, max: MAX_DUP_RETRY });
          return sleep(1000).then(attempt);
        }

        if (err.status === 502 || err.code === 'ai_invalid_response') {
          if (invalid >= MAX_INVALID_RETRY) throw err;
          invalid += 1;
          onRetry({ kind: 'invalid', n: invalid, max: MAX_INVALID_RETRY });
          return sleep(600).then(attempt);
        }

        throw err;
      });
    }
    return attempt();
  }

  // --- 옷장
  var closetGrid = $('#closet-grid');

  function onClosetEnter() {
    if (!state.closet.loaded) loadClothes({ reset: true });
    else renderCloset();
  }

  function loadClothes(opts) {
    opts = opts || {};
    if (opts.reset) {
      state.closet.page = 0;
      state.closet.items = [];
      closetGrid.innerHTML = '';
    }

    var first = state.closet.page === 0;
    setNote($('#closet-error'), '', $('#closet-error-msg'));
    if (first) {
      show($('#closet-empty'), false);
      show($('#closet-empty-filter'), false);
      show($('#closet-more'), false);
      show($('#closet-count'), false);
      show($('#closet-skeleton'), true);
    }
    var done = first ? function () {} : busy($('#btn-load-more'), '読み込んでいます…');

    return api.clothes.list({
      page: state.closet.page,
      size: 20,
      mainCategory: state.closet.filter === 'ALL' ? null : state.closet.filter
    }).then(function (data) {
      state.closet.loaded = true;
      state.closet.totalPages = data.totalPages || 0;
      state.closet.totalElements = data.totalElements || 0;
      // 서버가 mainCategory 를 무시해도 visibleClothes() 가 한 번 더 거른다.
      state.closet.items = state.closet.items.concat(data.content || []);
      renderCloset();
    }).catch(function (err) {
      if (isExpired(err)) return;
      if (first) setNote($('#closet-error'), humanError(err), $('#closet-error-msg'));
      else toast(humanError(err), 'error');
    }).finally(function () {
      show($('#closet-skeleton'), false);
      done();
    });
  }

  function visibleClothes() {
    if (state.closet.filter === 'ALL') return state.closet.items;
    return state.closet.items.filter(function (c) { return c.mainCategory === state.closet.filter; });
  }

  function renderCloset() {
    var items = visibleClothes();
    var isEmptyAll = state.closet.items.length === 0 && state.closet.filter === 'ALL';
    var hasMore = state.closet.page + 1 < state.closet.totalPages;

    show($('#closet-empty'), isEmptyAll);
    show($('#closet-empty-filter'), !isEmptyAll && items.length === 0);
    show($('#closet-more'), hasMore);
    show($('#closet-count'), items.length > 0);

    if (items.length > 0) {
      // 개수 표시는 영문 라벨이 아니라 일본어로 쓴다.
      $('#closet-count').textContent = state.closet.filter === 'ALL'
        ? state.closet.totalElements + '着'
        : catOf(state.closet.filter).label + ' ' + items.length + '着';
    }

    closetGrid.innerHTML = items.map(clothesCardHtml).join('');
    hydrateImages(closetGrid);
  }

  function clothesCardHtml(item) {
    var c = catOf(item.mainCategory);
    var meta = [c.label, item.color].filter(Boolean).join(' · ');
    return '<button class="itemcard" type="button" data-clothes-id="' + esc(item.id) + '">' +
      itemFrameHtml(item, 'frame--item') +
      '<span class="itemcard__name">' + esc(item.name) + '</span>' +
      '<span class="itemcard__meta">' + esc(meta) + '</span>' +
    '</button>';
  }

  closetGrid.addEventListener('click', function (e) {
    var card = e.target.closest('[data-clothes-id]');
    if (!card) return;
    openItem(card.dataset.clothesId, $('.itemcard__name', card).textContent);
  });

  $$('[data-filter]').forEach(function (chip) {
    chip.addEventListener('click', function () {
      if (state.closet.filter === chip.dataset.filter) return;
      state.closet.filter = chip.dataset.filter;
      $$('[data-filter]').forEach(function (c) {
        var on = c === chip;
        c.classList.toggle('is-active', on);
        c.setAttribute('aria-pressed', on ? 'true' : 'false');
      });
      loadClothes({ reset: true });
    });
  });

  $('#btn-load-more').addEventListener('click', function () {
    state.closet.page += 1;
    loadClothes();
  });
  $('#btn-closet-retry').addEventListener('click', function () { loadClothes({ reset: true }); });

  // --- 옷 상세
  function openItem(id, title) { navigate('item', { id: id, title: title || 'アイテム' }); }

  function onItemEnter(id) {
    var host = $('#item-detail');
    if (state.item.id !== String(id)) {
      state.item = { id: String(id), data: null, usedIn: [], usedInUnavailable: false };
      host.innerHTML = '';
    }
    if (state.item.data) { renderItem(); return; }

    show($('#item-skeleton'), true);
    api.clothes.get(id).then(function (data) {
      state.item.data = data;
      $('#topbar-title').textContent = data.name || 'アイテム';
      renderItem();
      return api.clothes.coordinations(id, { page: 0, size: 20 }).then(function (page) {
        state.item.usedIn = (page && page.content) || [];
        state.item.usedInUnavailable = !!(page && page.__unavailable);
        renderItem();
      });
    }).catch(function (err) {
      if (isExpired(err)) return;
      host.innerHTML = errorStateHtml(humanError(err), 'item-retry');
    }).finally(function () {
      show($('#item-skeleton'), false);
    });
  }

  function renderItem() {
    var d = state.item.data;
    if (!d) return;
    var c = catOf(d.mainCategory);

    // 값이 없는 필드는 줄을 그리지 않는다.
    var rows = [
      { label: 'Category', value: c.label },
      { label: 'Type', value: d.subCategory },
      { label: 'Color', value: d.color },
      { label: 'Material', value: d.material },
      { label: 'Fit', value: d.fit },
      { label: 'Season', value: d.season },
      { label: 'Note', value: d.detail },
      { label: 'Added', value: d.createdAt ? dateOf(d.createdAt) : null }
    ].filter(function (r) { return r.value; });

    var usedIn = state.item.usedIn;
    var usedInBlock = usedIn.length
      ? '<div class="minilooks">' + usedIn.map(miniLookHtml).join('') + '</div>'
      : '<div class="empty empty--inline"><p class="empty__desc">' +
          (state.item.usedInUnavailable ? 'まだ整理されたコーデがありません。' : 'このアイテムを使ったコーデはまだありません。') +
        '</p></div>';

    // 넓은 화면은 좌 사진 / 우 정보, 좁은 폭은 위아래로 쌓인다.
    $('#item-detail').innerHTML =
      '<div class="detail detail--split">' +
        '<div class="detail__col detail__col--media">' +
          // 옷 사진에는 AI pill 을 얹지 않는다.
          '<div class="detail__media">' + itemFrameHtml(d, 'frame--item') + '</div>' +
        '</div>' +

        '<div class="detail__col detail__col--info">' +
        '<div class="detail__head">' +
          '<p class="indexlabel">' + esc(c.en) + '</p>' +
          '<h2 class="detail__title">' + esc(d.name) + '</h2>' +
        '</div>' +

        '<section class="section">' +
          '<p class="sectionlabel">Details</p>' +
          '<ul class="specrows">' + rows.map(specRowHtml).join('') + '</ul>' +
          // AI 자동 분류가 틀릴 수 있어 저장 후에도 수정할 수 있게 한다.
          '<button class="btn btn--ghost btn--block item__edit" type="button" data-action="edit-item">' +
            icon('pencil', 'ico--sm') + '<span class="btn__text">情報を編集</span>' +
          '</button>' +
        '</section>' +

        '<section class="section">' +
          '<p class="sectionlabel">Worn In</p>' +
          '<p class="section__sub">このアイテムを使ったコーデ' +
            (usedIn.length ? ' ' + usedIn.length + '件' : '') + '</p>' +
          usedInBlock +
        '</section>' +

        '<div class="detail__foot">' + deleteBlockHtml(
            // 코디에 쓰인 옷은 지워도 기록에 남는다는 점을 미리 알린다.
            usedIn.length
              ? 'このアイテムはコーデ ' + usedIn.length + '件 に使われています。クローゼットからは消えますが、これまでの記録には残ります。'
              : 'このアイテムをクローゼットから削除しますか？',
            'delete-item') + '</div>' +
        '</div>' +
      '</div>';

    hydrateImages($('#item-detail'));
  }

  function specRowHtml(r) {
    return '<li class="specrow"><span class="specrow__static">' +
      '<span class="specrow__label">' + esc(r.label) + '</span>' +
      '<span class="specrow__value">' + esc(r.value) + '</span></span></li>';
  }

  function deleteBlockHtml(question, action) {
    return '<div class="dangerzone" data-delete-block>' +
      '<button class="btn btn--quiet btn--danger-text btn--block" data-action="ask-delete" type="button">' +
        icon('trash', 'ico--sm') + '<span class="btn__text">削除</span></button>' +
      '<div class="confirm" role="alertdialog" aria-label="削除の確認" hidden>' +
        '<p class="confirm__q">' + esc(question) + '</p>' +
        '<div class="confirm__actions">' +
          '<button class="btn btn--ghost btn--tiny" data-action="cancel-delete" type="button">キャンセル</button>' +
          '<button class="btn btn--danger btn--tiny" data-action="' + esc(action) + '" type="button">' +
            '<span class="btn__spinner" aria-hidden="true"></span><span class="btn__text">削除</span></button>' +
        '</div>' +
      '</div>' +
    '</div>';
  }

  function errorStateHtml(msg, retryId) {
    return '<div class="state state--error" role="alert">' +
      '<p class="state__title">' + esc(msg) + '</p>' +
      '<button class="btn btn--ghost" id="' + esc(retryId) + '" type="button">読み込み直す</button></div>';
  }

  // 삭제 확인 토글은 상세 화면 두 곳이 함께 쓴다
  document.addEventListener('click', function (e) {
    var btn = e.target.closest('[data-action]');
    if (!btn) return;
    var block = btn.closest('[data-delete-block]');
    if (!block) return;

    var confirmBox = $('.confirm', block);
    var ask = $('[data-action="ask-delete"]', block);
    if (btn.dataset.action === 'ask-delete') {
      show(confirmBox, true);
      show(ask, false);
      var cancel = $('[data-action="cancel-delete"]', block);
      if (cancel) cancel.focus();
      // 화면 맨 아래에 열려 탭바에 가리므로 스크롤한다.
      scrollIntoViewSafely(confirmBox);
    } else if (btn.dataset.action === 'cancel-delete') {
      show(confirmBox, false);
      show(ask, true);
      if (ask) ask.focus();
    }
  });

  /** Esc 로 화면 안의 확인 패널을 닫는다. dialog 시트는 브라우저가 닫는다. */
  function closeConfirmPanel(box) {
    if (box.id === 'key-remove-confirm') {
      showKeyRemoveConfirm(false);
      $('#btn-key-ask-remove').focus();
      return;
    }
    var ask = $('[data-action^="ask-"]', box.parentNode);
    show(box, false);
    if (ask) { show(ask, true); ask.focus(); }
  }

  document.addEventListener('keydown', function (e) {
    if (e.key !== 'Escape' && e.key !== 'Esc') return;
    if (addSheet.open || guideSheet.open) return;   // 시트는 스스로 닫힌다
    var open = $$('.confirm').filter(function (box) { return !box.hidden; });
    if (!open.length) return;
    e.preventDefault();
    open.forEach(closeConfirmPanel);
  });

  $('#item-detail').addEventListener('click', function (e) {
    if (e.target.closest('[data-action="edit-item"]')) {
      if (state.item.data) openEditSheet(state.item.data);
      return;
    }

    var del = e.target.closest('[data-action="delete-item"]');
    if (del) {
      var done = busy(del, '削除しています…');
      api.clothes.remove(state.item.id).then(function () {
        state.closet.loaded = false;
        state.home.loaded = false;
        state.stats.loaded = false;
        toast('クローゼットから削除しました。');
        back();
      }).catch(function (err) {
        if (isExpired(err)) return;
        done();
        toast(humanError(err), 'error');
      });
      return;
    }
    if (e.target.closest('#item-retry')) { state.item.data = null; onItemEnter(state.item.id); return; }

    var fav = e.target.closest('[data-fav]');
    if (fav) { toggleFavorite(fav.dataset.fav); return; }
    var open = e.target.closest('[data-coord-open]');
    if (open) openCoord(open.dataset.coordOpen, open.dataset.coordTitle);
  });

  // --- 기록
  var HISTORY_SIZE = 12;

  function onHistoryEnter() {
    if (!state.history.loaded) loadHistory({ page: 0 });
    else renderHistory();
  }

  function loadHistory(opts) {
    opts = opts || {};
    var page = opts.page || 0;

    show($('#history-skeleton'), true);
    show($('#history-empty'), false);
    show($('#history-pager'), false);
    show($('#history-count'), false);
    $('#history-list').innerHTML = '';
    setNote($('#history-error'), '', $('#history-error-msg'));

    var isFav = state.history.filter === 'FAV';

    // 즐겨찾기는 서버 파라미터가 없다. 최신 몇 페이지를 모아 걸러 낸다.
    var load = isFav
      ? collectPages(5, 50).then(function (all) {
          return { content: all, page: 0, totalPages: 1, totalElements: all.length };
        })
      : api.coordinations.list({ page: page, size: HISTORY_SIZE });

    return load.then(function (data) {
      // 기록 API 가 아직 없으면 오늘 목록이라도 보여 준다
      if (data && data.__unavailable) {
        return api.coordinations.today().then(function (list) {
          applyHistory({ content: list || [], page: 0, totalPages: 1, totalElements: (list || []).length }, isFav);
        }).catch(function () {
          applyHistory({ content: [], page: 0, totalPages: 0, totalElements: 0 }, isFav);
        });
      }
      var content = (data && data.content) || [];
      applyHistory({
        content: content,
        page: data.page != null ? data.page : page,
        totalPages: data.totalPages || (content.length ? 1 : 0),
        totalElements: data.totalElements != null ? data.totalElements : content.length
      }, isFav);
    }).catch(function (err) {
      if (isExpired(err)) return;
      setNote($('#history-error'), humanError(err), $('#history-error-msg'));
    }).finally(function () {
      show($('#history-skeleton'), false);
    });
  }

  function applyHistory(data, isFav) {
    var content = data.content.slice().sort(function (a, b) {
      return new Date(b.createdAt) - new Date(a.createdAt);
    });
    if (isFav) content = content.filter(function (c) { return c.favorite; });

    state.history.items = content;
    state.history.page = data.page;
    state.history.totalPages = data.totalPages;
    state.history.totalElements = data.totalElements;
    state.history.loaded = true;
    renderHistory();
  }

  /** 즐겨찾기용: 최신 몇 페이지를 이어 붙인다. */
  function collectPages(maxPages, size) {
    var all = [];
    function step(p) {
      return api.coordinations.list({ page: p, size: size }).then(function (data) {
        all = all.concat((data && data.content) || []);
        if (data && data.hasNext && p + 1 < maxPages) return step(p + 1);
        return all;
      });
    }
    return step(0).catch(function () { return all; });
  }

  function renderHistory() {
    var items = state.history.items;
    var isFav = state.history.filter === 'FAV';

    show($('#history-empty'), items.length === 0);
    if (items.length === 0) {
      $('#history-empty-title').textContent = isFav ? 'お気に入りのコーデがありません' : 'まだ記録がありません';
      $('#history-empty-desc').textContent = isFav
        ? '気に入ったコーデのハートを押すと、ここに集まります。'
        : 'コーデを選んでもらうと、ここに一枚ずつ増えていきます。';
      show($('#btn-history-go-home'), !isFav);
    }

    show($('#history-count'), items.length > 0);
    if (items.length > 0) {
      $('#history-count').textContent = isFav
        ? 'お気に入り ' + items.length + '件'
        : (state.history.totalElements || items.length) + '件';
    }

    $('#history-list').innerHTML = items.map(lookHtml).join('');
    hydrateImages($('#history-list'));

    var multi = !isFav && state.history.totalPages > 1;
    show($('#history-pager'), multi);
    if (multi) {
      $('#history-pager-label').textContent = (state.history.page + 1) + ' / ' + state.history.totalPages;
      $('#btn-page-prev').disabled = state.history.page <= 0;
      $('#btn-page-next').disabled = state.history.page + 1 >= state.history.totalPages;
    }
  }

  $$('[data-hfilter]').forEach(function (b) {
    b.addEventListener('click', function () {
      if (state.history.filter === b.dataset.hfilter) return;
      state.history.filter = b.dataset.hfilter;
      $$('[data-hfilter]').forEach(function (o) {
        var on = o === b;
        o.classList.toggle('is-active', on);
        o.setAttribute('aria-pressed', on ? 'true' : 'false');
      });
      loadHistory({ page: 0 });
    });
  });

  $('#btn-page-prev').addEventListener('click', function () {
    if (state.history.page > 0) loadHistory({ page: state.history.page - 1 });
  });
  $('#btn-page-next').addEventListener('click', function () {
    if (state.history.page + 1 < state.history.totalPages) loadHistory({ page: state.history.page + 1 });
  });
  $('#btn-history-retry').addEventListener('click', function () { loadHistory({ page: state.history.page }); });

  // --- 즐겨찾기
  function toggleFavorite(id) {
    var before = findCoord(id);
    var next = !(before && before.favorite);
    setFavoriteLocally(id, next);   // 먼저 반응하고, 실패하면 되돌린다

    api.coordinations.toggleFavorite(id).then(function (res) {
      var value = res && typeof res.favorite === 'boolean' ? res.favorite : next;
      setFavoriteLocally(id, value);
      if (state.history.filter === 'FAV' && !value) loadHistory({ page: 0 });
    }).catch(function (err) {
      setFavoriteLocally(id, !next);
      if (isExpired(err)) return;
      toast(api.isNotDeployed(err) ? 'お気に入りはまだ保存されません。' : humanError(err), 'error');
    });
  }

  function findCoord(id) {
    var pools = [state.history.items, state.home.today, state.home.recent, state.item.usedIn];
    for (var i = 0; i < pools.length; i++) {
      var hit = pools[i].filter(function (c) { return String(c.id) === String(id); })[0];
      if (hit) return hit;
    }
    if (state.coord.data && String(state.coord.data.id) === String(id)) return state.coord.data;
    return null;
  }

  function setFavoriteLocally(id, value) {
    [state.history.items, state.home.today, state.home.recent, state.item.usedIn].forEach(function (pool) {
      pool.forEach(function (c) { if (String(c.id) === String(id)) c.favorite = value; });
    });
    if (state.coord.data && String(state.coord.data.id) === String(id)) state.coord.data.favorite = value;

    $$('[data-fav="' + String(id).replace(/"/g, '') + '"]').forEach(function (btn) {
      btn.classList.toggle('is-on', value);
      btn.setAttribute('aria-pressed', value ? 'true' : 'false');
      btn.innerHTML = icon(value ? 'heart-fill' : 'heart');
    });
    var action = $('#btn-topbar-action');
    if (!action.hidden && String(action.dataset.favId) === String(id)) {
      action.classList.toggle('is-on', value);
      action.setAttribute('aria-pressed', value ? 'true' : 'false');
      action.innerHTML = icon(value ? 'heart-fill' : 'heart');
    }
  }

  // --- 코디 상세
  function openCoord(id, title) { navigate('coord', { id: id, title: title || 'コーデ' }); }

  function onCoordEnter(id) {
    var host = $('#coord-detail');
    var cached = findCoord(id);

    if (state.coord.id !== String(id)) {
      // 다른 코디로 넘어가면 사진 탭을 기본값(tryon)으로 되돌린다.
      state.coord = { id: String(id), data: cached || null, media: 'tryon' };
      host.innerHTML = '';
    }
    if (state.coord.data) renderCoord();
    else show($('#coord-skeleton'), true);

    // 목록 값은 요약일 수 있어 항상 다시 읽는다
    api.coordinations.get(id).then(function (data) {
      state.coord.data = data;
      renderCoord();
    }).catch(function (err) {
      if (isExpired(err)) return;
      if (state.coord.data) return;   // 캐시가 있으면 그대로 둔다
      host.innerHTML = errorStateHtml(humanError(err), 'coord-retry');
    }).finally(function () {
      show($('#coord-skeleton'), false);
    });
  }

  /** 입어보기 블록. 결과가 있으면 재생성 버튼(확인 필요)과 착용 사진 삭제를 둔다. */
  function tryOnBlockHtml(c, items) {
    var hasResult = !!c.tryOnImageUrl;
    // 사진 없는 옷은 이름만으로 생성돼 실물과 달라질 수 있다.
    var noPhoto = items.filter(function (it) { return !it.imageUrl; });

    // 추천 직후 자동 생성이 실패한 이유. 결과가 이미 있으면 숨긴다.
    var note = (state.tryOnNote && String(state.tryOnNote.id) === String(c.id) && !hasResult)
      ? state.tryOnNote : null;

    return '<div class="tryon" data-tryon>' +
      (note
        ? '<div class="note' + (note.kind === 'error' ? ' note--error' : '') + '" role="status">' +
            '<span class="note__body">' + esc(note.message) + '</span>' +
            (note.action
              ? '<button class="btn btn--tiny btn--ghost" type="button" data-action="' + esc(note.action) + '">' +
                  esc(note.actionLabel) + '</button>'
              : '') +
          '</div>'
        : '') +
      (hasResult
        ? '<button class="btn btn--ghost btn--block" type="button" data-action="ask-tryon-again">' +
            icon('sparkle', 'ico--sm accent') +
            '<span class="btn__text">作り直す</span>' +
          '</button>'
        : '<button class="btn btn--primary btn--block" type="button" data-action="tryon">' +
            '<span class="btn__spinner" aria-hidden="true"></span>' +
            icon('sparkle', 'ico--sm') +
            '<span class="btn__text">試着する</span>' +
          '</button>') +
      (hasResult ? '' : '<p class="tryon__hint">登録した全身写真にこのコーデを着せてみます。</p>') +

      // 막지 않고 경고만 한다.
      (noPhoto.length
        ? '<div class="note note--warn">写真のないアイテム（' +
            esc(noPhoto.map(function (it) { return it.name; }).join('、')) +
            '）は名前だけで描くため、実物と違って見えることがあります。</div>'
        : '') +

      '<div class="confirm confirm--quiet" role="alertdialog" aria-label="作り直しの確認" data-tryon-again hidden>' +
        '<p class="confirm__q">AI をもう一度呼んで作り直します。今の画像は新しい画像に置き換わります。</p>' +
        '<div class="confirm__actions">' +
          '<button class="btn btn--ghost btn--tiny" type="button" data-action="cancel-tryon-again">キャンセル</button>' +
          '<button class="btn btn--primary btn--tiny" type="button" data-action="tryon">' +
            '<span class="btn__spinner" aria-hidden="true"></span>' +
            '<span class="btn__text">作り直す</span>' +
          '</button>' +
        '</div>' +
      '</div>' +

      // 코디 기록은 두고 착용 사진만 지운다.
      (hasResult
        ? '<button class="btn btn--quiet btn--danger-text btn--block" type="button" data-action="ask-del-tryon">' +
            icon('trash', 'ico--sm') + '<span class="btn__text">この試着画像だけ削除</span>' +
          '</button>' +
          '<div class="confirm" role="alertdialog" aria-label="試着画像の削除確認" data-del-tryon hidden>' +
            '<p class="confirm__q">この試着画像だけ削除しますか？コーデの記録と服はそのまま残ります。</p>' +
            '<div class="confirm__actions">' +
              '<button class="btn btn--ghost btn--tiny" type="button" data-action="cancel-del-tryon">キャンセル</button>' +
              '<button class="btn btn--danger btn--tiny" type="button" data-action="delete-tryon">' +
                '<span class="btn__spinner" aria-hidden="true"></span>' +
                '<span class="btn__text">画像だけ削除</span>' +
              '</button>' +
            '</div>' +
          '</div>'
        : '') +

      '<div class="progress" hidden>' +
        '<div class="progress__track"><div class="progress__fill"></div></div>' +
        '<p class="progress__label" role="status">準備しています…</p>' +
      '</div>' +
      '<div class="note note--error tryon__error" role="alert" hidden></div>' +
    '</div>';
  }

  // --- 코디 상세의 사진
  var MEDIA_NOTE = {
    tryon: 'AI が作った画像です。実際に着た姿ではありません。',
    items: '登録した服の写真そのままです。'
  };

  /** 착용 사진이 있으면 상세에서 원본 옷 사진과 전환할 수 있게 한다. */
  function coordMediaHtml(c) {
    var hasTryOn = !!c.tryOnImageUrl;
    var which = hasTryOn ? (state.coord.media || 'tryon') : 'items';

    var panes = (hasTryOn ? tryOnFrameHtml(c, ' data-media-pane="tryon"' + (which === 'tryon' ? '' : ' hidden')) : '') +
      itemsFrameHtml(c, ' data-media-pane="items"' + (which === 'items' ? '' : ' hidden'));

    return '<div class="detail__media">' + panes +
        '<span class="pill" data-ai-pill><span class="pill__dot" aria-hidden="true"></span>' +
          (which === 'tryon' ? 'AI生成画像' : 'AI提案') +
        '</span>' +
      '</div>' +
      (hasTryOn
        ? '<div class="mediaswitch">' +
            '<div class="segmented" role="group" aria-label="画像の種類">' +
              '<button class="segmented__item' + (which === 'tryon' ? ' is-active' : '') + '" type="button" ' +
                'data-media-tab="tryon" aria-pressed="' + (which === 'tryon') + '">試着した姿</button>' +
              '<button class="segmented__item' + (which === 'items' ? ' is-active' : '') + '" type="button" ' +
                'data-media-tab="items" aria-pressed="' + (which === 'items') + '">自分の服の写真</button>' +
            '</div>' +
            '<p class="medianote" data-media-note>' + esc(MEDIA_NOTE[which]) + '</p>' +
          '</div>'
        : '');
  }

  function setCoordMedia(which) {
    state.coord.media = which;
    var host = $('#coord-detail');
    $$('[data-media-pane]', host).forEach(function (p) {
      show(p, p.dataset.mediaPane === which);
    });
    $$('[data-media-tab]', host).forEach(function (b) {
      var on = b.dataset.mediaTab === which;
      b.classList.toggle('is-active', on);
      b.setAttribute('aria-pressed', on ? 'true' : 'false');
    });
    var pill = $('[data-ai-pill]', host);
    if (pill) {
      pill.innerHTML = '<span class="pill__dot" aria-hidden="true"></span>' +
        (which === 'tryon' ? 'AI生成画像' : 'AI提案');
    }
    var note = $('[data-media-note]', host);
    if (note) note.textContent = MEDIA_NOTE[which] || '';
  }

  function renderCoord() {
    var c = state.coord.data;
    if (!c) return;
    var items = sortedItems(c);

    $('#topbar-title').textContent = c.title || 'コーデ';

    var action = $('#btn-topbar-action');
    show(action, true);
    action.dataset.favId = c.id;
    action.setAttribute('aria-label', 'お気に入り');
    action.setAttribute('aria-pressed', c.favorite ? 'true' : 'false');
    action.classList.toggle('is-on', !!c.favorite);
    action.innerHTML = icon(c.favorite ? 'heart-fill' : 'heart');

    var situation = situationOf(c);

    // 넓은 화면은 좌 사진 / 우 정보, 좁은 폭은 위아래로 쌓인다.
    $('#coord-detail').innerHTML =
      '<div class="detail detail--split">' +
        '<div class="detail__col detail__col--media">' +
        '<div class="look__head">' +
          '<span class="indexlabel">' + esc(lookLabel(c)) + '</span>' +
          '<span class="num">' + esc(stampOf(c.createdAt)) + '</span>' +
        '</div>' +

        coordMediaHtml(c) +
        // 넓은 화면에서도 입어보기는 사진 바로 밑에 둔다.
        tryOnBlockHtml(c, items) +
        '</div>' +

        '<div class="detail__col detail__col--info">' +
        '<div class="detail__head">' +
          '<h2 class="detail__title">' + esc(c.title || '今日のコーデ') + '</h2>' +
        '</div>' +

        (situation
          ? '<section class="section">' +
              '<p class="sectionlabel">Today’s Context</p>' +
              '<p class="situation-line">' + esc(situation) + '</p>' +
            '</section>'
          : '') +

        (c.reason
          ? '<section class="section">' +
              '<p class="sectionlabel">' + icon('sparkle', 'ico--sm') + 'Coordination Details</p>' +
              '<p class="prose">' + esc(c.reason) + '</p>' +
            '</section>'
          : '') +

        '<section class="section">' +
          '<p class="sectionlabel">Items</p>' +
          '<ul class="specrows">' +
            items.map(function (it) {
              var cat = catOf(it.mainCategory);
              var value = it.name + (it.color ? ' (' + it.color + ')' : '');
              // 옷장에서 지운 옷은 상세 화면이 없어 링크 없이 그린다.
              if (it.inWardrobe === false) {
                return '<li class="specrow specrow--gone">' +
                  '<div class="specrow__btn specrow__btn--static">' +
                    '<span class="specrow__label">' + esc(cat.en) + '</span>' +
                    '<span class="specrow__value">' + esc(value) +
                      '<span class="specrow__note">クローゼットから削除済み</span>' +
                    '</span>' +
                  '</div></li>';
              }
              return '<li class="specrow">' +
                '<button class="specrow__btn" type="button" data-clothes-id="' + esc(it.clothesId) + '" ' +
                        'data-clothes-name="' + esc(it.name) + '">' +
                  '<span class="specrow__label">' + esc(cat.en) + '</span>' +
                  '<span class="specrow__value">' + esc(value) + '</span>' +
                  icon('next', 'ico--sm specrow__chev') +
                '</button></li>';
            }).join('') +
          '</ul>' +
        '</section>' +

        '<div class="detail__foot">' + deleteBlockHtml('このコーデを記録から削除しますか？', 'delete-coord') + '</div>' +
        '</div>' +
      '</div>';

    hydrateImages($('#coord-detail'));
  }

  $('#btn-topbar-action').addEventListener('click', function () {
    if (this.dataset.favId) toggleFavorite(this.dataset.favId);
  });

  $('#coord-detail').addEventListener('click', function (e) {
    var tab = e.target.closest('[data-media-tab]');
    if (tab) { setCoordMedia(tab.dataset.mediaTab); return; }

    var link = e.target.closest('[data-clothes-id]');
    if (link) { openItem(link.dataset.clothesId, link.dataset.clothesName); return; }

    var del = e.target.closest('[data-action="delete-coord"]');
    if (del) {
      var done = busy(del, '削除しています…');
      api.coordinations.remove(state.coord.id).then(function () {
        state.history.loaded = false;
        state.home.loaded = false;
        state.stats.loaded = false;
        toast('コーデを削除しました。');
        back();
      }).catch(function (err) {
        if (isExpired(err)) return;
        done();
        toast(api.isNotDeployed(err) ? '今は削除できません。' : humanError(err), 'error');
      });
      return;
    }

    if (e.target.closest('#coord-retry')) { state.coord.data = null; onCoordEnter(state.coord.id); return; }

    if (e.target.closest('[data-action="go-body-photo"]')) {
      // 전신 사진 등록이 끝나면 이 코디로 되돌아오도록 기억해 둔다.
      var c = state.coord.data;
      state.pendingTryOn = { id: state.coord.id, title: (c && c.title) || 'コーデ' };
      navigate('more');
      setTimeout(function () {
        var el = $('#body-photo-preview');
        if (el) el.scrollIntoView({ block: 'center', behavior: 'smooth' });
      }, 140);
      return;
    }

    // 재생성은 AI 를 다시 호출하므로 확인을 받는다.
    if (e.target.closest('[data-action="ask-tryon-again"]')) { showTryOnAgain(true); return; }
    if (e.target.closest('[data-action="cancel-tryon-again"]')) { showTryOnAgain(false); return; }

    if (e.target.closest('[data-action="ask-del-tryon"]')) { showDelTryOn(true); return; }
    if (e.target.closest('[data-action="cancel-del-tryon"]')) { showDelTryOn(false); return; }
    var delTryOn = e.target.closest('[data-action="delete-tryon"]');
    if (delTryOn) { runDeleteTryOn(delTryOn); return; }

    var tryOnBtn = e.target.closest('[data-action="tryon"]');
    if (tryOnBtn) runTryOn(tryOnBtn);
  });

  // --- 입어보기
  function showTryOnAgain(on) {
    var host = $('[data-tryon]', $('#coord-detail'));
    if (!host) return;
    var box = $('[data-tryon-again]', host);
    var ask = $('[data-action="ask-tryon-again"]', host);
    show(box, on);
    show(ask, !on);
    var focusTarget = on ? $('[data-action="cancel-tryon-again"]', host) : ask;
    if (focusTarget) focusTarget.focus();
    if (on) scrollIntoViewSafely(box);
  }

  function showDelTryOn(on) {
    var host = $('[data-tryon]', $('#coord-detail'));
    if (!host) return;
    var box = $('[data-del-tryon]', host);
    var ask = $('[data-action="ask-del-tryon"]', host);
    show(box, on);
    show(ask, !on);
    var focusTarget = on ? $('[data-action="cancel-del-tryon"]', host) : ask;
    if (focusTarget) focusTarget.focus();
    if (on) scrollIntoViewSafely(box);
  }

  /** 착용 사진만 지운다. 엔드포인트가 없으면(404·405) 준비 중 문구와 대안을 보여 준다. */
  function runDeleteTryOn(btn) {
    var host = $('[data-tryon]', $('#coord-detail'));
    var errEl = $('.tryon__error', host);
    var done = busy(btn, '削除しています…');
    show(errEl, false);

    api.coordinations.removeTryOn(state.coord.id).then(function () {
      if (state.coord.data) state.coord.data.tryOnImageUrl = null;
      var pooled = findCoord(state.coord.id);
      if (pooled) pooled.tryOnImageUrl = null;
      state.home.loaded = false;
      state.history.loaded = false;
      state.coord.media = 'tryon';   // 다음 결과가 생기면 착용 사진부터 보여 준다
      renderCoord();
      toast('試着画像を削除しました。服の写真はそのままです。');
    }).catch(function (err) {
      done();
      if (isExpired(err)) return;
      setNote(errEl, api.isNotDeployed(err)
        ? 'この画像だけを削除する機能はまだ準備中です。今は「作り直す」で新しい画像を作れます。'
        : humanError(err));
    });
  }

  function runTryOn(btn) {
    if (!requireAi()) return;

    var host = $('[data-tryon]', $('#coord-detail'));
    var progress = $('.progress', host);
    var errEl = $('.tryon__error', host);
    // 유료 호출이라 진행 중에는 이 블록의 버튼(확인 상자 포함)을 전부 잠근다.
    var locked = $$('button', host);

    show(errEl, false);
    locked.forEach(function (b) { b.disabled = true; });
    btn.classList.add('is-busy');
    btn.setAttribute('aria-busy', 'true');
    show(progress, true);

    function release() {
      locked.forEach(function (b) { b.disabled = false; });
      btn.classList.remove('is-busy');
      btn.removeAttribute('aria-busy');
    }

    var sim = startProgress($('.progress__fill', progress), $('.progress__label', progress));

    api.coordinations.tryOn(state.coord.id).then(function (res) {
      sim.finish();
      var url = res && res.tryOnImageUrl;
      if (state.coord.data) state.coord.data.tryOnImageUrl = url;
      var pooled = findCoord(state.coord.id);
      if (pooled) pooled.tryOnImageUrl = url;

      // 마지막 프레임(100%)을 잠깐 보여 주고 결과로 바꾼다
      setTimeout(function () {
        if (url) {
          state.tryOnNote = null;
          state.home.loaded = false;
          renderCoord();   // 버튼이 "作り直す" 로 다시 그려진다
          toast('試着した姿ができました。');
        } else {
          show(progress, false);
          release();
          setNote(errEl, '画像を受け取れませんでした。もう一度お試しください。');
        }
      }, 420);
    }).catch(function (err) {
      sim.stop();
      show(progress, false);
      release();
      if (isExpired(err)) return;

      if (err.isApiError && err.code === 'no_body_photo') {
        errEl.innerHTML = '<span class="note__body">試着には全身写真が必要です。</span>' +
          '<button class="btn btn--tiny btn--ghost" type="button" data-action="go-body-photo">全身写真を登録</button>';
        show(errEl, true);
        return;
      }
      if (err.isApiError && err.status === 503) {
        openGuide({
          title: 'AI が応答していません',
          desc: '連携したキーが有効か確認して、しばらくしてからもう一度お試しください。',
          action: '連携を確認しに行く'
        });
        return;
      }
      setNote(errEl, humanError(err));
    });
  }

  /**
   * 가짜 진행률. 서버가 진행률을 주지 않아 남은 거리에 비례해 느려지다 96% 에서 멈추고,
   * 100% 는 실제 응답이 왔을 때만 찍는다.
   */
  var TRYON_STAGES = [
    [0, '準備しています…'],
    [20, '全身写真を確認しています…'],
    [45, '服を一つずつ着せています…'],
    [72, '仕上げています…もう少しお待ちください'],
    [92, 'もうすぐです…']
  ];

  function startProgress(fillEl, labelEl, stages) {
    stages = stages || TRYON_STAGES;
    var pct = 0;
    var held = null;   // 단계 문구 대신 고정할 문구 (예: 재시도 중)
    var timer = setInterval(function () {
      pct += Math.max(0.35, (96 - pct) * 0.035);
      if (pct > 96) pct = 96;
      apply();
    }, 320);

    function apply() {
      fillEl.style.setProperty('--progress', pct.toFixed(1) + '%');
      if (held) {
        if (labelEl.textContent !== held) labelEl.textContent = held;
        return;
      }
      for (var i = stages.length - 1; i >= 0; i--) {
        if (pct >= stages[i][0]) {
          if (labelEl.textContent !== stages[i][1]) labelEl.textContent = stages[i][1];
          break;
        }
      }
    }
    apply();

    return {
      stop: function () { clearInterval(timer); },
      /** 단계와 무관한 문구를 고정한다. */
      hold: function (text) { held = text; apply(); },
      finish: function (text) {
        clearInterval(timer);
        held = null;
        fillEl.style.setProperty('--progress', '100%');
        labelEl.textContent = text || '完成しました！';
      }
    };
  }

  // --- 옷 추가 시트
  var addSheet = $('#add-sheet');

  function openSheet(el) {
    if (typeof el.showModal === 'function') el.showModal();
    else el.setAttribute('open', '');
    relocateToasts(el);
  }

  function closeSheet(el) {
    if (el.open && typeof el.close === 'function') el.close();
    else el.removeAttribute('open');
    relocateToasts(null);
  }

  function openAddSheet() {
    state.editItemId = null;
    resetAddForm();
    applySheetMode();
    openSheet(addSheet);
    setTimeout(function () { $('#add-image-label').focus(); }, 40);
  }

  /** 추가 입력 칸. analyze 응답과 등록 요청의 필드명이 1:1 로 같다. */
  var EXTRA_FIELDS = [
    { key: 'subCategory', sel: '#add-subcategory', label: '種類' },
    { key: 'material',    sel: '#add-material',    label: '素材' },
    { key: 'fit',         sel: '#add-fit',         label: 'シルエット' },
    { key: 'season',      sel: '#add-season',      label: '季節' }
  ];

  /**
   * 계절 칩. 칩은 입력 단축일 뿐이고 서버는 20자 이내 자유 입력을 받는다.
   * 눌림 표시는 입력칸 내용과 같은지로 정한다.
   */
  var seasonInput = $('#add-season');

  function syncSeasonChips() {
    var value = seasonInput ? seasonInput.value.trim() : '';
    $$('#add-season-chips [data-season]').forEach(function (chip) {
      var on = chip.dataset.season === value;
      chip.classList.toggle('is-active', on);
      chip.setAttribute('aria-pressed', on ? 'true' : 'false');
    });
  }

  if (seasonInput) {
    seasonInput.addEventListener('input', syncSeasonChips);
    $('#add-season-chips').addEventListener('click', function (e) {
      var chip = e.target.closest('[data-season]');
      if (!chip) return;
      // 눌린 칩을 다시 누르면 비운다.
      var same = seasonInput.value.trim() === chip.dataset.season;
      seasonInput.value = same ? '' : chip.dataset.season;
      syncSeasonChips();
    });
  }

  /** 옷 정보 수정. 등록 시트를 재사용하고, 사진은 PATCH 로 못 바꾸므로 사진 선택만 숨긴다. */
  function openEditSheet(item) {
    state.editItemId = item.id;
    resetAddForm();
    $('#add-name').value = item.name || '';
    $('#add-color').value = item.color || '';
    $('#add-detail').value = item.detail || '';
    EXTRA_FIELDS.forEach(function (f) { $(f.sel).value = item[f.key] || ''; });
    syncSeasonChips();
    setCategory(CATEGORY[item.mainCategory] ? item.mainCategory : 'TOP');
    applySheetMode();
    openSheet(addSheet);
    setTimeout(function () { $('#add-name').focus(); $('#add-name').select(); }, 40);
  }

  function applySheetMode() {
    var editing = state.editItemId != null;
    $('#add-sheet-kicker').textContent = editing ? 'Edit Item' : 'New Item';
    $('#add-sheet-title').textContent = editing ? 'アイテム情報の編集' : '服を追加';
    $('#add-sheet-desc').textContent = editing
      ? 'AI が読み取った内容をここで直せます。写真は変わりません。'
      : '写真を選ぶだけで、名前・カテゴリー・色・種類・素材・シルエット・季節まで自動で入力します。そのまま保存できます。';
    show($('#add-picker'), !editing);
    $('.btn__text', $('#btn-add-submit')).textContent = editing ? '変更を保存' : '保存';
  }

  function closeAddSheet() {
    if (state.analyzeAbort) { state.analyzeAbort.abort(); state.analyzeAbort = null; }
    closeSheet(addSheet);
  }

  function resetAddForm() {
    $('#form-add').reset();
    state.addImage = null;
    var img = $('#add-thumb-img');
    img.hidden = true;
    img.removeAttribute('src');
    img.classList.remove('is-loaded');
    show($('#add-thumb-empty'), true);
    show($('#btn-clear-image'), false);
    show($('#analyze-loading'), false);
    setNote($('#add-error'), '');
    setNote($('#analyze-warn'), '');
    setCategory('TOP');
    syncSeasonChips();   // form.reset() 은 칩 눌림 표시를 풀지 않는다
    setAnalyzing(false);
  }

  /** 사진 분석 중에는 저장 버튼을 잠그고 대기 문구를 표시한다. */
  function setAnalyzing(on) {
    var btn = $('#btn-add-submit');
    btn.disabled = !!on;
    if (on) btn.setAttribute('aria-busy', 'true');
    else btn.removeAttribute('aria-busy');
    $('.btn__text', btn).textContent = on ? '写真を読み取り中…'
      : (state.editItemId != null ? '変更を保存' : '保存');
  }

  /** 분석 결과 안내. 시트 안에서는 토스트 대신 인라인 노트를 쓴다. */
  function analyzeNotice(message, kind) {
    var el = $('#analyze-warn');
    el.classList.toggle('note--ok', kind === 'ok');
    el.classList.toggle('note--warn', kind !== 'ok');
    setNote(el, message);
  }

  function setCategory(cat) {
    $$('#add-category .segmented__item').forEach(function (b) {
      var on = b.dataset.cat === cat;
      b.classList.toggle('is-active', on);
      b.setAttribute('aria-pressed', on ? 'true' : 'false');
    });
  }
  function currentCategory() {
    var on = $('#add-category .segmented__item.is-active');
    return on ? on.dataset.cat : 'TOP';
  }

  $$('#add-category .segmented__item').forEach(function (b) {
    b.addEventListener('click', function () { setCategory(b.dataset.cat); });
  });

  // 파일 입력은 숨겨 두고 버튼으로 연다 (label 은 키보드로 활성화되지 않는다)
  $('#add-image-label').addEventListener('click', function () { $('#add-image').click(); });
  $('#body-photo-label').addEventListener('click', function () {
    if (!this.disabled) $('#body-photo-input').click();
  });

  $$('[data-open-add]').forEach(function (b) { b.addEventListener('click', openAddSheet); });
  $('#btn-add-cancel').addEventListener('click', closeAddSheet);

  // ESC 나 닫기 버튼으로 닫혀도 여기서 정리한다.
  addSheet.addEventListener('close', function () {
    if (state.analyzeAbort) { state.analyzeAbort.abort(); state.analyzeAbort = null; }
    state.editItemId = null;
    relocateToasts(null);
  });
  guideSheet.addEventListener('close', function () { relocateToasts(null); });

  [addSheet, guideSheet].forEach(function (el) {
    el.addEventListener('click', function (e) { if (e.target === el) closeSheet(el); });
  });

  $('#btn-clear-image').addEventListener('click', function () {
    if (state.analyzeAbort) { state.analyzeAbort.abort(); state.analyzeAbort = null; }
    state.addImage = null;
    $('#add-image').value = '';
    var img = $('#add-thumb-img');
    img.hidden = true;
    img.removeAttribute('src');
    img.classList.remove('is-loaded');
    show($('#add-thumb-empty'), true);
    show($('#btn-clear-image'), false);
    show($('#analyze-loading'), false);
    setNote($('#analyze-warn'), '');
    setAnalyzing(false);
  });

  /** 사진을 고르면 분석해서 폼을 채운다. 분석이 실패해도 직접 입력은 가능하다. */
  $('#add-image').addEventListener('change', function (e) {
    var file = e.target.files && e.target.files[0];
    if (!file) return;

    setNote($('#add-error'), '');
    setNote($('#analyze-warn'), '');
    state.addImage = file;

    var img = $('#add-thumb-img');
    img.src = localPreview(file);
    img.hidden = false;
    img.classList.add('is-loaded');
    show($('#add-thumb-empty'), false);
    show($('#btn-clear-image'), true);

    // AI 미연결이면 분석을 건너뛰고 직접 입력으로 넘어간다
    if (state.ai.checked && !state.ai.configured) {
      analyzeNotice('AI連携がまだなので、写真は自動で読み取れません。直接入力して保存できます。', 'warn');
      $('#add-name').focus();
      return;
    }

    if (state.analyzeAbort) state.analyzeAbort.abort();
    var ctrl = ('AbortController' in window) ? new AbortController() : null;
    state.analyzeAbort = ctrl;

    show($('#analyze-loading'), true);
    setAnalyzing(true);

    api.clothes.analyze(file, ctrl ? ctrl.signal : undefined)
      .then(function (result) {
        if (state.addImage !== file) return;   // 그 사이 다른 사진을 고른 경우
        if (result) {
          if (result.name && !$('#add-name').value) $('#add-name').value = result.name;
          if (result.mainCategory && CATEGORY[result.mainCategory]) setCategory(result.mainCategory);
          if (result.color && !$('#add-color').value) $('#add-color').value = result.color;
          if (result.detail && !$('#add-detail').value) $('#add-detail').value = result.detail;
          // 응답에 없는 항목은 빈칸으로 둔다.
          EXTRA_FIELDS.forEach(function (f) {
            var v = result[f.key];
            if (v && !$(f.sel).value) $(f.sel).value = String(v);
          });
          syncSeasonChips();
          analyzeNotice('写真を読み取って、すべての項目を入力しました。このまま保存できます。', 'ok');
        }
      })
      .catch(function (err) {
        if (err && err.name === 'AbortError') return;
        if (isExpired(err)) return;
        // 형식 문제(HEIC 등)는 분석 실패와 구분해서 알린다.
        analyzeNotice(
          err.isApiError && (err.code === 'unsupported_image_type' || err.status === 415)
            ? humanError(err)
            : err.isApiError && err.status === 503
              ? 'AI が応答していません。直接入力して保存できます。'
              : '写真を自動で読み取れませんでした。直接入力して保存できます。',
          'warn'
        );
      })
      .finally(function () {
        if (state.addImage !== file && state.addImage !== null) return;
        show($('#analyze-loading'), false);
        setAnalyzing(false);
        state.analyzeAbort = null;
        if (!$('#add-name').value) $('#add-name').focus();
      });
  });

  // 한 줄 입력칸이 많아 IME 확정 Enter 로 저장되지 않게 막는다.
  guardImeSubmit($('#form-add'));
  guardImeSubmit($('#form-key'));

  $('#form-add').addEventListener('submit', function (e) {
    e.preventDefault();
    var name = $('#add-name').value.trim();
    var color = $('#add-color').value.trim();
    var detail = $('#add-detail').value.trim();
    setNote($('#add-error'), '');

    if (!name) {
      setNote($('#add-error'), 'アイテム名を入力してください。');
      $('#add-name').focus();
      return;
    }

    var extras = {};
    EXTRA_FIELDS.forEach(function (f) { extras[f.key] = $(f.sel).value.trim(); });

    /** 보냈지만 서버에 저장되지 않은 칸의 라벨 목록. */
    function droppedFields(saved) {
      var lost = EXTRA_FIELDS.filter(function (f) {
        return extras[f.key] && (saved[f.key] || '') !== extras[f.key];
      }).map(function (f) { return f.label; });
      if ((saved.detail || '') !== detail) lost.push('説明');
      return lost;
    }

    var done = busy($('#btn-add-submit'), '保存しています…');

    if (state.editItemId != null) {
      var editingId = state.editItemId;
      api.clothes.update(editingId, Object.assign({
        name: name,
        mainCategory: currentCategory(),
        // PATCH 에서 null 은 변경 없음이라 비울 때는 빈 문자열을 보낸다.
        color: color,
        detail: detail
      }, extras)).then(function (updated) {
        closeAddSheet();
        state.item.data = updated;
        state.closet.loaded = false;
        state.home.loaded = false;
        state.stats.loaded = false;
        if (String(state.item.id) === String(editingId)) {
          $('#topbar-title').textContent = updated.name || 'アイテム';
          renderItem();
        }
        toast('「' + updated.name + '」の情報を更新しました。');
        var lost = droppedFields(updated);
        if (lost.length) {
          toast(lost.join('・') + ' はまだ保存されません。ほかは保存しました。', 'error');
        }
      }).catch(function (err) {
        if (isExpired(err)) return;
        setNote($('#add-error'), api.isNotDeployed(err)
          ? '今は編集できません。しばらくしてからもう一度お試しください。'
          : humanError(err));
      }).finally(done);
      return;
    }

    api.clothes.create(Object.assign({
      image: state.addImage,
      name: name,
      mainCategory: currentCategory(),
      color: color || null,
      detail: detail || null
    }, extras)).then(function (created) {
      closeAddSheet();
      toast('「' + created.name + '」をクローゼットに追加しました。');
      state.home.loaded = false;
      state.closet.loaded = true;
      state.stats.loaded = false;
      // 개수·정렬이 서버 기준이라 첫 페이지부터 다시 읽는다.
      if (current().name !== 'closet') navigate('closet');
      return loadClothes({ reset: true });
    }).catch(function (err) {
      if (isExpired(err)) return;
      setNote($('#add-error'), humanError(err));
    }).finally(done);
  });

  // --- 더보기 / 설정
  var moreLoaded = false;

  function onMoreEnter() {
    var name = (state.user && state.user.displayName) || '';
    $('#more-name').textContent = name;
    show($('#more-name'), !!name);
    // 저장 안 한 입력이 남지 않도록 들어올 때마다 서버 값으로 되돌린다.
    $('#name-input').value = name;
    updateNameCount();
    renderBodyPhoto();
    renderAiState();
    loadStats().then(renderStatsPanel).catch(function () {});
    if (moreLoaded) return;
    moreLoaded = true;

    loadAiState();
    api.users.stylePreference().then(function (res) {
      $('#style-input').value = (res && res.preference) || '';
      updateStyleCount();
    }).catch(function () { /* 없으면 빈칸으로 둔다 */ });
  }

  // --- 옷장 통계 (홈 색인과 더보기 패널이 공유)
  function loadStats() {
    if (state.stats.loaded) return Promise.resolve(state.stats.data);
    return api.clothes.stats().then(function (s) {
      state.stats.loaded = true;
      state.stats.data = (s && !s.__unavailable && s.total != null) ? s : null;
      return state.stats.data;
    }).catch(function (err) {
      state.stats.loaded = true;
      state.stats.data = null;
      if (isExpired(err)) throw err;
      return null;
    });
  }

  function renderStatsPanel() {
    var s = state.stats.data;
    if (!s) { show($('#panel-stats'), false); return; }

    var by = s.byCategory || {};
    var rows = [
      { label: 'Total', value: s.total },
      { label: 'Top', value: by.TOP || 0 },
      { label: 'Bottom', value: by.BOTTOM || 0 },
      { label: 'Outer', value: by.OUTER || 0 }
    ];
    if (s.neverUsed != null) rows.push({ label: 'Never worn', value: s.neverUsed });

    var most = (s.mostUsed || []).slice(0, 3);
    $('#stats-body').innerHTML =
      '<div class="statrows">' + rows.map(function (r) {
        return '<div class="statrow">' +
          '<span class="statrow__label">' + esc(r.label) + '</span>' +
          '<span class="statrow__value">' + esc(r.value) + '</span></div>';
      }).join('') + '</div>' +
      (most.length
        ? '<div><p class="sectionlabel">Most Worn</p><ul class="specrows">' +
            most.map(function (m) {
              return '<li class="specrow"><span class="specrow__static">' +
                '<span class="specrow__label">' + esc(m.usedCount) + '回</span>' +
                '<span class="specrow__value">' + esc(m.name) + '</span></span></li>';
            }).join('') + '</ul></div>'
        : '');
    show($('#panel-stats'), true);
  }

  // --- 전신 사진

  /** 입어보기에서 넘어온 경우 원래 코디로 돌아가는 안내를 보여 준다. */
  function renderTryOnResume() {
    var p = state.pendingTryOn;
    var box = $('#body-photo-resume');
    show(box, !!p);
    if (!p) return;

    var hasPhoto = !!(state.user && state.user.bodyPhotoUrl);
    $('#body-photo-resume-msg').textContent = hasPhoto
      ? '全身写真の準備ができました。「' + p.title + '」に戻って試着できます。'
      : '「' + p.title + '」の試着の途中でした。写真を登録すると続きから試せます。';
  }

  $('#btn-body-photo-resume').addEventListener('click', function () {
    var p = state.pendingTryOn;
    if (!p) return;
    state.pendingTryOn = null;
    show($('#body-photo-resume'), false);
    navigate('coord', { id: p.id, title: p.title });
    // 입어보기 버튼으로 스크롤만 한다. AI 호출은 사용자가 누를 때만.
    setTimeout(function () {
      var host = $('#coord-detail');
      var btn = $('[data-action="tryon"]', host) || $('[data-action="ask-tryon-again"]', host);
      if (btn) { scrollIntoViewSafely(btn); btn.focus(); }
      else scrollIntoViewSafely($('[data-tryon]', host));
    }, 320);
  });

  function renderBodyPhoto() {
    var me = state.user;
    var img = $('#body-photo-img');
    var ph = $('#body-photo-placeholder');
    var label = $('.btn__text', $('#body-photo-label'));
    var status = $('#body-photo-status');

    // 스크린 리더용 상태 문구
    if (status) status.textContent = (me && me.bodyPhotoUrl) ? '全身写真は登録済み' : '全身写真は未登録';
    renderTryOnResume();

    if (me && me.bodyPhotoUrl) {
      if (label) label.textContent = '写真を変更';
      api.media.objectUrl(me.bodyPhotoUrl).then(function (url) {
        img.src = url;
        img.hidden = false;
        img.classList.add('is-loaded');
        ph.hidden = true;
      }).catch(function () {
        img.hidden = true;
        ph.hidden = false;
      });
    } else {
      img.hidden = true;
      img.removeAttribute('src');
      ph.hidden = false;
      if (label) label.textContent = '写真を選ぶ';
    }
  }

  $('#body-photo-input').addEventListener('change', function (e) {
    var file = e.target.files && e.target.files[0];
    if (!file) return;
    setNote($('#body-photo-error'), '');

    // 서버 응답을 기다리기 전에 먼저 보여 준다 (실패하면 되돌린다)
    var previousUrl = state.user && state.user.bodyPhotoUrl;
    var localUrl = URL.createObjectURL(file);
    var img = $('#body-photo-img');
    img.src = localUrl;
    img.hidden = false;
    img.classList.add('is-loaded');
    $('#body-photo-placeholder').hidden = true;

    show($('#body-photo-loading'), true);
    $('#body-photo-label').disabled = true;

    api.users.uploadBodyPhoto(file).then(function (res) {
      // 바로 revoke 하면 서버 사진으로 바뀌기 전에 깜빡인다.
      setTimeout(function () { URL.revokeObjectURL(localUrl); }, 2000);
      state.user = state.user || {};
      state.user.bodyPhotoUrl = (res && res.bodyPhotoUrl) || previousUrl;
      // 전신 사진 등록 안내를 지운다.
      if (state.tryOnNote && state.tryOnNote.kind === 'guide') state.tryOnNote = null;
      renderBodyPhoto();
      toast(state.pendingTryOn
        ? '全身写真を保存しました。試着に戻れます。'
        : '全身写真を保存しました。');
      if (state.pendingTryOn) scrollIntoViewSafely($('#body-photo-resume'));
    }).catch(function (err) {
      URL.revokeObjectURL(localUrl);
      if (state.user) state.user.bodyPhotoUrl = previousUrl;
      renderBodyPhoto();
      if (isExpired(err)) return;
      setNote($('#body-photo-error'), humanError(err));
    }).finally(function () {
      show($('#body-photo-loading'), false);
      $('#body-photo-label').disabled = false;
      e.target.value = '';
    });
  });

  // --- 스타일 선호도
  function updateStyleCount() {
    $('#style-count').textContent = String($('#style-input').value.length);
  }
  $('#style-input').addEventListener('input', updateStyleCount);

  $('#form-style').addEventListener('submit', function (e) {
    e.preventDefault();
    setNote($('#style-error'), '');
    var value = $('#style-input').value.trim();
    var done = busy($('#btn-style-save'), '保存しています…');

    api.users.saveStylePreference(value || null).then(function () {
      toast('好みのスタイルを保存しました。');
    }).catch(function (err) {
      if (isExpired(err)) return;
      setNote($('#style-error'), api.isNotDeployed(err)
        ? '今は保存できません。しばらくしてからもう一度お試しください。'
        : humanError(err));
    }).finally(done);
  });

  // --- 표시 이름
  function updateNameCount() {
    $('#name-count').textContent = String($('#name-input').value.length);
  }
  $('#name-input').addEventListener('input', updateNameCount);

  $('#form-name').addEventListener('submit', function (e) {
    e.preventDefault();
    setNote($('#name-error'), '');
    var value = $('#name-input').value.trim();
    if (!value) {
      // 서버도 거절하지만 요청 전에 막는다.
      setNote($('#name-error'), '表示名を入力してください。');
      return;
    }
    var done = busy($('#btn-name-save'), '保存しています…');

    api.users.saveDisplayName(value).then(function (res) {
      var saved = (res && res.displayName) || value;
      // 서버가 자른 값으로 state.user 도 갱신한다.
      if (state.user) state.user.displayName = saved;
      $('#more-name').textContent = saved;
      show($('#more-name'), true);
      $('#name-input').value = saved;
      updateNameCount();
      toast('表示名を保存しました。');
    }).catch(function (err) {
      if (isExpired(err)) return;
      setNote($('#name-error'), api.isNotDeployed(err)
        ? '今は保存できません。しばらくしてからもう一度お試しください。'
        : humanError(err));
    }).finally(done);
  });

  // --- AI 키
  $('#btn-key-reveal').addEventListener('click', function () {
    var input = $('#key-input');
    var shown = input.type === 'text';
    input.type = shown ? 'password' : 'text';
    this.setAttribute('aria-pressed', shown ? 'false' : 'true');
    this.setAttribute('aria-label', shown ? '入力したキーを表示' : '入力したキーを隠す');
    this.innerHTML = icon(shown ? 'eye' : 'eye-off', 'ico--sm');
  });

  $('#form-key').addEventListener('submit', function (e) {
    e.preventDefault();
    setNote($('#key-error'), '');
    var key = $('#key-input').value.trim();
    if (!key) {
      setNote($('#key-error'), '発行したキーを貼り付けてください。');
      $('#key-input').focus();
      return;
    }

    var wasEditing = state.ai.editing;
    var done = busy(e.target.querySelector('button[type=submit]'), '連携しています…');
    api.settings.saveGeminiKey(key).then(function (res) {
      state.ai.configured = !!(res && res.configured);
      state.ai.masked = res && res.masked;
      state.ai.checked = true;
      state.ai.editing = false;
      $('#key-input').value = '';
      renderAiState();
      toast(wasEditing ? '新しいキーに変更しました。' : 'AI連携が完了しました。これでコーデを選んでもらえます。');
    }).catch(function (err) {
      if (isExpired(err)) return;
      setNote($('#key-error'), err.isApiError && err.code === 'invalid_key'
        ? 'キーが正しくありません。コピーした文字を最初から最後まで貼り直してください。'
        : humanError(err));
    }).finally(done);
  });

  /** 키 바꾸기는 화면 모드 전환일 뿐이라 configured 는 건드리지 않는다. */
  $('#btn-key-change').addEventListener('click', function () {
    state.ai.editing = true;
    renderAiState();
    setNote($('#key-error'), '');
    setTimeout(function () { $('#key-input').focus(); }, 60);
  });

  /** 키 변경 취소 */
  $('#btn-key-cancel').addEventListener('click', function () {
    state.ai.editing = false;
    $('#key-input').value = '';
    setNote($('#key-error'), '');
    renderAiState();
    setTimeout(function () { $('#btn-key-change').focus(); }, 60);
  });

  $('#btn-key-ask-remove').addEventListener('click', function () {
    showKeyRemoveConfirm(true);
    $('#btn-key-remove-cancel').focus();
    scrollIntoViewSafely($('#key-remove-confirm'));
  });
  $('#btn-key-remove-cancel').addEventListener('click', function () {
    showKeyRemoveConfirm(false);
    $('#btn-key-ask-remove').focus();
  });

  $('#btn-key-remove').addEventListener('click', function () {
    var done = busy(this, '解除しています…');
    api.settings.removeGeminiKey().then(function () {
      state.ai.configured = false;
      state.ai.masked = null;
      state.ai.editing = false;
      renderAiState();
      toast('連携を解除しました。');
    }).catch(function (err) {
      if (isExpired(err)) return;
      toast(humanError(err), 'error');
    }).finally(done);
  });

  // --- 부팅
  /** 토큰이 있으면 그대로 쓰고, 없으면 api.js 가 세션을 받아 온다. */
  function boot() {
    showBootLoading();
    api.auth.ensure()
      .then(function () { return api.users.me(); })
      .then(function (me) {
        state.user = me;
        showApp();
      })
      .catch(function (err) {
        // 세션이 없으면 전부 401 이므로 부팅 오류 화면에서 멈춘다.
        if (!api.tokens.exists()) {
          showBootError(humanError(err));
          return;
        }
        // 토큰은 있고 첫 요청만 실패한 경우. 앱은 열고 토스트로 알린다.
        showApp();
        toast(humanError(err), 'error');
      });
  }

  $('#btn-boot-retry').addEventListener('click', boot);

  boot();
})();
