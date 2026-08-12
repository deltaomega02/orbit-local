/**
 * app.js — 화면 하나짜리 앱의 상태와 렌더링.
 *
 * 원칙 셋:
 *  1) alert/confirm 을 쓰지 않는다. 모든 안내는 인라인 배너·토스트·인라인 확인 UI.
 *  2) 시간이 걸리는 버튼은 누른 즉시 비활성화 + 스피너. 중복 클릭이 가장 흔한 사고다.
 *  3) 모든 목록에는 로딩 / 비어 있음 / 에러 세 가지 상태를 전부 만든다.
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

  function show(el, on) {
    if (!el) return;
    el.hidden = !on;
  }

  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }

  /** 버튼을 "처리 중" 상태로. 되돌리는 함수를 반환한다. */
  function busy(btn, labelWhileBusy) {
    if (!btn) return function () {};
    var textEl = $('.btn__text', btn);
    var original = textEl ? textEl.textContent : null;
    btn.disabled = true;
    btn.classList.add('is-busy');
    btn.setAttribute('aria-busy', 'true');
    if (textEl && labelWhileBusy) textEl.textContent = labelWhileBusy;
    return function done() {
      btn.disabled = false;
      btn.classList.remove('is-busy');
      btn.removeAttribute('aria-busy');
      if (textEl && original !== null) textEl.textContent = original;
    };
  }

  function setBanner(el, message, msgEl) {
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
   * <dialog>.showModal() 은 top layer 로 올라가서 z-index 로는 절대 못 넘는다.
   * 시트가 열려 있는 동안에는 토스트 컨테이너를 시트 안으로 옮겨 둔다.
   * (position:fixed 라 화면상 위치는 그대로다)
   */
  function relocateToasts(intoSheet) {
    var target = intoSheet ? $('#add-sheet') : document.body;
    if (toastHost.parentNode !== target) target.appendChild(toastHost);
  }

  function toast(message, kind) {
    var t = document.createElement('div');
    t.className = 'toast toast--' + (kind || 'info');
    t.setAttribute('role', kind === 'error' ? 'alert' : 'status');
    t.textContent = message;
    toastHost.appendChild(t);
    // 등장 애니메이션 프레임 분리
    requestAnimationFrame(function () { t.classList.add('is-in'); });
    setTimeout(function () {
      t.classList.remove('is-in');
      setTimeout(function () { t.remove(); }, 260);
    }, kind === 'error' ? 4200 : 2600);
  }

  /* ---------------- 오류 → 사람 말 ---------------- */
  function humanError(err) {
    if (!err) return '알 수 없는 문제가 생겼어요.';
    if (err.name === 'AbortError') return null;
    if (!err.isApiError) return '문제가 생겼어요. 잠시 후 다시 시도해 주세요.';

    switch (err.code) {
      case 'not_enough_clothes': return '상의와 하의를 최소 하나씩 등록해 주세요.';
      case 'no_body_photo': return '가상 착용을 하려면 전신 사진이 필요해요.';
      case 'duplicate_email': return '이미 가입된 이메일이에요.';
      case 'invalid_credentials': return '이메일 또는 비밀번호가 올바르지 않아요.';
      case 'session_expired': return '로그인이 만료되었어요. 다시 로그인해 주세요.';
      case 'network': return '서버에 연결할 수 없어요. Orbit 이 켜져 있는지 확인해 주세요.';
      case 'clothes_in_use': return '코디에 쓰인 옷이라 지금은 지울 수 없어요.';
    }
    switch (err.status) {
      case 0: return '서버에 연결할 수 없어요. Orbit 이 켜져 있는지 확인해 주세요.';
      case 400: return '입력한 내용을 다시 확인해 주세요.';
      case 401: return '로그인이 만료되었어요. 다시 로그인해 주세요.';
      case 403: return '권한이 없어요.';
      case 404: return '찾을 수 없어요. 새로고침 후 다시 시도해 주세요.';
      case 413: return '사진 용량이 너무 커요. 10MB 이하로 올려 주세요.';
      case 503: return 'AI 기능이 지금 꺼져 있어요.';
    }
    return '문제가 생겼어요. 잠시 후 다시 시도해 주세요.';
  }

  var CATEGORY = {
    TOP: { label: '상의', emoji: '👕' },
    BOTTOM: { label: '하의', emoji: '👖' },
    OUTER: { label: '아우터', emoji: '🧥' }
  };
  function catOf(key) { return CATEGORY[key] || { label: '기타', emoji: '🧺' }; }

  var MAX_UPLOAD = 10 * 1024 * 1024;

  /** 이미지가 없거나 깨져도 카테고리 플레이스홀더가 그대로 보이도록 겹쳐 그린다. */
  function thumbHtml(item, extraClass) {
    var c = catOf(item.mainCategory);
    var img = item.imageUrl
      ? '<img data-src="' + esc(item.imageUrl) + '" alt="" decoding="async" />'
      : '';
    return '<div class="thumb ' + (extraClass || '') + ' thumb--' + esc(item.mainCategory) + '">' +
      '<span class="thumb__emoji" aria-hidden="true">' + c.emoji + '</span>' + img + '</div>';
  }

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
        img.src = url;
      }).catch(function () {
        // 썸네일은 지워도 뒤의 플레이스홀더가 남는다. 그 외에는 자리만 비운다.
        if (img.closest('.thumb')) img.remove();
        else img.hidden = true;
      });
    });
  }

  // 깨진 이미지는 조용히 걷어내고 플레이스홀더를 남긴다. (error 는 버블링하지 않아 캡처로 듣는다)
  document.addEventListener('error', function (e) {
    var t = e.target;
    if (t && t.tagName === 'IMG' && t.closest('.thumb')) t.remove();
  }, true);

  /* ================================================================
   * 상태
   * ================================================================ */
  var state = {
    user: null,
    screen: 'closet',

    clothes: [],
    page: 0,
    totalPages: 0,
    totalElements: 0,
    filter: 'ALL',
    clothesLoaded: false,
    pendingDelete: null,

    coords: [],
    coordsLoaded: false,

    addImage: null,
    analyzeAbort: null
  };

  /* ================================================================
   * 화면 전환
   * ================================================================ */
  var SCREENS = {
    closet: { el: '#screen-closet', title: '내 옷장' },
    coord: { el: '#screen-coord', title: '오늘의 코디' },
    settings: { el: '#screen-settings', title: '설정' }
  };

  function goto(name) {
    state.screen = name;
    Object.keys(SCREENS).forEach(function (k) {
      show($(SCREENS[k].el), k === name);
    });
    $('#topbar-title').textContent = SCREENS[name].title;
    $$('.tabbar__item').forEach(function (b) {
      var active = b.dataset.nav === name;
      b.classList.toggle('is-active', active);
      if (active) b.setAttribute('aria-current', 'page');
      else b.removeAttribute('aria-current');
    });
    window.scrollTo(0, 0);

    // 처음 들어갈 때만 불러온다
    if (name === 'closet' && !state.clothesLoaded) loadClothes({ reset: true });
    if (name === 'coord' && !state.coordsLoaded) loadToday();
  }

  function showAuth(notice) {
    show($('#app-shell'), false);
    show($('#screen-auth'), true);
    show($('#boot'), false);
    setBanner($('#auth-notice'), notice || '');
    setBanner($('#auth-error'), '');
    // 다시 로그인할 때 이전 사용자 데이터가 남지 않게 초기화
    state.user = null;
    state.clothes = []; state.page = 0; state.totalPages = 0;
    state.totalElements = 0; state.clothesLoaded = false;
    state.coords = []; state.coordsLoaded = false;
  }

  function showApp() {
    show($('#screen-auth'), false);
    show($('#app-shell'), true);
    show($('#boot'), false);
    goto('closet');
  }

  api.onSessionExpired(function () {
    showAuth('로그인이 만료되었어요. 다시 로그인해 주세요.');
  });

  /* ================================================================
   * 1. 로그인 / 가입
   * ================================================================ */
  $$('[data-auth-tab]').forEach(function (tab) {
    tab.addEventListener('click', function () {
      var mode = tab.dataset.authTab;
      $$('[data-auth-tab]').forEach(function (t) {
        var on = t === tab;
        t.classList.toggle('is-active', on);
        t.setAttribute('aria-selected', on ? 'true' : 'false');
      });
      show($('#form-login'), mode === 'login');
      show($('#form-signup'), mode === 'signup');
      setBanner($('#auth-error'), '');
      setBanner($('#auth-notice'), '');
    });
  });

  $('#form-login').addEventListener('submit', function (e) {
    e.preventDefault();
    var email = $('#login-email').value.trim();
    var password = $('#login-password').value;
    setBanner($('#auth-error'), '');

    if (!email || !password) {
      setBanner($('#auth-error'), '이메일과 비밀번호를 모두 입력해 주세요.');
      return;
    }

    var done = busy(e.target.querySelector('button[type=submit]'), '로그인 중…');
    api.auth.login(email, password)
      .then(function () { return bootAfterLogin(); })
      .catch(function (err) {
        setBanner($('#auth-error'), humanError(err) || '로그인에 실패했어요.');
      })
      .finally(done);
  });

  $('#form-signup').addEventListener('submit', function (e) {
    e.preventDefault();
    var email = $('#signup-email').value.trim();
    var password = $('#signup-password').value;
    setBanner($('#auth-error'), '');

    if (!email || !password) {
      setBanner($('#auth-error'), '이메일과 비밀번호를 모두 입력해 주세요.');
      return;
    }
    if (password.length < 8) {
      setBanner($('#auth-error'), '비밀번호는 8자 이상으로 정해 주세요.');
      return;
    }

    var done = busy(e.target.querySelector('button[type=submit]'), '가입 중…');
    api.auth.signup(email, password)
      // 가입 직후 바로 로그인시킨다. 방금 친 비밀번호를 또 치게 하지 않는다.
      .then(function () { return api.auth.login(email, password); })
      .then(function () { return bootAfterLogin(); })
      .catch(function (err) {
        setBanner($('#auth-error'), humanError(err) || '가입에 실패했어요.');
      })
      .finally(done);
  });

  function bootAfterLogin() {
    return api.users.me().then(function (me) {
      state.user = me;
      renderProfile();
      showApp();
    }).catch(function (err) {
      // /me 가 실패해도 토큰은 있다. 앱은 열어 주되 이유는 알려 준다.
      if (err.isApiError && err.status === 401) return;
      showApp();
      toast(humanError(err), 'error');
    });
  }

  /* ================================================================
   * 2. 옷장
   * ================================================================ */
  var closetGrid = $('#closet-grid');

  function loadClothes(opts) {
    opts = opts || {};
    if (opts.reset) {
      state.page = 0;
      state.clothes = [];
      state.pendingDelete = null;
      closetGrid.innerHTML = '';
    }

    var first = state.page === 0;
    setBanner($('#closet-error'), '', $('#closet-error-msg'));
    if (first) {
      show($('#closet-empty'), false);
      show($('#closet-empty-filter'), false);
      show($('#closet-more'), false);
      show($('#closet-count'), false);   // 실패했을 때 이전 개수가 남지 않도록
      show($('#closet-skeleton'), true);
    }
    var moreBtn = $('#btn-load-more');
    var done = first ? function () {} : busy(moreBtn, '불러오는 중…');

    return api.clothes.list({
      page: state.page,
      size: 20,
      mainCategory: state.filter === 'ALL' ? null : state.filter
    }).then(function (data) {
      state.clothesLoaded = true;
      state.totalPages = data.totalPages || 0;
      state.totalElements = data.totalElements || 0;
      // 서버가 mainCategory 파라미터를 무시하더라도 visibleClothes() 가 한 번 더
      // 거르므로 화면에는 고른 카테고리만 보인다.
      state.clothes = state.clothes.concat(data.content || []);
      renderCloset();
    }).catch(function (err) {
      if (err.isApiError && err.code === 'session_expired') return;
      if (first) {
        setBanner($('#closet-error'), humanError(err), $('#closet-error-msg'));
      } else {
        toast(humanError(err), 'error');
      }
    }).finally(function () {
      show($('#closet-skeleton'), false);
      done();
    });
  }

  function visibleClothes() {
    if (state.filter === 'ALL') return state.clothes;
    return state.clothes.filter(function (c) { return c.mainCategory === state.filter; });
  }

  function renderCloset() {
    var items = visibleClothes();
    var isEmptyAll = state.clothes.length === 0;
    var hasMore = state.page + 1 < state.totalPages;

    show($('#closet-empty'), isEmptyAll);
    show($('#closet-empty-filter'), !isEmptyAll && items.length === 0);
    show($('#closet-more'), hasMore);
    show($('#closet-count'), !isEmptyAll);

    if (!isEmptyAll) {
      $('#closet-count').textContent = state.filter === 'ALL'
        ? '전체 ' + state.totalElements + '벌'
        : catOf(state.filter).label + ' ' + items.length + '벌';
    }

    closetGrid.innerHTML = items.map(clothesCardHtml).join('');
    hydrateImages(closetGrid);
  }

  function clothesCardHtml(item) {
    var c = catOf(item.mainCategory);
    var confirming = state.pendingDelete === item.id;
    return '' +
      '<article class="card' + (confirming ? ' is-confirming' : '') + '" data-id="' + esc(item.id) + '">' +
        thumbHtml(item, 'thumb--card') +
        '<div class="card__body">' +
          '<h3 class="card__name" title="' + esc(item.name) + '">' + esc(item.name) + '</h3>' +
          '<p class="card__meta">' +
            '<span class="tag">' + esc(c.label) + '</span>' +
            (item.color ? '<span class="card__color">' + esc(item.color) + '</span>' : '') +
            (item.detail ? '<span class="card__detail">' + esc(item.detail) + '</span>' : '') +
          '</p>' +
        '</div>' +
        '<button class="card__del" data-action="ask-delete" aria-label="' + esc(item.name) + ' 삭제">🗑</button>' +
        '<div class="card__confirm" ' + (confirming ? '' : 'hidden') + ' role="alertdialog" aria-label="삭제 확인">' +
          '<p>이 옷을 지울까요?</p>' +
          '<div class="card__confirm-actions">' +
            '<button class="btn btn--tiny btn--ghost" data-action="cancel-delete">취소</button>' +
            '<button class="btn btn--tiny btn--danger" data-action="confirm-delete" data-label="삭제">' +
              '<span class="btn__spinner" aria-hidden="true"></span><span class="btn__text">삭제</span>' +
            '</button>' +
          '</div>' +
        '</div>' +
      '</article>';
  }

  // 카드 액션 (이벤트 위임)
  closetGrid.addEventListener('click', function (e) {
    var btn = e.target.closest('[data-action]');
    if (!btn) return;
    var card = btn.closest('.card');
    if (!card) return;
    var id = card.dataset.id;

    if (btn.dataset.action === 'ask-delete') {
      state.pendingDelete = findId(id);
      renderCloset();
      var next = closetGrid.querySelector('.card.is-confirming [data-action="cancel-delete"]');
      if (next) next.focus();
    } else if (btn.dataset.action === 'cancel-delete') {
      state.pendingDelete = null;
      renderCloset();
    } else if (btn.dataset.action === 'confirm-delete') {
      var done = busy(btn, '삭제 중…');
      var realId = findId(id);
      api.clothes.remove(realId).then(function () {
        state.clothes = state.clothes.filter(function (c) { return String(c.id) !== String(id); });
        state.totalElements = Math.max(0, state.totalElements - 1);
        state.pendingDelete = null;
        renderCloset();
        toast('삭제했어요.');
      }).catch(function (err) {
        if (err.isApiError && err.code === 'session_expired') return;
        done();
        toast(humanError(err), 'error');
      });
    }
  });

  /** id 는 dataset 을 거치며 문자열이 된다. 원본(숫자일 수 있음)을 되돌려 준다. */
  function findId(id) {
    var found = state.clothes.filter(function (c) { return String(c.id) === String(id); })[0];
    return found ? found.id : id;
  }

  $$('[data-filter]').forEach(function (chip) {
    chip.addEventListener('click', function () {
      if (state.filter === chip.dataset.filter) return;
      state.filter = chip.dataset.filter;
      $$('[data-filter]').forEach(function (c) {
        var on = c === chip;
        c.classList.toggle('is-active', on);
        c.setAttribute('aria-pressed', on ? 'true' : 'false');
      });
      loadClothes({ reset: true });
    });
  });

  $('#btn-load-more').addEventListener('click', function () {
    state.page += 1;
    loadClothes();
  });

  $('#btn-closet-retry').addEventListener('click', function () {
    loadClothes({ reset: true });
  });

  /* ---------------- 옷 추가 시트 ---------------- */
  var sheet = $('#add-sheet');

  function openAddSheet() {
    resetAddForm();
    if (typeof sheet.showModal === 'function') sheet.showModal();
    else sheet.setAttribute('open', '');
    relocateToasts(true);
    setTimeout(function () { $('#add-image-label').focus(); }, 30);
  }

  function closeAddSheet() {
    if (state.analyzeAbort) { state.analyzeAbort.abort(); state.analyzeAbort = null; }
    if (sheet.open && typeof sheet.close === 'function') sheet.close();
    else sheet.removeAttribute('open');
    relocateToasts(false);
  }

  function resetAddForm() {
    $('#form-add').reset();
    state.addImage = null;
    $('#add-thumb-img').hidden = true;
    $('#add-thumb-img').removeAttribute('src');
    show($('#btn-clear-image'), false);
    show($('#analyze-loading'), false);
    setBanner($('#add-error'), '');
    setBanner($('#analyze-warn'), '');
    setCategory('TOP');
  }

  /** 분석 결과 안내. 시트 안에서는 토스트 대신 인라인 배너를 쓴다. */
  function analyzeNotice(message, kind) {
    var el = $('#analyze-warn');
    el.classList.toggle('banner--ok', kind === 'ok');
    el.classList.toggle('banner--warn', kind !== 'ok');
    setBanner(el, message);
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
    if (!this.classList.contains('is-disabled')) $('#body-photo-input').click();
  });

  $('#btn-open-add').addEventListener('click', openAddSheet);
  $$('[data-open-add]').forEach(function (b) {
    b.addEventListener('click', openAddSheet);
  });
  $('#btn-add-cancel').addEventListener('click', closeAddSheet);
  // ESC·✕(form method=dialog)로 닫힐 때도 뒷정리는 한 곳에서 한다.
  sheet.addEventListener('close', function () {
    if (state.analyzeAbort) { state.analyzeAbort.abort(); state.analyzeAbort = null; }
    relocateToasts(false);
  });
  // 배경(백드롭)을 눌러도 닫히게
  sheet.addEventListener('click', function (e) {
    if (e.target === sheet) closeAddSheet();
  });

  $('#btn-clear-image').addEventListener('click', function () {
    if (state.analyzeAbort) { state.analyzeAbort.abort(); state.analyzeAbort = null; }
    state.addImage = null;
    $('#add-image').value = '';
    $('#add-thumb-img').hidden = true;
    $('#add-thumb-img').removeAttribute('src');
    show($('#btn-clear-image'), false);
    show($('#analyze-loading'), false);
    setBanner($('#analyze-warn'), '');
  });

  /**
   * 사진을 고르면 먼저 분석해서 폼을 채운다.
   * 분석은 "도우미"일 뿐이라, 실패해도 폼은 계속 쓸 수 있어야 한다.
   * (그래서 실패는 에러가 아니라 노란 안내로 띄운다)
   */
  $('#add-image').addEventListener('change', function (e) {
    var file = e.target.files && e.target.files[0];
    if (!file) return;

    if (file.size > MAX_UPLOAD) {
      setBanner($('#add-error'), '사진이 너무 커요. 10MB 이하로 골라 주세요.');
      e.target.value = '';
      return;
    }

    setBanner($('#add-error'), '');
    setBanner($('#analyze-warn'), '');
    state.addImage = file;

    var img = $('#add-thumb-img');
    var url = URL.createObjectURL(file);
    img.onload = function () { URL.revokeObjectURL(url); };
    img.src = url;
    img.hidden = false;
    show($('#btn-clear-image'), true);

    if (state.analyzeAbort) state.analyzeAbort.abort();
    var ctrl = ('AbortController' in window) ? new AbortController() : null;
    state.analyzeAbort = ctrl;

    show($('#analyze-loading'), true);
    $('#btn-add-submit').disabled = true;

    api.clothes.analyze(file, ctrl ? ctrl.signal : undefined)
      .then(function (result) {
        if (state.addImage !== file) return; // 그 사이 다른 사진을 골랐다
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
        if (err.isApiError && err.code === 'session_expired') return;
        var msg = err.isApiError && err.status === 503
          ? 'AI 기능이 지금 꺼져 있어요. 직접 입력해서 저장할 수 있어요.'
          : '사진을 자동으로 읽지 못했어요. 직접 입력해서 저장할 수 있어요.';
        analyzeNotice(msg, 'warn');
      })
      .finally(function () {
        if (state.addImage !== file && state.addImage !== null) return;
        show($('#analyze-loading'), false);
        $('#btn-add-submit').disabled = false;
        state.analyzeAbort = null;
        if (!$('#add-name').value) $('#add-name').focus();
      });
  });

  $('#form-add').addEventListener('submit', function (e) {
    e.preventDefault();
    var name = $('#add-name').value.trim();
    var color = $('#add-color').value.trim();
    var detail = $('#add-detail').value.trim();
    setBanner($('#add-error'), '');

    if (!name) {
      setBanner($('#add-error'), '옷 이름을 적어 주세요.');
      $('#add-name').focus();
      return;
    }

    var done = busy($('#btn-add-submit'), '저장 중…');
    api.clothes.create({
      image: state.addImage,
      name: name,
      mainCategory: currentCategory(),
      color: color || null,
      detail: detail || null
    }).then(function (created) {
      closeAddSheet();
      toast('‘' + created.name + '’ 을(를) 옷장에 담았어요.');
      // 총 개수·정렬이 서버 기준이므로 첫 페이지부터 다시 읽는다.
      state.clothesLoaded = true;
      return loadClothes({ reset: true });
    }).catch(function (err) {
      if (err.isApiError && err.code === 'session_expired') return;
      setBanner($('#add-error'), humanError(err));
    }).finally(done);
  });

  /* ================================================================
   * 3. 오늘의 코디
   * ================================================================ */
  var coordList = $('#coord-list');

  function loadToday() {
    show($('#coord-skeleton'), true);
    show($('#coord-empty'), false);
    setBanner($('#coord-error'), '', $('#coord-error-msg'));

    return api.coordinations.today().then(function (list) {
      state.coordsLoaded = true;
      state.coords = (list || []).slice().sort(function (a, b) {
        return new Date(b.createdAt) - new Date(a.createdAt);
      });
      renderCoords();
    }).catch(function (err) {
      if (err.isApiError && err.code === 'session_expired') return;
      showCoordError(humanError(err));
    }).finally(function () {
      show($('#coord-skeleton'), false);
    });
  }

  function showCoordError(msg, action) {
    setBanner($('#coord-error'), msg, $('#coord-error-msg'));
    var btn = $('#btn-coord-error-action');
    if (action) {
      btn.textContent = action.label;
      btn.hidden = false;
      btn.onclick = action.run;
    } else {
      btn.hidden = true;
      btn.onclick = null;
    }
  }

  function renderCoords() {
    show($('#coord-empty'), state.coords.length === 0);
    $('#coord-date').textContent = state.coords.length
      ? '오늘 만든 코디 ' + state.coords.length + '개'
      : '';
    coordList.innerHTML = state.coords.map(coordCardHtml).join('');
    hydrateImages(coordList);
  }

  function timeOf(iso) {
    var d = new Date(iso);
    if (isNaN(d)) return '';
    return d.getHours() + '시 ' + String(d.getMinutes()).padStart(2, '0') + '분';
  }

  function coordCardHtml(c, index) {
    var items = (c.items || []).slice().sort(function (a, b) {
      return (a.layerOrder || 0) - (b.layerOrder || 0);
    });

    var layers = items.map(function (it, i) {
      return '<li class="layer">' +
        thumbHtml(it, 'thumb--layer') +
        '<div class="layer__text">' +
          '<span class="layer__order">' + (i + 1) + '</span>' +
          '<span class="layer__name">' + esc(it.name) + '</span>' +
          '<span class="layer__cat">' + esc(catOf(it.mainCategory).label) + '</span>' +
        '</div>' +
      '</li>';
    }).join('');

    var tryOn = c.tryOnImageUrl
      ? '<figure class="tryon__result"><img data-src="' + esc(c.tryOnImageUrl) + '" alt="가상 착용 결과" />' +
        '<figcaption>가상 착용 결과</figcaption></figure>'
      : '<div class="tryon__idle">' +
          '<button class="btn btn--ghost btn--block" data-action="tryon" data-label="입어보기">' +
            '<span class="btn__text">🪞 입어보기</span>' +
          '</button>' +
          '<div class="progress" hidden>' +
            '<div class="progress__bar"><div class="progress__fill" style="width:0%"></div></div>' +
            '<p class="progress__label">준비하는 중…</p>' +
          '</div>' +
          '<p class="tryon__error" role="alert" hidden></p>' +
        '</div>';

    return '' +
      '<article class="coord-card' + (index === 0 ? ' is-latest' : '') + '" data-id="' + esc(c.id) + '">' +
        '<header class="coord-card__head">' +
          '<h3 class="coord-card__title">' + esc(c.title || '오늘의 코디') + '</h3>' +
          '<span class="coord-card__time">' + esc(timeOf(c.createdAt)) + '</span>' +
        '</header>' +
        (c.reason ? '<p class="coord-reason"><span aria-hidden="true">💡</span> ' + esc(c.reason) + '</p>' : '') +
        '<ol class="layers">' + layers + '</ol>' +
        '<div class="tryon">' + tryOn + '</div>' +
      '</article>';
  }

  /* ---------------- 추천받기 (409 자동 재시도) ---------------- */
  var MAX_DUP_RETRY = 3;

  $('#btn-recommend').addEventListener('click', function () {
    var btn = this;
    var done = busy(btn, '고르는 중…');
    setBanner($('#coord-error'), '', $('#coord-error-msg'));
    show($('#coord-retry'), false);

    recommendWithRetry(function (attempt) {
      $('#coord-retry-msg').textContent = '다른 조합을 찾는 중… (' + attempt + '/' + MAX_DUP_RETRY + ')';
      show($('#coord-retry'), true);
    }).then(function (created) {
      show($('#coord-retry'), false);
      // 서버가 오늘 목록의 주인이다. 로컬에서 합치지 않고 다시 읽는다.
      return loadToday().then(function () {
        toast('오늘의 코디를 골랐어요.');
        var card = coordList.querySelector('[data-id="' + String(created.id).replace(/"/g, '') + '"]');
        if (card) {
          card.classList.add('is-new');
          card.scrollIntoView({ behavior: 'smooth', block: 'center' });
        }
      });
    }).catch(function (err) {
      show($('#coord-retry'), false);
      if (err && err.code === 'duplicate_exhausted') {
        showCoordError('오늘 나올 수 있는 조합을 다 봤어요. 옷을 더 등록하면 새로운 조합이 생겨요.', {
          label: '옷 추가하러 가기',
          run: function () { goto('closet'); openAddSheet(); }
        });
        return;
      }
      if (err.isApiError && err.code === 'session_expired') return;
      if (err.isApiError && err.code === 'not_enough_clothes') {
        showCoordError(humanError(err), {
          label: '옷 등록하기',
          run: function () { goto('closet'); openAddSheet(); }
        });
        return;
      }
      showCoordError(humanError(err));
    }).finally(done);
  });

  /**
   * 409 {error:"duplicate", retry:true} 는 "같은 요청을 다시 보내면 다른 조합이
   * 나올 수 있다"는 뜻이다. 최대 3회, 1초 간격으로 자동 재시도한다.
   * 3회 다 실패하면 더 시도해도 소용없다고 보고 사람에게 넘긴다.
   */
  function recommendWithRetry(onRetry) {
    function attempt(n) {
      return api.coordinations.recommend().catch(function (err) {
        var isDup = err.isApiError && err.status === 409;
        if (!isDup) throw err;
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

  /* ---------------- 입어보기 ---------------- */
  coordList.addEventListener('click', function (e) {
    var btn = e.target.closest('[data-action="tryon"]');
    if (!btn) return;

    var card = btn.closest('.coord-card');
    var id = card.dataset.id;
    var idle = btn.closest('.tryon__idle');
    var progress = $('.progress', idle);
    var errEl = $('.tryon__error', idle);

    errEl.hidden = true;
    btn.disabled = true;
    btn.hidden = true;
    progress.hidden = false;

    var sim = startProgress($('.progress__fill', progress), $('.progress__label', progress));

    api.coordinations.tryOn(id).then(function (res) {
      sim.finish();
      var url = res && res.tryOnImageUrl;
      var coord = state.coords.filter(function (c) { return String(c.id) === String(id); })[0];
      if (coord) coord.tryOnImageUrl = url;

      // 마지막 프레임(100%)을 잠깐 보여주고 결과로 바꾼다
      setTimeout(function () {
        if (url) {
          $('.tryon', card).innerHTML =
            '<figure class="tryon__result"><img data-src="' + esc(url) + '" alt="가상 착용 결과" />' +
            '<figcaption>가상 착용 결과</figcaption></figure>';
          hydrateImages(card);
        } else {
          progress.hidden = true;
          btn.hidden = false;
          btn.disabled = false;
          errEl.textContent = '이미지를 받지 못했어요. 다시 시도해 주세요.';
          errEl.hidden = false;
        }
      }, 420);
    }).catch(function (err) {
      sim.stop();
      progress.hidden = true;
      btn.hidden = false;
      btn.disabled = false;
      if (err.isApiError && err.code === 'session_expired') return;

      if (err.isApiError && err.code === 'no_body_photo') {
        errEl.innerHTML = '가상 착용을 하려면 전신 사진이 필요해요. ' +
          '<button class="link-btn" data-action="go-settings">설정에서 등록하기</button>';
      } else {
        errEl.textContent = humanError(err);
      }
      errEl.hidden = false;
    });
  });

  coordList.addEventListener('click', function (e) {
    if (e.target.closest('[data-action="go-settings"]')) goto('settings');
  });

  /**
   * 진행 감각용 시뮬레이션. 서버가 진행률을 주지 않으므로, 남은 거리에 비례해
   * 느려지며 95% 에서 멈춘다 — "곧 끝날 것 같은데 안 끝나는" 느낌을 피하려고
   * 100% 는 실제 응답이 왔을 때만 찍는다.
   */
  function startProgress(fillEl, labelEl) {
    var stages = [
      [0, '준비하는 중…'],
      [20, '전신 사진을 살펴보는 중…'],
      [45, '옷을 하나씩 입혀 보는 중…'],
      [72, '마무리하는 중… 조금만 기다려 주세요'],
      [92, '거의 다 됐어요…']
    ];
    var pct = 0;
    var timer = setInterval(function () {
      pct += Math.max(0.35, (96 - pct) * 0.035);
      if (pct > 96) pct = 96;
      apply();
    }, 320);

    function apply() {
      fillEl.style.width = pct.toFixed(1) + '%';
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
      finish: function () {
        clearInterval(timer);
        fillEl.style.width = '100%';
        labelEl.textContent = '완성됐어요!';
      }
    };
  }

  /* ================================================================
   * 4. 설정 / 프로필
   * ================================================================ */
  function renderProfile() {
    var me = state.user;
    $('#settings-email').textContent = me && me.email ? me.email : '';
    var img = $('#body-photo-img');
    var ph = $('#body-photo-placeholder');
    if (me && me.bodyPhotoUrl) {
      $('#body-photo-label').textContent = '사진 바꾸기';
      api.media.objectUrl(me.bodyPhotoUrl).then(function (url) {
        img.src = url;
        img.hidden = false;
        ph.hidden = true;
      }).catch(function () {
        img.hidden = true;
        ph.hidden = false;
      });
    } else {
      img.hidden = true;
      img.removeAttribute('src');
      ph.hidden = false;
      $('#body-photo-label').textContent = '사진 선택';
    }
  }

  $('#body-photo-input').addEventListener('change', function (e) {
    var file = e.target.files && e.target.files[0];
    if (!file) return;
    setBanner($('#body-photo-error'), '');

    if (file.size > MAX_UPLOAD) {
      setBanner($('#body-photo-error'), '사진이 너무 커요. 10MB 이하로 골라 주세요.');
      e.target.value = '';
      return;
    }

    // 서버 응답을 기다리기 전에 먼저 보여 준다 (실패하면 되돌린다)
    var previousUrl = state.user && state.user.bodyPhotoUrl;
    var localUrl = URL.createObjectURL(file);
    var img = $('#body-photo-img');
    img.src = localUrl;
    img.hidden = false;
    $('#body-photo-placeholder').hidden = true;

    show($('#body-photo-loading'), true);
    $('#body-photo-label').classList.add('is-disabled');

    api.users.uploadBodyPhoto(file).then(function (res) {
      // 서버 사진을 받아 끼울 때까지 미리보기를 살려 둔다. 바로 revoke 하면
      // 그 사이 이미지가 깜빡인다.
      setTimeout(function () { URL.revokeObjectURL(localUrl); }, 2000);
      state.user = state.user || {};
      state.user.bodyPhotoUrl = (res && res.bodyPhotoUrl) || previousUrl;
      renderProfile();
      toast('전신 사진을 저장했어요.');
    }).catch(function (err) {
      URL.revokeObjectURL(localUrl);
      if (state.user) state.user.bodyPhotoUrl = previousUrl;
      renderProfile();
      if (err.isApiError && err.code === 'session_expired') return;
      setBanner($('#body-photo-error'), humanError(err));
    }).finally(function () {
      show($('#body-photo-loading'), false);
      $('#body-photo-label').classList.remove('is-disabled');
      e.target.value = '';
    });
  });

  $('#btn-logout').addEventListener('click', function () {
    api.auth.logout();
    showAuth('로그아웃했어요.');
  });

  /* ================================================================
   * 내비게이션 / 부팅
   * ================================================================ */
  $$('.tabbar__item').forEach(function (b) {
    b.addEventListener('click', function () { goto(b.dataset.nav); });
  });

  function boot() {
    if (!api.tokens.exists()) {
      showAuth();
      return;
    }
    api.users.me().then(function (me) {
      state.user = me;
      renderProfile();
      showApp();
    }).catch(function (err) {
      if (err.isApiError && err.status === 401) {
        // onSessionExpired 가 이미 안내 문구와 함께 로그인 화면으로 보냈다.
        // 여기서 다시 showAuth() 를 부르면 그 문구를 지워 버린다.
        show($('#boot'), false);
        if ($('#screen-auth').hidden) showAuth();
        return;
      }
      // 서버가 잠깐 안 뜬 경우까지 로그아웃시키지는 않는다
      showApp();
      toast(humanError(err), 'error');
    });
  }

  boot();
})();
