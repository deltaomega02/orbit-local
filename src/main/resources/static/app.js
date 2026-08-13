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

  /* ---------------- 日本語入力(IME) ----------------
   *
   * 일본어는 로마자를 치고 Enter 로 **변환을 확정**한다. 그 Enter 는 "제출"이
   * 아니라 "이 한자로 하겠다"는 뜻인데, 폼 안의 한 줄짜리 입력칸에서는 브라우저가
   * 그것을 그대로 암묵적 제출로 흘려보낼 수 있다. 그러면 「服」 를 확정하려던
   * 손짓이 저장 버튼을 누른 것이 되고, 이 실수는 일본어 사용자가 **매번** 겪는다.
   *
   * 조합 중인지는 두 가지로 본다. `isComposing` 은 표준이지만 조합을 끝내는
   * 그 Enter 에서 false 로 오는 브라우저가 있어, 예부터 쓰이는 keyCode 229 와
   * 우리가 직접 세는 composition 상태를 함께 본다.
   */
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

  /**
   * 변환 확정 Enter 가 폼 제출로 새지 않게 막는다.
   * textarea 는 Enter 가 줄바꿈이라 애초에 제출되지 않으므로 건드리지 않는다.
   */
  function guardImeSubmit(form) {
    if (!form) return;
    form.addEventListener('keydown', function (e) {
      if (e.key !== 'Enter') return;
      var el = e.target;
      if (!el || el.tagName !== 'INPUT') return;
      if (isComposingEvent(e, el)) e.preventDefault();
    });
  }

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

  /* ---------------- 오류 → 사람 말 ----------------
   *
   * 화면에 나가는 오류 문구는 **전부 여기서 만든다.** 서버가 주는 `detail` 은
   * 쓰지 않는다 — 한 화면에서 서버 문장과 클라이언트 문장이 섞이면 말투도
   * 높임도 제각각이 되고, 서버가 문구를 고칠 때마다 화면이 같이 흔들린다.
   * 서버의 `error` 코드만 읽고 문장은 이쪽이 책임진다.
   */
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
      // AI 가 상의·하의를 갖춘 조합을 내놓지 못한 경우. 서버가 걸러 낸 것이므로
      // 사용자가 고칠 수 있는 것은 없다. 다시 해 보라고만 말한다.
      case 'ai_invalid_response': return 'コーデを作れませんでした。しばらくしてからもう一度お試しください。';
      /*
       * 형식 때문에 막힌 것을 "읽을 수 없습니다"로 뭉뚱그리면, 사용자는 사진이
       * 깨진 줄 알고 같은 파일을 몇 번이고 다시 고른다. 실제로 걸리는 것은 거의
       * 하나다 — 아이폰이 기본으로 저장하는 HEIC. 자바에 디코더가 없어서 받을 수
       * 없고, 브라우저도 못 그린다. 이름을 대고 빠져나갈 길을 알려준다.
       */
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
      // 서버는 크기로 거절하지 않는다(상한 자체가 없다). 여기까지 온다는 것은
      // 사진이 아니거나 뭔가 잘못된 파일이라는 뜻이다. 사용자에게 "줄여서
      // 다시 올려라"고 하면 할 수 없는 일을 시키는 셈이라 문구를 바꿨다.
      case 413: return 'この写真は読み込めませんでした。別の写真でお試しください。';
      case 502: return 'コーデを作れませんでした。しばらくしてからもう一度お試しください。';
      case 503: return 'AI が応答していません。しばらくしてからもう一度お試しください。';
    }
    return '問題が起きました。しばらくしてからもう一度お試しください。';
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
    TOP:    { label: 'トップス', en: 'Top',    initial: 'TOP' },
    BOTTOM: { label: 'ボトムス', en: 'Bottom', initial: 'BOTTOM' },
    OUTER:  { label: 'アウター', en: 'Outer',  initial: 'OUTER' }
  };
  function catOf(key) { return CATEGORY[key] || { label: 'その他', en: 'Item', initial: 'ITEM' }; }

  /*
   * 업로드 크기 상한이 여기 있었다(10MB). 서버 쪽을 40MB 로 올린 뒤에도 이 값이
   * 남아 있어서, 화면은 계속 "10MB 以下" 라고 거절했다. 상한을 두 곳에서 관리하면
   * 반드시 이런 식으로 어긋난다.
   * 지금은 서버가 크기로 거절하지 않고 받아서 줄인다. 화면도 재지 않는다.
   */

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
  /**
   * 날짜. 일본에서 숫자로 적는 날짜는 `2026/08/13` 이 표준이다.
   * (`2026.08.13` 은 한국식이고, `2026年8月13日` 은 mono 로 자릿수를 맞춰 세로로
   *  쌓아 두는 이 자리에서는 길이가 들쭉날쭉해진다.)
   * 시각은 24시간제 — 일본도 같다.
   */
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
      imgTag(c.tryOnImageUrl, 'AI が作った試着姿') + '</span>';
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
      (c && c.tryOnImageUrl ? 'AI生成画像' : 'AI提案') + '</span>';
  }

  function favBtnHtml(c) {
    return '<button class="photobtn' + (c.favorite ? ' is-on' : '') + '" type="button" ' +
      'data-fav="' + esc(c.id) + '" aria-pressed="' + (c.favorite ? 'true' : 'false') + '" ' +
      'aria-label="お気に入り">' + icon(c.favorite ? 'heart-fill' : 'heart') + '</button>';
  }

  /**
   * 그날 적어 둔 한 줄. 서버가 이 필드를 아직 안 줄 수도 있으므로 없으면 빈 문자열.
   * 코디는 아우터 없이 상의·하의만으로도 정상이라, 구성 옷 수는 여기서 따지지 않는다.
   */
  function situationOf(c) {
    var s = c && c.situation;
    return (typeof s === 'string' && s.trim()) ? s.trim() : '';
  }

  /** LOOK 카드 — 이 앱의 얼굴 */
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
      // 그때 무슨 일이 있어서 이 옷을 입었는지. 적어 두지 않았으면 줄 자체가 없다.
      (situation ? '<p class="look__note">' + esc(situation) + '</p>' : '') +
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
    var title = c.title || '今日のコーデ';
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

  /* ================================================================
   * 라우터
   * ================================================================ */
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
   *
   * 화면은 둘뿐이다 — 앱, 그리고 앱을 못 연 이유를 말하는 부팅 화면.
   * 로그인 화면은 없다. 세션은 api.js 가 알아서 받아 온다.
   * ================================================================ */
  function showApp() {
    show($('#app-shell'), true);
    show($('#boot'), false);
    // 주소가 가리키는 화면에서 시작한다. 새로고침해도 보던 자리로 돌아온다.
    stack = stackFor(parseHash(window.location.hash) || { name: 'home', params: {} });
    syncUrl(true);
    applyRoute();
    loadAiState();
  }

  /**
   * 세션을 못 받았을 때.
   *
   * 예전에는 이 자리가 로그인 화면이었다. 이제 사용자가 손으로 고칠 수 있는 것은
   * 없으므로, 무엇이 막혔는지 한 줄로 말하고 다시 해 볼 버튼 하나만 둔다.
   */
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
    if (submitText) submitText.textContent = editing ? '新しいキーに変更' : '連携する';

    // 해제 확인은 패널을 다시 그릴 때마다 접어 둔다.
    showKeyRemoveConfirm(false);

    // 홈의 문구는 **서버가 말한 사실**만 따른다. 화면의 편집 모드가 섞이면
    // 키를 바꾸려 눌렀을 뿐인데 앱 전체가 "AI 안 됨" 으로 바뀐다.
    var sub = $('#recommend-sub');
    if (sub) {
      // 연결이 안 됐다는 사실이 우선이고, 그 외에는 '오늘 상황' 이 문구를 맡는다.
      if (state.ai.checked && !state.ai.configured) sub.textContent = 'AI連携を済ませるとすぐ使えます';
      else syncSituation();
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

    // 助数詞: 기록은 '件', 옷은 '着'. 숫자 뒤에 붙는 이 한 글자가 일본어에서는
    // 단위가 아니라 문법이라, 하나로 뭉뚱그리면 바로 어색해진다.
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

  /* ---------------- 오늘 상황 ----------------
   * 추천 버튼 바로 옆의 한 줄. 이번 추천에만 쓰이고 끝나면 비워진다.
   * (설정의 '스타일 선호도' 는 늘 반영되는 취향이라 다른 물건이다.)
   *
   * 매일 여는 앱에서 매번 문장을 치게 하면 결국 아무도 안 쓴다. 그래서 칩을 먼저
   * 두고, 칩으로 넣은 뒤에도 이어서 고칠 수 있게 입력칸을 함께 둔다.
   * 비워 둔 채 버튼만 눌러도 지금까지처럼 곧장 돈다 — 관문이 아니다.
   */
  var LAST_SITUATION_KEY = 'orbit.situation.last';
  var situationInput = $('#situation-input');

  function situationValue() {
    return situationInput ? situationInput.value.trim().slice(0, 100) : '';
  }

  /**
   * 입력칸의 내용을 쉼표로 끊어 본 목록. 칩의 눌림 상태를 여기서 읽는다.
   *
   * 일본어 키보드로 치면 쉼표는 반각 `,` 이 아니라 읽점 `、` 이나 전각 `，` 로
   * 들어온다. 셋 다 끊는 자리로 본다 — 안 그러면 손으로 「出勤、雨の日」 라고
   * 적었을 때 칩이 하나도 눌린 표시가 나지 않는다.
   */
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

    // 버튼이 무엇을 반영해서 고를지 스스로 말한다. 입력과 결과의 연결이 보여야 한다.
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

  /** 지난번에 쓴 말은 기본값이 아니라 힌트다. placeholder 로만 흘려준다. */
  function rememberSituation(value) {
    try {
      if (value) window.localStorage.setItem(LAST_SITUATION_KEY, value);
      else window.localStorage.removeItem(LAST_SITUATION_KEY);
    } catch (e) { /* 저장 못 해도 기능은 그대로다 */ }
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
      // 이어 붙일 때도 읽점을 쓴다. 일본어 문장에 반각 쉼표가 섞이면
      // 그 자리만 남의 글씨처럼 뜬다(「出勤, 雨の日」 vs 「出勤、雨の日」).
      setSituation(parts.join('、'));
      situationInput.focus();
    });

    $('#btn-situation-clear').addEventListener('click', function () {
      setSituation('');
      situationInput.focus();
    });

    // 상황을 치다가 Enter 를 누르면 그대로 추천이 돌아야 한다. 폼이 아니므로 직접 잇는다.
    // 다만 그 Enter 가 IME 변환을 확정하는 것이면 아직 다 쓴 게 아니다 — 흘려보낸다.
    situationInput.addEventListener('keydown', function (e) {
      if (e.key !== 'Enter') return;
      if (isComposingEvent(e, situationInput)) return;
      e.preventDefault();
      var btn = $('#btn-recommend');
      if (btn && !btn.disabled && !btn.hidden) btn.click();
    });
  }

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

  /**
   * 2단계 — 조합을 고른 뒤 그 조합을 입은 모습을 만드는 동안.
   * 20~30초가 걸리므로 첫 단계보다 더 또렷한 진행 감각이 필요하다.
   */
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
    // 여기서부터는 오래 걸리는 게 정상이다. "느린가?" 힌트 대신 걸리는 시간을 미리 말한다.
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
    // 이번 호출에만 쓰이는 한 줄. 비어 있으면 서버로 아예 나가지 않는다.
    var situation = situationValue();
    var done = busy(btn, '選んでいます…');
    setNote($('#home-error'), '', $('#home-error-msg'));
    show($('#home-error-action'), false);
    recStart();

    recommendWithRetry(situation, function (retry) {
      // 서버가 다시 해 볼 만한 실패를 주면 클라이언트가 같은 요청을 다시 보낸다.
      // 그동안 버튼이 "고르는 중…" 에 머물러 있으면 사용자는 그냥 느린 줄 안다.
      // 무엇을 다시 하고 있는지를 진행 바 문구로 꺼낸다.
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
      // 오늘 상황은 이번 한 번짜리다. 남겨 두면 다음 추천에 엉뚱하게 딸려 간다.
      // 대신 방금 쓴 말을 placeholder 힌트로만 남긴다.
      setSituation('');
      rememberSituation(situation);
      state.history.loaded = false;
      state.stats.loaded = false;
      // 추천의 결과물은 조합이 아니라 "내가 그 옷을 입은 모습"이다. 이어서 만든다.
      return autoTryOn(created).then(function (outcome) {
        // 서버가 오늘 목록의 주인이다. 로컬에서 합치지 않고 다시 읽는다.
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
      // 서버가 구성이 어긋난 AI 응답을 거부한 경우(502). 위에서 이미 한 번 더
      // 불러 봤고 그래도 안 됐다. 사용자가 고칠 수 있는 것은 없으니 문구는
      // 짧게 두고, 다시 누를 길만 바로 옆에 둔다.
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
   * 추천 직후 이어서 부르는 착용 이미지 생성.
   *
   * 왜 서버가 한 번에 묶지 않고 클라이언트가 두 번 부르는가 —
   *   묶으면 추천 7초 + 생성 25초 = 30초가 넘는 단일 요청이 된다. 그 사이 무슨 일이
   *   벌어지는지 보여 줄 방법이 없고, 이미지 단계에서 실패하면 애써 고른 조합까지
   *   함께 날아간다. 두 번 부르면 (1) 단계별 진행 표시가 되고 (2) 이미지가 실패해도
   *   추천은 남아서 '다시 만들기' 로 이어 갈 수 있다.
   *
   * 값이 드는 호출이라 실패해도 **자동으로 다시 부르지 않는다.** 사람이 누른다.
   * 어떤 경우에도 reject 하지 않는다 — 이미지가 없다고 추천까지 실패로 만들지 않는다.
   */
  function autoTryOn(created) {
    var id = created && created.id;
    if (!id) return Promise.resolve({ message: '今日のコーデを選びました。' });

    function guide() {
      // 합성할 대상이 없다. 추천은 그대로 두고, 등록하고 돌아오는 길만 열어 준다.
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
   * 다시 해 볼 만한 실패 두 가지를 서로 다른 정책으로 재시도한다.
   *
   *   409 duplicate       "같은 요청을 다시 보내면 다른 조합이 나올 수 있다".
   *                       옷 수가 적을수록 자주 나므로 최대 3회, 1초 간격.
   *   502 ai_invalid      서버가 AI 응답 구성을 검증해 되돌린 것이다
   *                       (상의 1 · 하의 1 · 아우터 0~1 이 아님). 모델이 규격을
   *                       어긴 우연이라 한 번 더 부르면 성공할 여지가 있다.
   *                       다만 값이 드는 호출이므로 **딱 한 번만**.
   *
   * 두 카운터를 섞지 않는다. 섞으면 409 를 두 번 만난 뒤 502 가 왔을 때
   * 남은 몫이 없어 다시 해 볼 만한 실패를 그냥 실패로 넘겨 버린다.
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
      // 섹션 라벨의 영문은 디자인이지만, 개수를 세는 문장은 사용자의 말이어야 한다.
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

  /* ================================================================
   * 옷 상세
   * ================================================================ */
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

    // subCategory·material·fit·season 은 백엔드가 만드는 중이라 아직 undefined 일 수
    // 있다. 값이 없는 줄은 아예 그리지 않으므로 빈 라벨이 남지 않는다.
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

    // 넓은 화면에서는 왼쪽 사진 / 오른쪽 정보로 갈라진다(.detail--split).
    // 좁은 폭에서는 두 열이 그냥 위아래로 흐르므로 읽는 순서는 그대로다.
    $('#item-detail').innerHTML =
      '<div class="detail detail--split">' +
        '<div class="detail__col detail__col--media">' +
          // pill 은 AI 가 개입한 자리를 뜻한다. 바로 아래 인덱스 라벨이 같은 말을
          // 이미 하고 있으므로 옷 사진에는 얹지 않는다.
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
          // 이 앱은 옷 정보를 AI 가 대신 채운다. 실제로 바지를 "민소매 티셔츠 · 상의"
          // 로 분류한 적이 있다. 자동 입력을 쓰면서 고칠 길이 없으면, 잘못 읽힌 옷은
          // 지우고 다시 올리는 수밖에 없다. 저장 뒤에도 고칠 수 있어야 한다.
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
            // 이미 코디에 쓰인 옷은 지워도 기록에서 사라지지 않는다. 그 사실을
            // 미리 말해 주지 않으면 사용자는 기록까지 지워질까 봐 못 지우거나,
            // 지운 뒤 기록에 남아 있는 것을 보고 안 지워졌다고 생각한다.
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
      // 확인 패널은 화면 맨 아래에 열린다. 스크롤을 따라가지 않으면 탭바에 가린다.
      scrollIntoViewSafely(confirmBox);
    } else if (btn.dataset.action === 'cancel-delete') {
      show(confirmBox, false);
      show(ask, true);
      if (ask) ask.focus();
    }
  });

  /**
   * Esc — 펼쳐 놓은 것을 접는다.
   *
   * 시트는 <dialog> 라 브라우저가 알아서 닫아 주지만, 화면 안에서 펼쳐지는 확인
   * 패널(삭제·연결 해제·다시 만들기)은 취소 버튼을 눌러야만 닫혔다. 키보드로 쓰는
   * 사람에게는 열려 있는 것은 Esc 로 닫히는 게 규칙이다.
   */
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

  /* ================================================================
   * 코디 상세
   * ================================================================ */
  function openCoord(id, title) { navigate('coord', { id: id, title: title || 'コーデ' }); }

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

    // 추천 직후의 자동 생성이 못 끝났으면 그 이유가 여기에 실린다.
    // 결과가 이미 있으면 지난 이야기이므로 보여 주지 않는다.
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

      // 막지는 않는다. 결과가 왜 실제와 다를 수 있는지만 미리 알려 준다.
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

      // 생성 결과가 남으로 나오는 일이 있는데, 지우는 길이 룩 전체 삭제뿐이었다.
      // 착용 사진 하나만 되돌릴 수 있어야 기록을 버리지 않고 다시 시도한다.
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

  /* ---------------- 코디 상세의 사진 자리 ---------------- */
  var MEDIA_NOTE = {
    tryon: 'AI が作った画像です。実際に着た姿ではありません。',
    items: '登録した服の写真そのままです。'
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

    // 넓은 화면에서는 왼쪽 사진 / 오른쪽 정보. 좁은 폭에서는 위아래로 흐른다.
    $('#coord-detail').innerHTML =
      '<div class="detail detail--split">' +
        '<div class="detail__col detail__col--media">' +
        '<div class="look__head">' +
          '<span class="indexlabel">' + esc(lookLabel(c)) + '</span>' +
          '<span class="num">' + esc(stampOf(c.createdAt)) + '</span>' +
        '</div>' +

        coordMediaHtml(c) +
        // 입어보기는 사진에 하는 일이다. 넓은 화면에서도 사진 바로 밑에 둔다.
        tryOnBlockHtml(c, items) +
        '</div>' +

        '<div class="detail__col detail__col--info">' +
        '<div class="detail__head">' +
          '<h2 class="detail__title">' + esc(c.title || '今日のコーデ') + '</h2>' +
        '</div>' +

        // 그날 적어 둔 한 줄. "그때 왜 이걸 입었지" 가 보여야 기록이 뜻을 가진다.
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
              // 옷장에서 지운 옷은 코디에 그대로 남지만 상세 화면이 없다.
              // 링크를 걸면 눌렀을 때 되돌아올 수 없는 오류 화면으로 빠지므로,
              // 누를 수 없는 줄로 그리고 왜 못 누르는지 함께 적는다.
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
      // 사진만 올리고 끝나면 사용자는 하던 코디를 홈에서 다시 찾아 들어가야 한다.
      // 어디서 왔는지 적어 두고, 등록이 끝나면 그 자리로 되돌린다.
      var c = state.coord.data;
      state.pendingTryOn = { id: state.coord.id, title: (c && c.title) || 'コーデ' };
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
    var done = busy(btn, '削除しています…');
    show(errEl, false);

    api.coordinations.removeTryOn(state.coord.id).then(function () {
      if (state.coord.data) state.coord.data.tryOnImageUrl = null;
      var pooled = findCoord(state.coord.id);
      if (pooled) pooled.tryOnImageUrl = null;
      state.home.loaded = false;
      state.history.loaded = false;
      state.coord.media = 'tryon';   // 다음에 결과가 생기면 다시 그것부터 보여 준다
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
          state.tryOnNote = null;   // 손으로 다시 만들어 성공했다. 안내는 제 몫을 다했다.
          state.home.loaded = false;
          renderCoord();   // 블록이 통째로 다시 그려진다 → 버튼은 "作り直す" 가 된다
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
   * 진행 감각용 시뮬레이션. 서버가 진행률을 주지 않으므로, 남은 거리에 비례해
   * 느려지며 96% 에서 멈춘다 — "곧 끝날 것 같은데 안 끝나는" 느낌을 피하려고
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
        labelEl.textContent = text || '完成しました！';
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
  /**
   * 사진 분석 결과와 등록·수정 폼이 같은 이름으로 쓰는 칸들.
   * `POST /api/clothes/analyze` 응답과 등록 요청의 필드명이 1:1 이라,
   * 받은 것을 그대로 부으면 된다. 값이 없으면 조용히 비워 둔다.
   */
  var EXTRA_FIELDS = [
    { key: 'subCategory', sel: '#add-subcategory', label: '種類' },
    { key: 'material',    sel: '#add-material',    label: '素材' },
    { key: 'fit',         sel: '#add-fit',         label: 'シルエット' },
    { key: 'season',      sel: '#add-season',      label: '季節' }
  ];

  /**
   * 계절 칩.
   *
   * 실제로 쓰이는 값이 몇 개 안 되니 매번 타이핑하게 할 이유가 없다. 다만 칩은
   * 지름길일 뿐 값의 울타리가 아니다 — 서버도 20자 이내면 무엇이든 받는다.
   * 그래서 칩은 '고르는 것'이 아니라 '칸에 넣어 주는 것'이고, 눌린 표시는 칸의
   * 내용을 읽어서 정한다. 직접 적은 말이 칩과 같으면 그 칩이 눌려 보인다.
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
      // 눌린 칩을 한 번 더 누르면 비운다. 잘못 넣었을 때 지우는 길.
      var same = seasonInput.value.trim() === chip.dataset.season;
      seasonInput.value = same ? '' : chip.dataset.season;
      syncSeasonChips();
    });
  }

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
      /*
       * 안내가 실제보다 적게 약속하고 있었다("名前・カテゴリー・色"). 사진 한 장에서
       * 채워지는 것은 여덟 항목 전부이고(이름·카테고리·색·종류·소재·실루엣·계절·설명),
       * 그 값들이 그대로 추천의 재료가 된다. 적게 적어 두면 사용자는 나머지를 손으로
       * 채워야 하는 줄 알고, 비워 두면 추천이 나빠질까 걱정하게 된다.
       */
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
    syncSeasonChips();   // form.reset() 은 칸만 비운다. 칩의 눌린 표시도 함께 푼다.
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
        if (state.addImage !== file) return;   // 그 사이 다른 사진을 골랐다
        if (result) {
          if (result.name && !$('#add-name').value) $('#add-name').value = result.name;
          if (result.mainCategory && CATEGORY[result.mainCategory]) setCategory(result.mainCategory);
          if (result.color && !$('#add-color').value) $('#add-color').value = result.color;
          if (result.detail && !$('#add-detail').value) $('#add-detail').value = result.detail;
          // 서버가 아직 안 주는 항목은 그냥 빈칸으로 남는다. 직접 적으면 된다.
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
        /*
         * 원인을 뭉뚱그리지 않는다. 예전에는 형식 문제도 "読み取れませんでした" 로
         * 나가서, 사용자는 AI 가 못 알아본 줄 알고 같은 HEIC 파일을 계속 다시 골랐다.
         */
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

  // 이 폼은 한 줄짜리 입력칸이 여덟이다. 일본어로 옷 이름을 치고 변환을 확정하는
  // Enter 가 곧바로 '저장'이 되면, 반쯤 쓰다 만 옷이 옷장에 들어간다.
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

    /** 서버가 아직 받지 않는 칸이 있으면 조용히 사라지게 두지 않고 이름을 대 준다. */
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
        // PATCH 는 null 이 "변경 없음" 이므로, 비우려면 빈 문자열을 보내야 한다.
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
        // 서버가 아직 받지 않는 항목이 있으면 무엇이 빠졌는지 이름을 대 준다.
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
    // 이메일은 이 앱에서 아무 뜻도 없는 부품이다. 서버가 준 표시 이름만 쓴다.
    var name = (state.user && state.user.displayName) || '';
    $('#more-name').textContent = name;
    show($('#more-name'), !!name);
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
                '<span class="specrow__label">' + esc(m.usedCount) + '回</span>' +
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
      ? '全身写真の準備ができました。「' + p.title + '」に戻って試着できます。'
      : '「' + p.title + '」の試着の途中でした。写真を登録すると続きから試せます。';
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
      // 서버 사진을 받아 끼울 때까지 미리보기를 살려 둔다. 바로 revoke 하면 깜빡인다.
      setTimeout(function () { URL.revokeObjectURL(localUrl); }, 2000);
      state.user = state.user || {};
      state.user.bodyPhotoUrl = (res && res.bodyPhotoUrl) || previousUrl;
      // "전신 사진을 등록하면…" 안내는 이제 할 말을 잃었다.
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

  /* ---------------- 스타일 선호도 ---------------- */
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

  /* ---------------- AI 키 ---------------- */
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

  /* ================================================================
   * 부팅
   * ================================================================ */
  /**
   * 켜자마자 홈이다.
   *
   * 쓸 수 있는 토큰이 있으면 그대로 쓰고, 없으면 api.js 가 조용히 세션을 받아 온다.
   * 물어보는 화면은 어디에도 없다.
   */
  function boot() {
    showBootLoading();
    api.auth.ensure()
      .then(function () { return api.users.me(); })
      .then(function (me) {
        state.user = me;
        showApp();
      })
      .catch(function (err) {
        // 세션조차 못 받았으면 앱을 열어 봐야 전부 401 이다. 이유를 말하고 멈춘다.
        if (!api.tokens.exists()) {
          showBootError(humanError(err));
          return;
        }
        // 토큰은 살아 있는데 첫 요청만 어긋난 경우. 앱은 열어 두고 알리기만 한다.
        showApp();
        toast(humanError(err), 'error');
      });
  }

  $('#btn-boot-retry').addEventListener('click', boot);

  boot();
})();
