/**
 * app.js — 화면 상태와 렌더링.
 *
 * 구조
 *   라우터        루트 탭 4개(홈·옷장·기록·더보기) + 푸시되는 상세 화면 2개.
 *                 뒤로가기는 스택으로 관리하고 브라우저 뒤로가기와도 묶는다.
 *   화면 모듈     home / closet / item / history / more / coord
 *   공통 규칙     alert·confirm 을 쓰지 않는다. 시간이 걸리는 버튼은 즉시 잠근다.
 *                 모든 목록에 로딩·빈 상태·에러 세 가지를 전부 만든다.
 *
 * 색·간격·라운드는 이 파일에 없다. 전부 CSS 커스텀 프로퍼티로 나가 있다.
 */
(function () {
  'use strict';

  var api = window.OrbitApi;

  /* ================================================================
   * 작은 도구들
   * ================================================================ */
  function $(sel, root) { return (root || document).querySelector(sel); }
  function $$(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }

  function esc(v) {
    return String(v == null ? '' : v)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function show(el, on) { if (el) el.hidden = !on; }
  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }

  /**
   * 방금 펼친 것을 눈에 보이는 자리로 데려온다.
   *
   * 삭제 확인 패널은 늘 화면 맨 아래에 열렸고, 버튼이 고정된 탭바에 절반쯤
   * 가려졌다. 스크롤도 저절로 따라가지 않아 "눌렀는데 아무 일도 안 일어났다"로
   * 읽혔다. 탭바에 가리지 않을 여백은 CSS 의 scroll-margin-bottom 이 맡는다.
   */
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

  /* ---------------- 토스트 ---------------- */
  var toastHost = $('#toasts');

  /**
   * <dialog>.showModal() 은 top layer 로 올라가서 z-index 로는 넘을 수 없다.
   * 시트가 열려 있는 동안에는 토스트 컨테이너를 그 시트 안으로 옮겨 둔다.
   */
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

  /* ---------------- 오류 → 사람 말 ---------------- */
  function humanError(err) {
    if (!err) return '알 수 없는 문제가 생겼어요.';
    if (err.name === 'AbortError') return null;
    if (!err.isApiError) return '문제가 생겼어요. 잠시 후 다시 시도해 주세요.';

    switch (err.code) {
      case 'not_enough_clothes': return '상의와 하의를 최소 하나씩 등록해 주세요.';
      case 'no_body_photo': return '입어보기를 하려면 전신 사진이 필요해요.';
      case 'duplicate_email': return '이미 가입된 이메일이에요.';
      case 'invalid_credentials': return '이메일 또는 비밀번호가 올바르지 않아요.';
      case 'session_expired': return '로그인이 만료되었어요. 다시 로그인해 주세요.';
      case 'network': return '서버에 연결할 수 없어요. Orbit 이 켜져 있는지 확인해 주세요.';
      case 'clothes_in_use': return '코디에 쓰인 옷이라 지금은 지울 수 없어요.';
      case 'invalid_key': return '키가 올바르지 않아요. 복사한 글자를 다시 확인해 주세요.';
    }
    switch (err.status) {
      case 0: return '서버에 연결할 수 없어요. Orbit 이 켜져 있는지 확인해 주세요.';
      case 400: return '입력한 내용을 다시 확인해 주세요.';
      case 401: return '로그인이 만료되었어요. 다시 로그인해 주세요.';
      case 403: return '권한이 없어요.';
      case 404: return '찾을 수 없어요. 새로고침 후 다시 시도해 주세요.';
      case 413: return '사진 용량이 너무 커요. 10MB 이하로 올려 주세요.';
      case 503: return 'AI 가 지금 응답하지 않아요. 잠시 후 다시 시도해 주세요.';
    }
    return '문제가 생겼어요. 잠시 후 다시 시도해 주세요.';
  }

  function isExpired(err) { return err && err.isApiError && err.code === 'session_expired'; }

  /* ---------------- 카테고리 ---------------- */
  /**
   * initial 은 사진 없는 옷의 바닥판에 찍히는 글자다.
   *
   * DESIGN.md 는 `TOP / BTM / OUT` 을 적어 뒀지만, `BTM` 과 `OUT` 은 단어가 아니라
   * 개발용 축약이다. 화면에 나가는 글자가 사전에 없는 말이면 그건 디자인이 아니라
   * 미완성으로 읽힌다. 인덱스 라벨의 "넓은 자간 · 작은 대문자 영문" 이라는 성질은
   * 그대로 지키면서 실제 단어로 바꾼다.
   */
  var CATEGORY = {
    TOP:    { label: '상의',   en: 'Top',    initial: 'TOP' },
    BOTTOM: { label: '하의',   en: 'Bottom', initial: 'BOTTOM' },
    OUTER:  { label: '아우터', en: 'Outer',  initial: 'OUTER' }
  };
  function catOf(key) { return CATEGORY[key] || { label: '기타', en: 'Item', initial: 'ITEM' }; }

  var MAX_UPLOAD = 10 * 1024 * 1024;

  /* ---------------- 번호 · 날짜 ---------------- */
  /**
   * 기록물의 일련번호.
   *
   * 예전에는 서버 `id` 를 세 자리로 채워 만들었다. id 는 모든 사용자가 공유하는
   * 전역 시퀀스라, 새 계정의 첫 기록이 `LOOK 005` 로 나왔다. 사용자에게 보여줄
   * 뜻을 가진 값이 아니다. 표시용 번호는 서버가 계정별로 1부터 매겨 저장해 두고
   * `lookNo` 로 내려준다. `id` 는 링크와 API 호출에만 쓴다.
   */
  function lookLabel(c) {
    var n = c && c.lookNo;
    if (typeof n !== 'number' || !isFinite(n)) return 'Look';   // 서버가 못 준 경우
    return 'Look ' + (n < 100 ? ('00' + n).slice(-3) : String(n));
  }
  function dateOf(iso) {
    var d = new Date(iso);
    if (isNaN(d)) return '';
    return d.getFullYear() + '.' + String(d.getMonth() + 1).padStart(2, '0') +
           '.' + String(d.getDate()).padStart(2, '0');
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

  /* ---------------- 이미지 ---------------- */
  /**
   * src 대신 data-src 로 심어 둔 이미지를 실제로 채운다.
   * 서버가 /media/** 를 인증된 소유자에게만 내주기 때문에, 브라우저가 알아서 거는
   * 요청(<img src>)으로는 토큰을 보낼 수 없다. innerHTML 로 그린 직후 불러 준다.
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

  // 깨진 이미지는 조용히 걷어낸다. (error 는 버블링하지 않아 캡처로 듣는다)
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

  /**
   * 코디 프레임 3:4.
   * 입어본 사진이 있으면 그것이 주인공이고, 없으면 구성 옷 사진을
   * 가로로 이어 붙인 스트립으로 대신한다.
   */
  function tryOnFrameHtml(c, extra) {
    return '<span class="frame frame--look"' + (extra || '') + '>' +
      imgTag(c.tryOnImageUrl, 'AI 가 만든 입어본 모습') + '</span>';
  }

  /** 원본 옷 사진 스트립. 가상 착용 결과가 있어도 이 그림은 사라지지 않아야 한다. */
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

  /**
   * 사진 위 pill.
   *
   * 입어본 사진은 AI 가 만들어 낸 그림이지 사용자가 실제로 입은 모습이 아니다.
   * 아무 표시가 없으면 기록에서 훑을 때 "그날 그렇게 입었다"로 읽힌다.
   * 그래서 생성 이미지가 주인공인 자리에서는 pill 이 그 사실을 먼저 말한다.
   */
  function aiPillHtml(c) {
    return '<span class="pill" data-ai-pill><span class="pill__dot" aria-hidden="true"></span>' +
      (c && c.tryOnImageUrl ? 'AI 생성 이미지' : 'AI 추천') + '</span>';
  }

  function favBtnHtml(c) {
    return '<button class="photobtn' + (c.favorite ? ' is-on' : '') + '" type="button" ' +
      'data-fav="' + esc(c.id) + '" aria-pressed="' + (c.favorite ? 'true' : 'false') + '" ' +
      'aria-label="즐겨찾기">' + icon(c.favorite ? 'heart-fill' : 'heart') + '</button>';
  }

  /** LOOK 카드 — 이 앱의 얼굴 */
  function lookHtml(c) {
    var items = sortedItems(c);
    var title = c.title || '오늘의 코디';
    return '<article class="look" data-id="' + esc(c.id) + '">' +
      '<div class="look__head">' +
        '<span class="indexlabel">' + esc(lookLabel(c)) + '</span>' +
        '<span class="num">' + esc(stampOf(c.createdAt)) + '</span>' +
      '</div>' +
      '<div class="look__figure">' +
        '<button class="look__open" type="button" data-coord-open="' + esc(c.id) + '" ' +
                'data-coord-title="' + esc(title) + '" aria-label="' + esc(title) + ' 자세히 보기">' +
          lookFrameHtml(c) + aiPillHtml(c) +
        '</button>' +
        favBtnHtml(c) +
      '</div>' +
      '<h3 class="look__title">' + esc(title) + '</h3>' +
      '<p class="look__items">' + esc(items.map(function (i) { return i.name; }).join(' · ')) + '</p>' +
      // 좁은 2열 카드에서는 머리의 날짜가 번호와 부딪힌다. 그때만 이 줄이 대신 나온다.
      '<p class="look__stamp num">' + esc(stampOf(c.createdAt)) + '</p>' +
    '</article>';
  }

  /**
   * 축소판 LOOK 카드 — 옷 상세의 "이 옷이 쓰인 코디" 자리에서만 쓴다.
   * 목록 화면의 큰 카드를 그대로 쓰면 카드 하나가 화면을 채워, 코디가 서너 개만
   * 돼도 이 자리에서 스크롤이 끝없이 길어졌다. 여기서 필요한 건 "어디에 썼나"
   * 를 훑는 것뿐이라, 가로로 넘기는 작은 카드로 줄인다.
   */
  function miniLookHtml(c) {
    var title = c.title || '오늘의 코디';
    return '<button class="minilook" type="button" data-coord-open="' + esc(c.id) + '" ' +
            'data-coord-title="' + esc(title) + '">' +
      lookFrameHtml(c) +
      '<span class="indexlabel minilook__no">' + esc(lookLabel(c)) + '</span>' +
      '<span class="minilook__title">' + esc(title) + '</span>' +
      '<span class="num minilook__date">' + esc(dateOf(c.createdAt)) + '</span>' +
    '</button>';
  }

  /* ================================================================
   * 상태
   * ================================================================ */
  var state = {
    user: null,
    /**
     * configured 는 **서버가 말한 사실**이다. 화면이 덮어쓰면 안 된다.
     * 예전에는 `키 바꾸기` 가 configured 를 false 로 만들어, 서버는 여전히
     * 연결되어 있는데 앱 전체가 "AI 안 됨" 으로 바뀌고 빠져나올 수 없었다.
     * "지금 키를 다시 입력하는 중" 은 화면의 사정이므로 editing 으로 따로 둔다.
     */
    ai: { configured: false, masked: null, checked: false, editing: false },

    closet: { items: [], page: 0, totalPages: 0, totalElements: 0, filter: 'ALL', loaded: false },
    home: { today: [], recent: [], loaded: false, lookCount: 0 },
    history: { items: [], page: 0, totalPages: 0, totalElements: 0, filter: 'ALL', loaded: false },
    coord: { id: null, data: null, media: 'tryon' },
    item: { id: null, data: null, usedIn: [], usedInUnavailable: false },
    stats: { data: null, loaded: false },

    /** '입어보기' → 전신 사진 등록으로 보낼 때, 돌아올 자리를 적어 둔다. */
    pendingTryOn: null,

    addImage: null,
    analyzeAbort: null,
    /** 옷 정보 수정 중이면 그 옷의 id. null 이면 새 옷 등록이다. */
    editItemId: null
  };

  /* ================================================================
   * 라우터
   * ================================================================ */
  var VIEWS = {
    home:    { el: '#view-home',    root: true,  title: '홈' },
    closet:  { el: '#view-closet',  root: true,  title: '내 옷장' },
    history: { el: '#view-history', root: true,  title: '코디 기록' },
    more:    { el: '#view-more',    root: true,  title: '더보기' },
    coord:   { el: '#view-coord',   root: false, title: '코디' },
    item:    { el: '#view-item',    root: false, title: '옷' }
  };

  var stack = [{ name: 'home', params: {}, scroll: 0 }];

  function current() { return stack[stack.length - 1]; }

  /**
   * 주소가 지금 보고 있는 화면을 그대로 담는다.
   * 예전에는 URL 이 늘 `/` 여서 코디 상세를 보다 새로고침하면 홈으로 튕겼다.
   * 해시 라우팅이라 서버 라우팅 설정 없이 새로고침·즐겨찾기·공유가 다 산다.
   */
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
   * 우리가 이 문서 안에서 몇 번째 기록에 있는지.
   * 0 이면 "이 앱이 만든 앞 기록이 없다" — 주소로 상세 화면에 곧장 들어온 경우다.
   * 이때 history.back() 을 부르면 앱 밖으로 나가 버리므로 뒤로가기를 직접 처리한다.
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
    // 주소로 상세 화면에 곧장 들어온 경우. 브라우저 기록을 건드리면 앱 밖으로
    // 나가 버리므로, 스택만 한 칸 내리고 주소를 덮어쓴다.
    if (stack.length > 1) {
      stack.pop();
      syncUrl(true);
      applyRoute({ restoreScroll: true });
      return;
    }
    if (current().name !== 'home') navigate('home');
  }

  /**
   * 뒤로·앞으로·주소 직접 수정을 모두 여기서 받는다.
   * 스택을 무조건 pop 하지 않고 "주소가 가리키는 화면"에 스택을 맞춘다.
   * 그래야 앞으로가기와 주소 직접 입력에서도 화면과 URL 이 어긋나지 않는다.
   */
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

  /* ================================================================
   * 셸 전환
   * ================================================================ */
  function showAuth(notice) {
    show($('#app-shell'), false);
    show($('#screen-auth'), true);
    show($('#boot'), false);
    setNote($('#auth-notice'), notice || '');
    authError('');
    resetState();
  }

  function resetState() {
    state.user = null;
    state.ai = { configured: false, masked: null, checked: false, editing: false };
    state.closet = { items: [], page: 0, totalPages: 0, totalElements: 0, filter: 'ALL', loaded: false };
    state.home = { today: [], recent: [], loaded: false, lookCount: 0 };
    state.history = { items: [], page: 0, totalPages: 0, totalElements: 0, filter: 'ALL', loaded: false };
    state.coord = { id: null, data: null, media: 'tryon' };
    state.item = { id: null, data: null, usedIn: [], usedInUnavailable: false };
    state.stats = { data: null, loaded: false };
    state.pendingTryOn = null;
    state.editItemId = null;
    moreLoaded = false;
    stack = [{ name: 'home', params: {}, scroll: 0 }];
    releaseLocalPreviews();
  }

  function showApp() {
    show($('#screen-auth'), false);
    show($('#app-shell'), true);
    show($('#boot'), false);
    // 주소가 가리키는 화면에서 시작한다. 새로고침해도 보던 자리로 돌아온다.
    stack = stackFor(parseHash(window.location.hash) || { name: 'home', params: {} });
    syncUrl(true);
    applyRoute();
    loadAiState();
  }

  api.onSessionExpired(function () {
    showAuth('로그인이 만료되었어요. 다시 로그인해 주세요.');
  });

  /* ================================================================
   * 로그인 / 가입
   * ================================================================ */

  /**
   * 오류 문구와 그 옆의 출구 버튼을 함께 다룬다.
   * 로그인에 실패하는 사람의 상당수는 "비밀번호를 틀린 사람"이 아니라
   * "아직 계정이 없는 사람"이다. 문구만 주고 끝내면 그 사람은 막힌다.
   */
  function authError(msg, action) {
    var box = $('#auth-error');
    var btn = $('#btn-auth-error-action');
    setNote(box, msg, $('#auth-error-msg'));
    if (msg && action) {
      btn.textContent = action.label;
      btn.hidden = false;
      btn.onclick = action.run;
    } else {
      btn.hidden = true;
      btn.onclick = null;
    }
  }

  /** 탭을 바꾼다. 이미 친 이메일은 그대로 옮겨 준다. */
  function setAuthTab(mode) {
    $$('[data-auth-tab]').forEach(function (t) {
      var on = t.dataset.authTab === mode;
      t.classList.toggle('is-active', on);
      t.setAttribute('aria-selected', on ? 'true' : 'false');
    });
    // 탭을 바꿨다고 방금 친 이메일까지 사라지면 두 번 치게 된다.
    var from = mode === 'signup' ? $('#login-email') : $('#signup-email');
    var to   = mode === 'signup' ? $('#signup-email') : $('#login-email');
    if (from.value.trim() && !to.value.trim()) to.value = from.value.trim();

    show($('#form-login'), mode === 'login');
    show($('#form-signup'), mode === 'signup');
    authError('');
    setNote($('#auth-notice'), '');
  }

  $$('[data-auth-tab]').forEach(function (tab) {
    tab.addEventListener('click', function () { setAuthTab(tab.dataset.authTab); });
  });

  /** 비밀번호 보기/가리기. 키 입력칸과 같은 방식을 로그인·가입에도 쓴다. */
  $$('[data-reveal]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var input = document.getElementById(btn.dataset.reveal);
      if (!input) return;
      var shown = input.type === 'text';
      input.type = shown ? 'password' : 'text';
      btn.setAttribute('aria-pressed', shown ? 'false' : 'true');
      btn.setAttribute('aria-label', shown ? '비밀번호 보기' : '비밀번호 가리기');
      btn.innerHTML = icon(shown ? 'eye' : 'eye-off', 'ico--sm');
    });
  });

  $('#form-login').addEventListener('submit', function (e) {
    e.preventDefault();
    var email = $('#login-email').value.trim();
    var password = $('#login-password').value;
    authError('');

    if (!email || !password) {
      authError('이메일과 비밀번호를 모두 입력해 주세요.');
      return;
    }
    var done = busy(e.target.querySelector('button[type=submit]'), '로그인 중…');
    api.auth.login(email, password)
      .then(bootAfterLogin)
      .catch(function (err) {
        var noAccount = err && err.isApiError && err.code === 'invalid_credentials';
        authError(humanError(err) || '로그인에 실패했어요.', noAccount ? {
          label: '계정이 없나요? 가입하기',
          run: function () {
            setAuthTab('signup');
            $('#signup-password').focus();
          }
        } : null);
      })
      .finally(done);
  });

  $('#form-signup').addEventListener('submit', function (e) {
    e.preventDefault();
    var email = $('#signup-email').value.trim();
    var password = $('#signup-password').value;
    var confirm = $('#signup-password2').value;
    authError('');

    if (!email || !password) {
      authError('이메일과 비밀번호를 모두 입력해 주세요.');
      return;
    }
    if (password.length < 8) {
      authError('비밀번호는 8자 이상으로 정해 주세요.');
      $('#signup-password').focus();
      return;
    }
    // 오타를 다음 로그인에서 알게 되는 것이 이 화면의 유일한 함정이었다.
    if (password !== confirm) {
      authError('두 비밀번호가 서로 달라요. 다시 확인해 주세요.');
      $('#signup-password2').focus();
      return;
    }
    var done = busy(e.target.querySelector('button[type=submit]'), '가입 중…');
    api.auth.signup(email, password)
      // 가입 직후 바로 로그인시킨다. 방금 친 비밀번호를 또 치게 하지 않는다.
      .then(function () { return api.auth.login(email, password); })
      .then(bootAfterLogin)
      .catch(function (err) {
        var dup = err && err.isApiError && err.code === 'duplicate_email';
        authError(humanError(err) || '가입에 실패했어요.', dup ? {
          label: '로그인하러 가기',
          run: function () { setAuthTab('login'); $('#login-password').focus(); }
        } : null);
      })
      .finally(done);
  });

  function bootAfterLogin() {
    return api.users.me().then(function (me) {
      state.user = me;
      showApp();
    }).catch(function (err) {
      if (err.isApiError && err.status === 401) return;
      showApp();
      toast(humanError(err), 'error');
    });
  }

  /* ================================================================
   * AI 연결 (Gemini 키)
   * ================================================================ */
  function loadAiState() {
    return api.settings.geminiKey().then(function (res) {
      state.ai.configured = !!(res && res.configured);
      state.ai.masked = res && res.masked;
      state.ai.checked = true;
      // 서버가 새로 답했으면 화면의 편집 모드는 뜻을 잃는다.
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
    // 연결돼 있어도 "키를 다시 입력하는 중" 이면 입력 폼을 보여 준다.
    // 이때도 configured 는 여전히 true 다 — 서버의 사실은 바뀌지 않았다.
    var editing = connected && state.ai.editing;

    show($('#key-connected'), connected && !editing);
    show($('#key-setup'), !connected || editing);
    if (connected) $('#key-masked').textContent = state.ai.masked || '';

    // 취소는 되돌아갈 자리가 있을 때만 뜻이 있다 (= 이미 연결돼 있을 때).
    show($('#btn-key-cancel'), editing);
    var submitText = $('.btn__text', $('#form-key button[type=submit]'));
    if (submitText) submitText.textContent = editing ? '새 키로 바꾸기' : '연결하기';

    // 해제 확인은 패널을 다시 그릴 때마다 접어 둔다.
    showKeyRemoveConfirm(false);

    // 홈의 문구는 **서버가 말한 사실**만 따른다. 화면의 편집 모드가 섞이면
    // 키를 바꾸려 눌렀을 뿐인데 앱 전체가 "AI 안 됨" 으로 바뀐다.
    var sub = $('#recommend-sub');
    if (sub) {
      sub.textContent = (state.ai.checked && !state.ai.configured)
        ? 'AI 연결을 마치면 바로 쓸 수 있어요'
        : '옷장을 보고 오늘의 조합을 골라 드려요';
    }
  }

  function showKeyRemoveConfirm(on) {
    show($('#key-remove-confirm'), on);
    show($('#btn-key-ask-remove'), !on);
  }

  var guideSheet = $('#guide-sheet');

  /**
   * AI 를 아직 연결하지 않았을 때는 오류가 아니라 안내다.
   * 무엇이 필요한지 알려 주고, 그 화면으로 데려간다.
   */
  function openGuide(opts) {
    opts = opts || {};
    $('#guide-title').textContent = opts.title || 'AI 연결이 아직 안 되어 있어요';
    $('#guide-desc').textContent = opts.desc ||
      '코디를 골라 주려면 Google AI Studio에서 받는 무료 키가 하나 필요해요. 더보기 화면에서 1분이면 끝나요.';
    $('.btn__text', $('#btn-guide-go')).textContent = opts.action || '연결하러 가기';
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

  /* ================================================================
   * 홈
   * ================================================================ */
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
      // 옷장이 비었는지 알아야 홈에서 무엇을 권할지 정할 수 있다
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
      // 지금까지 남긴 LOOK 이 몇 장인지. 기록 API 가 없으면 오늘 것만 셀 수밖에 없다.
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
          '<h2 class="empty__title">오늘의 기록이 아직 없어요</h2>' +
          '<p class="empty__desc">' + (closetEmpty
            ? '옷을 먼저 등록하면 오늘 입을 조합을 만들어 드려요.'
            : '추천을 받으면 오늘 입을 조합이 여기에 남아요.') + '</p>' +
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

  /**
   * 홈의 옷장 색인.
   *
   * 이 앱에서 다시 열 이유가 되는 유일한 물건이 옷장 통계인데, 더보기 → 스크롤 →
   * 맨 아래 패널로 3단계 깊이에 묻혀 있었다. "기록이 쌓이는 것 자체가 이 앱의
   * 시각적 보상" 이라면 그것이 첫 화면에 있어야 한다.
   */
  function renderHomeIndex() {
    var s = state.stats.data;
    var looks = state.home.lookCount || 0;
    var total = (s && s.total != null) ? s.total : state.closet.totalElements;
    var never = (s && s.neverUsed != null) ? s.neverUsed : null;

    // 아무것도 쌓이지 않았으면 0 을 세 개 늘어놓지 않는다. 빈 상태가 이미 말하고 있다.
    var meaningful = looks > 0 || (total || 0) > 0;
    show($('#home-index-section'), meaningful);
    if (!meaningful) return;

    var cells = [
      { label: 'Looks', value: looks, unit: '개', to: 'history' },
      { label: 'Wardrobe', value: total || 0, unit: '벌', to: 'closet' }
    ];
    if (never != null) cells.push({ label: 'Never worn', value: never, unit: '벌', to: 'closet' });

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

  /* ---------------- 추천받기 (409 자동 재시도) ---------------- */
  var MAX_DUP_RETRY = 3;
  /** 이보다 오래 걸리면 "왜 안 끝나는지" 를 한 줄 더 말해 준다. */
  var SLOW_HINT_MS = 6000;

  var recSlowTimer = null;
  var recAttempt = 0;
  var recProgress = null;

  /**
   * 추천 대기.
   *
   * 같은 앱의 입어보기는 진행 바 + 단계 문구로 기다리게 하는데, 추천은 7초 동안
   * 스피너 하나뿐이었다. 기다림의 질이 화면마다 다를 이유가 없다. 같은 부품을 쓴다.
   */
  var REC_STAGES = [
    [0,  '옷장을 펼치는 중…'],
    [22, '오늘 입을 만한 옷을 추리는 중…'],
    [50, '색과 소재를 맞춰 보는 중…'],
    [75, '고른 이유를 적는 중…'],
    [92, '거의 다 됐어요…']
  ];

  function recStatus(msg) {
    $('#home-retry-msg').textContent = msg;
    show($('#home-retry'), true);
  }

  function recArmSlowHint() {
    clearTimeout(recSlowTimer);
    recSlowTimer = setTimeout(function () {
      recStatus('생각보다 오래 걸리고 있어요. 조금만 더 기다려 주세요.');
    }, SLOW_HINT_MS);
  }

  function recStart() {
    recStop();
    show($('#home-progress'), true);
    recProgress = startProgress($('#home-progress-fill'), $('#home-progress-label'), REC_STAGES);
    recArmSlowHint();
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
    var done = busy(btn, '고르는 중…');
    setNote($('#home-error'), '', $('#home-error-msg'));
    show($('#home-error-action'), false);
    recStart();

    recommendWithRetry(function (attempt) {
      // 서버가 409(이미 입은 조합)를 주면 클라이언트가 같은 요청을 다시 보낸다.
      // 그동안 버튼이 "고르는 중…" 에 머물러 있으면 사용자는 그냥 느린 줄 안다.
      // 무엇을 다시 하고 있는지, 몇 번째인지를 진행 바 문구로 꺼낸다.
      recAttempt = attempt;
      if (recProgress) {
        recProgress.hold('같은 조합이 나와서 다시 고르는 중… (' + attempt + '/' + MAX_DUP_RETRY + ')');
      }
      recArmSlowHint();
    }).then(function (created) {
      if (recProgress) recProgress.finish('골랐어요!');
      clearTimeout(recSlowTimer);
      show($('#home-retry'), false);
      state.history.loaded = false;
      state.stats.loaded = false;
      // 서버가 오늘 목록의 주인이다. 로컬에서 합치지 않고 다시 읽는다.
      return loadHome().then(function () {
        recStop();
        toast('오늘의 코디를 골랐어요.');
        if (created && created.id) openCoord(created.id, created.title);
      });
    }).catch(function (err) {
      recStop();
      if (isExpired(err)) return;

      if (err && err.code === 'duplicate_exhausted') {
        homeError('오늘 나올 수 있는 조합을 다 봤어요. 옷을 더 등록하면 새로운 조합이 생겨요.', {
          label: '옷 추가하기', run: function () { navigate('closet'); openAddSheet(); }
        });
        return;
      }
      if (err.isApiError && err.code === 'not_enough_clothes') {
        homeError(humanError(err), {
          label: '옷 등록하기', run: function () { navigate('closet'); openAddSheet(); }
        });
        return;
      }
      if (err.isApiError && err.status === 503) {
        openGuide({
          title: 'AI 가 지금 응답하지 않아요',
          desc: '연결한 키가 아직 살아 있는지 확인해 보고, 잠시 후 다시 시도해 주세요.',
          action: '연결 확인하러 가기'
        });
        return;
      }
      homeError(humanError(err));
    }).finally(done);
  });

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
   * 409 {error:"duplicate", retry:true} 는 "같은 요청을 다시 보내면 다른 조합이
   * 나올 수 있다"는 뜻이다. 최대 3회, 1초 간격으로 자동 재시도한다.
   * 3회 다 실패하면 더 시도해도 소용없다고 보고 사람에게 넘긴다.
   */
  function recommendWithRetry(onRetry) {
    function attempt(n) {
      return api.coordinations.recommend().catch(function (err) {
        if (!(err.isApiError && err.status === 409)) throw err;
        if (n >= MAX_DUP_RETRY) {
          var exhausted = new Error('duplicate_exhausted');
          exhausted.code = 'duplicate_exhausted';
          throw exhausted;
        }
        onRetry(n + 1);
        return sleep(1000).then(function () { return attempt(n + 1); });
      });
    }
    return attempt(0);
  }

  /* ================================================================
   * 옷장
   * ================================================================ */
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
    var done = first ? function () {} : busy($('#btn-load-more'), '불러오는 중…');

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
      // 섹션 라벨의 영문은 디자인이지만, 개수를 세는 문장은 사용자의 말이어야 한다.
      $('#closet-count').textContent = state.closet.filter === 'ALL'
        ? state.closet.totalElements + '벌'
        : catOf(state.closet.filter).label + ' ' + items.length + '벌';
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

  /* ================================================================
   * 옷 상세
   * ================================================================ */
  function openItem(id, title) { navigate('item', { id: id, title: title || '옷' }); }

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
      $('#topbar-title').textContent = data.name || '옷';
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

    var rows = [
      { label: 'Category', value: c.label },
      { label: 'Color', value: d.color },
      { label: 'Fabric · Fit', value: d.detail },
      { label: 'Added', value: d.createdAt ? dateOf(d.createdAt) : null }
    ].filter(function (r) { return r.value; });

    var usedIn = state.item.usedIn;
    var usedInBlock = usedIn.length
      ? '<div class="minilooks">' + usedIn.map(miniLookHtml).join('') + '</div>'
      : '<div class="empty empty--inline"><p class="empty__desc">' +
          (state.item.usedInUnavailable ? '아직 정리된 코디가 없어요.' : '이 옷이 쓰인 코디가 아직 없어요.') +
        '</p></div>';

    $('#item-detail').innerHTML =
      '<div class="detail">' +
        // pill 은 AI 가 개입한 자리를 뜻한다. 바로 아래 인덱스 라벨이 같은 말을
        // 이미 하고 있으므로 옷 사진에는 얹지 않는다.
        '<div class="detail__media">' + itemFrameHtml(d, 'frame--item') + '</div>' +

        '<div class="detail__head">' +
          '<p class="indexlabel">' + esc(c.en) + '</p>' +
          '<h2 class="detail__title">' + esc(d.name) + '</h2>' +
        '</div>' +

        '<section class="section">' +
          '<p class="sectionlabel">Details</p>' +
          '<ul class="specrows">' + rows.map(specRowHtml).join('') + '</ul>' +
          // 이 앱은 옷 정보를 AI 가 대신 채운다. 실제로 바지를 "민소매 티셔츠 · 상의"
          // 로 분류한 적이 있다. 자동 입력을 쓰면서 고칠 길이 없으면, 잘못 읽힌 옷은
          // 지우고 다시 올리는 수밖에 없다. 저장 뒤에도 고칠 수 있어야 한다.
          '<button class="btn btn--ghost btn--block item__edit" type="button" data-action="edit-item">' +
            icon('pencil', 'ico--sm') + '<span class="btn__text">정보 수정</span>' +
          '</button>' +
        '</section>' +

        '<section class="section">' +
          '<p class="sectionlabel">Worn In</p>' +
          '<p class="section__sub">이 옷이 쓰인 코디' +
            (usedIn.length ? ' ' + usedIn.length + '건' : '') + '</p>' +
          usedInBlock +
        '</section>' +

        '<div class="detail__foot">' + deleteBlockHtml(
            // 이미 코디에 쓰인 옷은 지워도 기록에서 사라지지 않는다. 그 사실을
            // 미리 말해 주지 않으면 사용자는 기록까지 지워질까 봐 못 지우거나,
            // 지운 뒤 기록에 남아 있는 것을 보고 안 지워졌다고 생각한다.
            usedIn.length
              ? '이 옷은 코디 ' + usedIn.length + '건에 쓰였어요. 옷장에서는 사라지지만 지난 기록에는 그대로 남습니다.'
              : '이 옷을 옷장에서 지울까요?',
            'delete-item') + '</div>' +
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
        icon('trash', 'ico--sm') + '<span class="btn__text">삭제</span></button>' +
      '<div class="confirm" role="alertdialog" aria-label="삭제 확인" hidden>' +
        '<p class="confirm__q">' + esc(question) + '</p>' +
        '<div class="confirm__actions">' +
          '<button class="btn btn--ghost btn--tiny" data-action="cancel-delete" type="button">취소</button>' +
          '<button class="btn btn--danger btn--tiny" data-action="' + esc(action) + '" type="button">' +
            '<span class="btn__spinner" aria-hidden="true"></span><span class="btn__text">삭제</span></button>' +
        '</div>' +
      '</div>' +
    '</div>';
  }

  function errorStateHtml(msg, retryId) {
    return '<div class="state state--error" role="alert">' +
      '<p class="state__title">' + esc(msg) + '</p>' +
      '<button class="btn btn--ghost" id="' + esc(retryId) + '" type="button">다시 불러오기</button></div>';
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
      // 확인 패널은 화면 맨 아래에 열린다. 스크롤을 따라가지 않으면 탭바에 가린다.
      scrollIntoViewSafely(confirmBox);
    } else if (btn.dataset.action === 'cancel-delete') {
      show(confirmBox, false);
      show(ask, true);
      if (ask) ask.focus();
    }
  });

  $('#item-detail').addEventListener('click', function (e) {
    if (e.target.closest('[data-action="edit-item"]')) {
      if (state.item.data) openEditSheet(state.item.data);
      return;
    }

    var del = e.target.closest('[data-action="delete-item"]');
    if (del) {
      var done = busy(del, '삭제 중…');
      api.clothes.remove(state.item.id).then(function () {
        state.closet.loaded = false;
        state.home.loaded = false;
        state.stats.loaded = false;
        toast('옷장에서 지웠어요.');
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

  /* ================================================================
   * 기록
   * ================================================================ */
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
      $('#history-empty-title').textContent = isFav ? '즐겨찾기한 코디가 없어요' : '아직 기록이 없어요';
      $('#history-empty-desc').textContent = isFav
        ? '마음에 드는 코디의 하트를 누르면 여기에 모여요.'
        : '추천을 받으면 여기에 한 장씩 쌓여요.';
      show($('#btn-history-go-home'), !isFav);
    }

    show($('#history-count'), items.length > 0);
    if (items.length > 0) {
      $('#history-count').textContent = isFav
        ? items.length + '개 즐겨찾기'
        : (state.history.totalElements || items.length) + '개';
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

  /* ---------------- 즐겨찾기 ---------------- */
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
      toast(api.isNotDeployed(err) ? '즐겨찾기는 아직 저장되지 않아요.' : humanError(err), 'error');
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

  /* ================================================================
   * 코디 상세
   * ================================================================ */
  function openCoord(id, title) { navigate('coord', { id: id, title: title || '코디' }); }

  function onCoordEnter(id) {
    var host = $('#coord-detail');
    var cached = findCoord(id);

    if (state.coord.id !== String(id)) {
      // 다른 코디로 넘어가면 사진 전환은 기본값(입어본 모습)으로 돌아간다.
      state.coord = { id: String(id), data: cached || null, media: 'tryon' };
      host.innerHTML = '';
    }
    if (state.coord.data) renderCoord();
    else show($('#coord-skeleton'), true);

    // 목록에 있던 값은 요약일 수 있으니 항상 한 번 더 읽어 최신으로 맞춘다
    api.coordinations.get(id).then(function (data) {
      state.coord.data = data;
      renderCoord();
    }).catch(function (err) {
      if (isExpired(err)) return;
      if (state.coord.data) return;   // 캐시로라도 보이면 조용히 넘어간다
      host.innerHTML = errorStateHtml(humanError(err), 'coord-retry');
    }).finally(function () {
      show($('#coord-skeleton'), false);
    });
  }

  /**
   * 입어보기 자리.
   *
   * 예전에는 사진 우하단의 라벨 없는 ✦ 아이콘 하나가 유일한 진입점이었다.
   * 이 앱의 하이라이트 기능인데 아이콘만으로는 무엇인지 알 수 없었고,
   * 결과가 생기면 그 버튼이 DOM 에서 통째로 사라져 다시 만들 길도 없었다.
   * 그래서 (1) 글자가 있는 버튼으로 바꾸고 (2) 결과가 있어도 "다시 만들기" 를 남긴다.
   * 재생성은 AI 를 한 번 더 부르는 일이라 확인을 한 번 받는다.
   */
  function tryOnBlockHtml(c, items) {
    var hasResult = !!c.tryOnImageUrl;
    // 사진이 없는 옷은 이름만으로 그려진다. 결과가 실제 옷과 달라지는 가장 큰 이유다.
    var noPhoto = items.filter(function (it) { return !it.imageUrl; });

    return '<div class="tryon" data-tryon>' +
      (hasResult
        ? '<button class="btn btn--ghost btn--block" type="button" data-action="ask-tryon-again">' +
            icon('sparkle', 'ico--sm accent') +
            '<span class="btn__text">다시 만들기</span>' +
          '</button>'
        : '<button class="btn btn--primary btn--block" type="button" data-action="tryon">' +
            '<span class="btn__spinner" aria-hidden="true"></span>' +
            icon('sparkle', 'ico--sm') +
            '<span class="btn__text">입어보기</span>' +
          '</button>') +
      (hasResult ? '' : '<p class="tryon__hint">등록한 전신 사진에 이 코디를 입혀 봐요.</p>') +

      // 막지는 않는다. 결과가 왜 실제와 다를 수 있는지만 미리 알려 준다.
      (noPhoto.length
        ? '<div class="note note--warn">사진이 없는 옷(' +
            esc(noPhoto.map(function (it) { return it.name; }).join(', ')) +
            ')은 이름만 보고 그리기 때문에 실제와 다르게 그려질 수 있어요.</div>'
        : '') +

      '<div class="confirm confirm--quiet" role="alertdialog" aria-label="다시 만들기 확인" data-tryon-again hidden>' +
        '<p class="confirm__q">AI 를 한 번 더 불러 새로 만들어요. 지금 이미지는 새 이미지로 바뀝니다.</p>' +
        '<div class="confirm__actions">' +
          '<button class="btn btn--ghost btn--tiny" type="button" data-action="cancel-tryon-again">취소</button>' +
          '<button class="btn btn--primary btn--tiny" type="button" data-action="tryon">' +
            '<span class="btn__spinner" aria-hidden="true"></span>' +
            '<span class="btn__text">다시 만들기</span>' +
          '</button>' +
        '</div>' +
      '</div>' +

      // 생성 결과가 남으로 나오는 일이 있는데, 지우는 길이 룩 전체 삭제뿐이었다.
      // 착용 사진 하나만 되돌릴 수 있어야 기록을 버리지 않고 다시 시도한다.
      (hasResult
        ? '<button class="btn btn--quiet btn--danger-text btn--block" type="button" data-action="ask-del-tryon">' +
            icon('trash', 'ico--sm') + '<span class="btn__text">이 착용 사진만 지우기</span>' +
          '</button>' +
          '<div class="confirm" role="alertdialog" aria-label="착용 사진 삭제 확인" data-del-tryon hidden>' +
            '<p class="confirm__q">이 착용 사진만 지울까요? 코디 기록과 옷은 그대로 남아요.</p>' +
            '<div class="confirm__actions">' +
              '<button class="btn btn--ghost btn--tiny" type="button" data-action="cancel-del-tryon">취소</button>' +
              '<button class="btn btn--danger btn--tiny" type="button" data-action="delete-tryon">' +
                '<span class="btn__spinner" aria-hidden="true"></span>' +
                '<span class="btn__text">사진만 지우기</span>' +
              '</button>' +
            '</div>' +
          '</div>'
        : '') +

      '<div class="progress" hidden>' +
        '<div class="progress__track"><div class="progress__fill"></div></div>' +
        '<p class="progress__label" role="status">준비하는 중…</p>' +
      '</div>' +
      '<div class="note note--error tryon__error" role="alert" hidden></div>' +
    '</div>';
  }

  /* ---------------- 코디 상세의 사진 자리 ---------------- */
  var MEDIA_NOTE = {
    tryon: 'AI 가 만든 이미지예요. 실제로 입은 모습이 아닙니다.',
    items: '등록한 옷 사진 그대로예요.'
  };

  /**
   * 가상 착용 결과가 생기면 홈·기록·상세의 썸네일이 전부 생성 이미지로 바뀌고,
   * 내가 올린 옷 사진을 다시 볼 방법이 없었다. 상세에서만은 둘을 오갈 수 있게 한다.
   */
  function coordMediaHtml(c) {
    var hasTryOn = !!c.tryOnImageUrl;
    var which = hasTryOn ? (state.coord.media || 'tryon') : 'items';

    var panes = (hasTryOn ? tryOnFrameHtml(c, ' data-media-pane="tryon"' + (which === 'tryon' ? '' : ' hidden')) : '') +
      itemsFrameHtml(c, ' data-media-pane="items"' + (which === 'items' ? '' : ' hidden'));

    return '<div class="detail__media">' + panes +
        '<span class="pill" data-ai-pill><span class="pill__dot" aria-hidden="true"></span>' +
          (which === 'tryon' ? 'AI 생성 이미지' : 'AI 추천') +
        '</span>' +
      '</div>' +
      (hasTryOn
        ? '<div class="mediaswitch">' +
            '<div class="segmented" role="group" aria-label="사진 종류">' +
              '<button class="segmented__item' + (which === 'tryon' ? ' is-active' : '') + '" type="button" ' +
                'data-media-tab="tryon" aria-pressed="' + (which === 'tryon') + '">입어본 모습</button>' +
              '<button class="segmented__item' + (which === 'items' ? ' is-active' : '') + '" type="button" ' +
                'data-media-tab="items" aria-pressed="' + (which === 'items') + '">내 옷 사진</button>' +
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
        (which === 'tryon' ? 'AI 생성 이미지' : 'AI 추천');
    }
    var note = $('[data-media-note]', host);
    if (note) note.textContent = MEDIA_NOTE[which] || '';
  }

  function renderCoord() {
    var c = state.coord.data;
    if (!c) return;
    var items = sortedItems(c);

    $('#topbar-title').textContent = c.title || '코디';

    var action = $('#btn-topbar-action');
    show(action, true);
    action.dataset.favId = c.id;
    action.setAttribute('aria-label', '즐겨찾기');
    action.setAttribute('aria-pressed', c.favorite ? 'true' : 'false');
    action.classList.toggle('is-on', !!c.favorite);
    action.innerHTML = icon(c.favorite ? 'heart-fill' : 'heart');

    $('#coord-detail').innerHTML =
      '<div class="detail">' +
        '<div class="look__head">' +
          '<span class="indexlabel">' + esc(lookLabel(c)) + '</span>' +
          '<span class="num">' + esc(stampOf(c.createdAt)) + '</span>' +
        '</div>' +

        coordMediaHtml(c) +

        tryOnBlockHtml(c, items) +

        '<div class="detail__head">' +
          '<h2 class="detail__title">' + esc(c.title || '오늘의 코디') + '</h2>' +
        '</div>' +

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
              // 옷장에서 지운 옷은 코디에 그대로 남지만 상세 화면이 없다.
              // 링크를 걸면 눌렀을 때 되돌아올 수 없는 오류 화면으로 빠지므로,
              // 누를 수 없는 줄로 그리고 왜 못 누르는지 함께 적는다.
              if (it.inWardrobe === false) {
                return '<li class="specrow specrow--gone">' +
                  '<div class="specrow__btn specrow__btn--static">' +
                    '<span class="specrow__label">' + esc(cat.en) + '</span>' +
                    '<span class="specrow__value">' + esc(value) +
                      '<span class="specrow__note">옷장에서 지움</span>' +
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

        '<div class="detail__foot">' + deleteBlockHtml('이 코디를 기록에서 지울까요?', 'delete-coord') + '</div>' +
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
      var done = busy(del, '삭제 중…');
      api.coordinations.remove(state.coord.id).then(function () {
        state.history.loaded = false;
        state.home.loaded = false;
        state.stats.loaded = false;
        toast('코디를 지웠어요.');
        back();
      }).catch(function (err) {
        if (isExpired(err)) return;
        done();
        toast(api.isNotDeployed(err) ? '지금은 지울 수 없어요.' : humanError(err), 'error');
      });
      return;
    }

    if (e.target.closest('#coord-retry')) { state.coord.data = null; onCoordEnter(state.coord.id); return; }

    if (e.target.closest('[data-action="go-body-photo"]')) {
      // 사진만 올리고 끝나면 사용자는 하던 코디를 홈에서 다시 찾아 들어가야 한다.
      // 어디서 왔는지 적어 두고, 등록이 끝나면 그 자리로 되돌린다.
      var c = state.coord.data;
      state.pendingTryOn = { id: state.coord.id, title: (c && c.title) || '코디' };
      navigate('more');
      setTimeout(function () {
        var el = $('#body-photo-preview');
        if (el) el.scrollIntoView({ block: 'center', behavior: 'smooth' });
      }, 140);
      return;
    }

    // 재생성은 AI 를 한 번 더 부르는 일이다. 곧바로 실행하지 않고 한 번 묻는다.
    if (e.target.closest('[data-action="ask-tryon-again"]')) { showTryOnAgain(true); return; }
    if (e.target.closest('[data-action="cancel-tryon-again"]')) { showTryOnAgain(false); return; }

    if (e.target.closest('[data-action="ask-del-tryon"]')) { showDelTryOn(true); return; }
    if (e.target.closest('[data-action="cancel-del-tryon"]')) { showDelTryOn(false); return; }
    var delTryOn = e.target.closest('[data-action="delete-tryon"]');
    if (delTryOn) { runDeleteTryOn(delTryOn); return; }

    var tryOnBtn = e.target.closest('[data-action="tryon"]');
    if (tryOnBtn) runTryOn(tryOnBtn);
  });

  /* ---------------- 입어보기 ---------------- */
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

  /**
   * 착용 사진만 지운다.
   *
   * 서버의 `DELETE /api/coordinations/{id}/tryon` 은 아직 만드는 중이다.
   * 없을 때(404·405)를 오류로 토해 내면 사용자는 앱이 고장난 줄 아니까,
   * "아직 안 열린 기능" 으로 구분해서 지금 할 수 있는 다른 길을 함께 말해 준다.
   */
  function runDeleteTryOn(btn) {
    var host = $('[data-tryon]', $('#coord-detail'));
    var errEl = $('.tryon__error', host);
    var done = busy(btn, '지우는 중…');
    show(errEl, false);

    api.coordinations.removeTryOn(state.coord.id).then(function () {
      if (state.coord.data) state.coord.data.tryOnImageUrl = null;
      var pooled = findCoord(state.coord.id);
      if (pooled) pooled.tryOnImageUrl = null;
      state.home.loaded = false;
      state.history.loaded = false;
      state.coord.media = 'tryon';   // 다음에 결과가 생기면 다시 그것부터 보여 준다
      renderCoord();
      toast('착용 사진을 지웠어요. 옷 사진은 그대로예요.');
    }).catch(function (err) {
      done();
      if (isExpired(err)) return;
      setNote(errEl, api.isNotDeployed(err)
        ? '이 사진만 지우는 기능은 아직 준비 중이에요. 지금은 ‘다시 만들기’ 로 새 이미지를 만들 수 있어요.'
        : humanError(err));
    });
  }

  function runTryOn(btn) {
    if (!requireAi()) return;

    var host = $('[data-tryon]', $('#coord-detail'));
    var progress = $('.progress', host);
    var errEl = $('.tryon__error', host);
    // 큰 버튼과 확인 상자의 버튼이 동시에 살아 있을 수 있다.
    // 값이 드는 호출이므로 진행 중에는 이 블록의 버튼을 전부 잠근다.
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
          state.home.loaded = false;
          renderCoord();   // 블록이 통째로 다시 그려진다 → 버튼은 "다시 만들기" 가 된다
          toast('입어본 모습을 만들었어요.');
        } else {
          show(progress, false);
          release();
          setNote(errEl, '이미지를 받지 못했어요. 다시 시도해 주세요.');
        }
      }, 420);
    }).catch(function (err) {
      sim.stop();
      show(progress, false);
      release();
      if (isExpired(err)) return;

      if (err.isApiError && err.code === 'no_body_photo') {
        errEl.innerHTML = '<span class="note__body">입어보기를 하려면 전신 사진이 필요해요.</span>' +
          '<button class="btn btn--tiny btn--ghost" type="button" data-action="go-body-photo">전신 사진 등록하기</button>';
        show(errEl, true);
        return;
      }
      if (err.isApiError && err.status === 503) {
        openGuide({
          title: 'AI 가 지금 응답하지 않아요',
          desc: '연결한 키가 아직 살아 있는지 확인해 보고, 잠시 후 다시 시도해 주세요.',
          action: '연결 확인하러 가기'
        });
        return;
      }
      setNote(errEl, humanError(err));
    });
  }

  /**
   * 진행 감각용 시뮬레이션. 서버가 진행률을 주지 않으므로, 남은 거리에 비례해
   * 느려지며 96% 에서 멈춘다 — "곧 끝날 것 같은데 안 끝나는" 느낌을 피하려고
   * 100% 는 실제 응답이 왔을 때만 찍는다.
   */
  var TRYON_STAGES = [
    [0, '준비하는 중…'],
    [20, '전신 사진을 살펴보는 중…'],
    [45, '옷을 하나씩 입혀 보는 중…'],
    [72, '마무리하는 중… 조금만 기다려 주세요'],
    [92, '거의 다 됐어요…']
  ];

  function startProgress(fillEl, labelEl, stages) {
    stages = stages || TRYON_STAGES;
    var pct = 0;
    var held = null;   // 단계 문구 대신 이 문장을 붙잡아 둔다 (예: 재시도 중)
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
      /** 단계 진행과 무관하게 지금 무슨 일이 벌어지는지 말해야 할 때. */
      hold: function (text) { held = text; apply(); },
      finish: function (text) {
        clearInterval(timer);
        held = null;
        fillEl.style.setProperty('--progress', '100%');
        labelEl.textContent = text || '완성됐어요!';
      }
    };
  }

  /* ================================================================
   * 옷 추가 시트
   * ================================================================ */
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

  /**
   * 옷 정보 수정.
   *
   * 등록 시트를 그대로 쓴다. 같은 값을 두 가지 화면으로 배우게 할 이유가 없고,
   * 사진을 고르면 자동으로 채워지는 그 폼이 곧 "AI 가 채운 것을 고치는 폼"이다.
   * 사진 자체는 PATCH 로 바꿀 수 없으므로 사진 고르는 자리만 접는다.
   */
  function openEditSheet(item) {
    state.editItemId = item.id;
    resetAddForm();
    $('#add-name').value = item.name || '';
    $('#add-color').value = item.color || '';
    $('#add-detail').value = item.detail || '';
    setCategory(CATEGORY[item.mainCategory] ? item.mainCategory : 'TOP');
    applySheetMode();
    openSheet(addSheet);
    setTimeout(function () { $('#add-name').focus(); $('#add-name').select(); }, 40);
  }

  function applySheetMode() {
    var editing = state.editItemId != null;
    $('#add-sheet-kicker').textContent = editing ? 'Edit Item' : 'New Item';
    $('#add-sheet-title').textContent = editing ? '옷 정보 수정' : '옷 추가';
    $('#add-sheet-desc').textContent = editing
      ? 'AI 가 잘못 읽은 이름·카테고리·색을 여기서 고칠 수 있어요. 사진은 바뀌지 않아요.'
      : '사진을 고르면 이름·카테고리·색을 자동으로 채워 드려요. 사진 없이 직접 입력해도 괜찮아요.';
    show($('#add-picker'), !editing);
    $('.btn__text', $('#btn-add-submit')).textContent = editing ? '수정 저장' : '저장';
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
    setAnalyzing(false);
  }

  /**
   * 사진을 읽는 동안 저장을 막는다. 그냥 잠그기만 하면 "왜 안 눌리지" 가 되므로
   * 버튼이 스스로 무엇을 기다리는지 말하게 한다.
   */
  function setAnalyzing(on) {
    var btn = $('#btn-add-submit');
    btn.disabled = !!on;
    if (on) btn.setAttribute('aria-busy', 'true');
    else btn.removeAttribute('aria-busy');
    $('.btn__text', btn).textContent = on ? '사진 읽는 중…'
      : (state.editItemId != null ? '수정 저장' : '저장');
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

  // ESC·✕ 로 닫힐 때도 뒷정리는 한 곳에서 한다.
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

  /**
   * 사진을 고르면 먼저 분석해서 폼을 채운다.
   * 분석은 "도우미"일 뿐이라, 실패해도 폼은 계속 쓸 수 있어야 한다.
   */
  $('#add-image').addEventListener('change', function (e) {
    var file = e.target.files && e.target.files[0];
    if (!file) return;

    if (file.size > MAX_UPLOAD) {
      setNote($('#add-error'), '사진이 너무 커요. 10MB 이하로 골라 주세요.');
      e.target.value = '';
      return;
    }

    setNote($('#add-error'), '');
    setNote($('#analyze-warn'), '');
    state.addImage = file;

    var img = $('#add-thumb-img');
    img.src = localPreview(file);
    img.hidden = false;
    img.classList.add('is-loaded');
    show($('#add-thumb-empty'), false);
    show($('#btn-clear-image'), true);

    // 연결 전이면 분석은 건너뛰고 직접 입력으로 이어 간다 (막지 않는다)
    if (state.ai.checked && !state.ai.configured) {
      analyzeNotice('AI 연결 전이라 사진은 자동으로 읽지 못해요. 직접 입력해서 저장할 수 있어요.', 'warn');
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
        if (state.addImage !== file) return;   // 그 사이 다른 사진을 골랐다
        if (result) {
          if (result.name && !$('#add-name').value) $('#add-name').value = result.name;
          if (result.mainCategory && CATEGORY[result.mainCategory]) setCategory(result.mainCategory);
          if (result.color && !$('#add-color').value) $('#add-color').value = result.color;
          if (result.detail && !$('#add-detail').value) $('#add-detail').value = result.detail;
          analyzeNotice('사진을 읽어 자동으로 채웠어요. 확인하고 고쳐 주세요.', 'ok');
        }
      })
      .catch(function (err) {
        if (err && err.name === 'AbortError') return;
        if (isExpired(err)) return;
        analyzeNotice(
          err.isApiError && err.status === 503
            ? 'AI 가 지금 응답하지 않아요. 직접 입력해서 저장할 수 있어요.'
            : '사진을 자동으로 읽지 못했어요. 직접 입력해서 저장할 수 있어요.',
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

  $('#form-add').addEventListener('submit', function (e) {
    e.preventDefault();
    var name = $('#add-name').value.trim();
    var color = $('#add-color').value.trim();
    var detail = $('#add-detail').value.trim();
    setNote($('#add-error'), '');

    if (!name) {
      setNote($('#add-error'), '옷 이름을 적어 주세요.');
      $('#add-name').focus();
      return;
    }

    var done = busy($('#btn-add-submit'), '저장 중…');

    if (state.editItemId != null) {
      var editingId = state.editItemId;
      api.clothes.update(editingId, {
        name: name,
        mainCategory: currentCategory(),
        // PATCH 는 null 이 "변경 없음" 이므로, 비우려면 빈 문자열을 보내야 한다.
        color: color,
        detail: detail
      }).then(function (updated) {
        closeAddSheet();
        state.item.data = updated;
        state.closet.loaded = false;
        state.home.loaded = false;
        state.stats.loaded = false;
        if (String(state.item.id) === String(editingId)) {
          $('#topbar-title').textContent = updated.name || '옷';
          renderItem();
        }
        toast('‘' + updated.name + '’ 정보를 고쳤어요.');
        // 서버가 아직 detail 을 받지 않는다면 조용히 사라지게 두지 않는다.
        if ((updated.detail || '') !== detail) {
          toast('소재 · 핏은 아직 저장되지 않아요. 나머지는 저장했어요.', 'error');
        }
      }).catch(function (err) {
        if (isExpired(err)) return;
        setNote($('#add-error'), api.isNotDeployed(err)
          ? '지금은 수정할 수 없어요. 잠시 후 다시 시도해 주세요.'
          : humanError(err));
      }).finally(done);
      return;
    }

    api.clothes.create({
      image: state.addImage,
      name: name,
      mainCategory: currentCategory(),
      color: color || null,
      detail: detail || null
    }).then(function (created) {
      closeAddSheet();
      toast('‘' + created.name + '’ 을(를) 옷장에 담았어요.');
      state.home.loaded = false;
      state.closet.loaded = true;
      state.stats.loaded = false;
      // 총 개수·정렬이 서버 기준이므로 첫 페이지부터 다시 읽는다.
      if (current().name !== 'closet') navigate('closet');
      return loadClothes({ reset: true });
    }).catch(function (err) {
      if (isExpired(err)) return;
      setNote($('#add-error'), humanError(err));
    }).finally(done);
  });

  /* ================================================================
   * 더보기 / 설정
   * ================================================================ */
  var moreLoaded = false;

  function onMoreEnter() {
    $('#more-email').textContent = (state.user && state.user.email) || '';
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

  /* ---------------- 옷장 통계 ----------------
   * 홈의 색인 줄과 더보기의 패널이 같은 값을 쓴다. 한 번만 읽고 둘이 나눠 쓴다. */
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
                '<span class="specrow__label">' + esc(m.usedCount) + '회</span>' +
                '<span class="specrow__value">' + esc(m.name) + '</span></span></li>';
            }).join('') + '</ul></div>'
        : '');
    show($('#panel-stats'), true);
  }

  /* ---------------- 전신 사진 ---------------- */

  /**
   * '입어보기' 에서 여기로 온 사람을 되돌려 보낸다.
   *
   * 오는 길("전신 사진이 필요해요" → 설정 화면)은 이미 잘 놓여 있었는데,
   * 돌아가는 길이 없었다. 사진을 올리고 나면 그냥 설정 화면에 남겨져,
   * 하던 코디를 홈에서 다시 찾아 들어가야 했다.
   */
  function renderTryOnResume() {
    var p = state.pendingTryOn;
    var box = $('#body-photo-resume');
    show(box, !!p);
    if (!p) return;

    var hasPhoto = !!(state.user && state.user.bodyPhotoUrl);
    $('#body-photo-resume-msg').textContent = hasPhoto
      ? '전신 사진이 준비됐어요. ‘' + p.title + '’ 으로 돌아가 입어볼 수 있어요.'
      : '‘' + p.title + '’ 입어보기를 하려다 오셨어요. 사진을 등록하면 이어서 할 수 있어요.';
  }

  $('#btn-body-photo-resume').addEventListener('click', function () {
    var p = state.pendingTryOn;
    if (!p) return;
    state.pendingTryOn = null;
    show($('#body-photo-resume'), false);
    navigate('coord', { id: p.id, title: p.title });
    // 돌아온 자리에서 무엇을 누르면 되는지 바로 보이게 한다.
    // AI 호출은 사용자가 직접 누를 때만 한다.
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

    // 이니셜 판(NONE)은 눈으로만 읽는 자리다. 화면 낭독기에는 이 문장이 간다.
    if (status) status.textContent = (me && me.bodyPhotoUrl) ? '전신 사진 등록됨' : '등록된 전신 사진 없음';
    renderTryOnResume();

    if (me && me.bodyPhotoUrl) {
      if (label) label.textContent = '사진 바꾸기';
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
      if (label) label.textContent = '사진 선택';
    }
  }

  $('#body-photo-input').addEventListener('change', function (e) {
    var file = e.target.files && e.target.files[0];
    if (!file) return;
    setNote($('#body-photo-error'), '');

    if (file.size > MAX_UPLOAD) {
      setNote($('#body-photo-error'), '사진이 너무 커요. 10MB 이하로 골라 주세요.');
      e.target.value = '';
      return;
    }

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
      // 서버 사진을 받아 끼울 때까지 미리보기를 살려 둔다. 바로 revoke 하면 깜빡인다.
      setTimeout(function () { URL.revokeObjectURL(localUrl); }, 2000);
      state.user = state.user || {};
      state.user.bodyPhotoUrl = (res && res.bodyPhotoUrl) || previousUrl;
      renderBodyPhoto();
      toast(state.pendingTryOn
        ? '전신 사진을 저장했어요. 이제 입어보기로 돌아갈 수 있어요.'
        : '전신 사진을 저장했어요.');
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

  /* ---------------- 스타일 선호도 ---------------- */
  function updateStyleCount() {
    $('#style-count').textContent = String($('#style-input').value.length);
  }
  $('#style-input').addEventListener('input', updateStyleCount);

  $('#form-style').addEventListener('submit', function (e) {
    e.preventDefault();
    setNote($('#style-error'), '');
    var value = $('#style-input').value.trim();
    var done = busy($('#btn-style-save'), '저장 중…');

    api.users.saveStylePreference(value || null).then(function () {
      toast('스타일 선호도를 저장했어요.');
    }).catch(function (err) {
      if (isExpired(err)) return;
      setNote($('#style-error'), api.isNotDeployed(err)
        ? '지금은 저장할 수 없어요. 잠시 후 다시 시도해 주세요.'
        : humanError(err));
    }).finally(done);
  });

  /* ---------------- AI 키 ---------------- */
  $('#btn-key-reveal').addEventListener('click', function () {
    var input = $('#key-input');
    var shown = input.type === 'text';
    input.type = shown ? 'password' : 'text';
    this.setAttribute('aria-pressed', shown ? 'false' : 'true');
    this.setAttribute('aria-label', shown ? '입력한 키 보기' : '입력한 키 가리기');
    this.innerHTML = icon(shown ? 'eye' : 'eye-off', 'ico--sm');
  });

  $('#form-key').addEventListener('submit', function (e) {
    e.preventDefault();
    setNote($('#key-error'), '');
    var key = $('#key-input').value.trim();
    if (!key) {
      setNote($('#key-error'), '받은 키를 붙여넣어 주세요.');
      $('#key-input').focus();
      return;
    }

    var wasEditing = state.ai.editing;
    var done = busy(e.target.querySelector('button[type=submit]'), '연결 중…');
    api.settings.saveGeminiKey(key).then(function (res) {
      state.ai.configured = !!(res && res.configured);
      state.ai.masked = res && res.masked;
      state.ai.checked = true;
      state.ai.editing = false;
      $('#key-input').value = '';
      renderAiState();
      toast(wasEditing ? '새 키로 바꿨어요.' : 'AI 연결이 끝났어요. 이제 추천을 받을 수 있어요.');
    }).catch(function (err) {
      if (isExpired(err)) return;
      setNote($('#key-error'), err.isApiError && err.code === 'invalid_key'
        ? '키가 올바르지 않아요. 복사한 글자를 처음부터 끝까지 다시 붙여넣어 주세요.'
        : humanError(err));
    }).finally(done);
  });

  /**
   * 키 바꾸기는 **화면의 모드 전환**이지 연결 해제가 아니다.
   * 예전에는 여기서 state.ai.configured 를 false 로 만들어, 서버는 계속
   * configured:true 인데도 홈 문구가 "AI 연결을 마치면" 으로 바뀌고
   * 추천을 누르면 "연결이 아직 안 되어 있어요" 시트가 떴다.
   */
  $('#btn-key-change').addEventListener('click', function () {
    state.ai.editing = true;
    renderAiState();
    setNote($('#key-error'), '');
    setTimeout(function () { $('#key-input').focus(); }, 60);
  });

  /** 취소 — 붙여넣을 키가 없을 때 원래 상태로 돌아가는 유일한 길. */
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
    var done = busy(this, '해제 중…');
    api.settings.removeGeminiKey().then(function () {
      state.ai.configured = false;
      state.ai.masked = null;
      state.ai.editing = false;
      renderAiState();
      toast('연결을 해제했어요.');
    }).catch(function (err) {
      if (isExpired(err)) return;
      toast(humanError(err), 'error');
    }).finally(done);
  });

  /* ================================================================
   * 부팅
   * ================================================================ */
  function boot() {
    if (!api.tokens.exists()) {
      showAuth();
      return;
    }
    api.users.me().then(function (me) {
      state.user = me;
      showApp();
    }).catch(function (err) {
      if (err.isApiError && err.status === 401) {
        // onSessionExpired 가 이미 안내와 함께 로그인 화면으로 보냈다.
        // 여기서 다시 showAuth() 를 부르면 그 문구를 지워 버린다.
        show($('#boot'), false);
        if ($('#screen-auth').hidden) showAuth();
        return;
      }
      // 서버가 잠깐 안 뜬 경우까지 세션을 버리지는 않는다
      showApp();
      toast(humanError(err), 'error');
    });
  }

  boot();
})();
