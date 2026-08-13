/**
 * api.js — 서버와 이야기하는 층. UI 는 여기서 아무것도 그리지 않는다.
 *
 * 여기에만 있는 것:
 *   - 토큰 보관 (localStorage)
 *   - 401 → refresh 1회 → 원래 요청 재시도 (실패하면 세션 종료 신호)
 *   - 서버 오류를 ApiError(status, body) 로 통일
 *   - "아직 안 만들어진 엔드포인트"를 빈 값으로 흘려보내는 optional()
 *
 * 화면 문구·재시도 정책 같은 "사람이 보는 판단"은 app.js 가 한다.
 * 층을 나눈 이유는 단순하다. 401 재발급을 화면마다 짜면 반드시 한 군데를
 * 빠뜨리고, 그 화면만 조용히 로그아웃되는 버그가 된다.
 */
(function (global) {
  'use strict';

  var ACCESS_KEY = 'orbit.accessToken';
  var REFRESH_KEY = 'orbit.refreshToken';

  /* ----------------------------------------------------------------
   * 토큰
   * ---------------------------------------------------------------- */
  var tokens = {
    access: function () {
      try { return localStorage.getItem(ACCESS_KEY); } catch (e) { return null; }
    },
    refresh: function () {
      try { return localStorage.getItem(REFRESH_KEY); } catch (e) { return null; }
    },
    save: function (pair) {
      try {
        if (pair.accessToken) localStorage.setItem(ACCESS_KEY, pair.accessToken);
        if (pair.refreshToken) localStorage.setItem(REFRESH_KEY, pair.refreshToken);
      } catch (e) { /* 사생활 보호 모드 등: 메모리로만 동작 */ }
    },
    clear: function () {
      try {
        localStorage.removeItem(ACCESS_KEY);
        localStorage.removeItem(REFRESH_KEY);
      } catch (e) { /* noop */ }
    },
    exists: function () { return !!tokens.access(); }
  };

  /* ----------------------------------------------------------------
   * 오류 타입
   * ---------------------------------------------------------------- */
  function ApiError(status, body, message) {
    var err = new Error(message || ('HTTP ' + status));
    err.name = 'ApiError';
    err.status = status;
    err.body = body || null;
    err.code = (body && body.error) || null;
    err.isApiError = true;
    return err;
  }

  /** 서버에 닿지도 못한 경우 (오프라인, 서버 꺼짐) */
  function NetworkError() {
    var err = new Error('network');
    err.name = 'NetworkError';
    err.status = 0;
    err.code = 'network';
    err.isApiError = true;
    return err;
  }

  /* ----------------------------------------------------------------
   * 세션 만료 콜백 — app.js 가 등록한다
   * ---------------------------------------------------------------- */
  var onSessionExpired = function () {};

  /* ----------------------------------------------------------------
   * refresh: 여러 요청이 동시에 401 을 받아도 재발급은 한 번만.
   * (안 그러면 refresh 토큰이 회전하는 순간 서로를 무효화한다)
   * ---------------------------------------------------------------- */
  var refreshInFlight = null;

  function doRefresh() {
    if (refreshInFlight) return refreshInFlight;

    var rt = tokens.refresh();
    if (!rt) return Promise.reject(ApiError(401, { error: 'no_refresh_token' }));

    refreshInFlight = rawRequest('/api/auth/refresh', {
      method: 'POST',
      json: { refreshToken: rt },
      auth: false
    }).then(function (data) {
      tokens.save({ accessToken: data.accessToken, refreshToken: data.refreshToken });
      return data.accessToken;
    }).finally(function () {
      refreshInFlight = null;
    });

    return refreshInFlight;
  }

  /* ----------------------------------------------------------------
   * 요청 본체
   * ---------------------------------------------------------------- */
  function rawRequest(path, opts) {
    opts = opts || {};
    var headers = {};
    var body;

    if (opts.json !== undefined) {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(opts.json);
    } else if (opts.form) {
      // FormData 는 Content-Type 을 브라우저가 boundary 와 함께 붙여야 한다.
      // 직접 지정하면 boundary 가 빠져서 서버가 파싱에 실패한다.
      body = opts.form;
    }

    if (opts.auth !== false) {
      var at = tokens.access();
      if (at) headers['Authorization'] = 'Bearer ' + at;
    }

    return fetch(path, {
      method: opts.method || 'GET',
      headers: headers,
      body: body,
      signal: opts.signal,
      cache: 'no-store'
    }).catch(function (e) {
      if (e && e.name === 'AbortError') throw e;
      throw NetworkError();
    }).then(function (res) {
      if (res.status === 204) return null;
      if (res.ok && opts.blob) return res.blob();

      return res.text().then(function (text) {
        var parsed = null;
        if (text) {
          try { parsed = JSON.parse(text); } catch (e) { parsed = { error: 'parse_error', detail: text }; }
        }
        if (!res.ok) throw ApiError(res.status, parsed);
        return parsed;
      });
    });
  }

  /** 세션 종료. 토큰을 버리고 화면을 로그인으로 되돌린 뒤 항상 reject 한다. */
  function endSession() {
    tokens.clear();
    api.media.clearCache();
    onSessionExpired();
    return Promise.reject(ApiError(401, { error: 'session_expired' }));
  }

  /**
   * 인증이 필요한 요청. 401 이면 refresh 를 한 번만 시도하고 원 요청을 재실행한다.
   * 재발급도 실패하면 토큰을 버리고 로그인 화면으로 돌려보낸다.
   */
  function request(path, opts) {
    opts = opts || {};
    return rawRequest(path, opts).catch(function (err) {
      var retryable = err.isApiError && err.status === 401
        && opts.auth !== false && !opts._retried;
      if (!retryable) throw err;

      // catch 를 재시도 요청까지 감싸면 안 된다. 그러면 재발급은 성공했는데
      // 재시도가 500 을 받은 경우까지 "세션 만료"로 오인해 로그아웃시킨다.
      return doRefresh().catch(function () {
        return endSession();
      }).then(function () {
        return rawRequest(path, Object.assign({}, opts, { _retried: true }))
          .catch(function (err2) {
            // 새 토큰으로도 401 이면 그때는 진짜 끝난 것이다.
            if (err2.isApiError && err2.status === 401) return endSession();
            throw err2;
          });
      });
    });
  }

  /* ----------------------------------------------------------------
   * 아직 배포되지 않은 엔드포인트
   *
   * 백엔드가 만드는 중인 API 는 서버 구현 방식에 따라 여러 얼굴로 없다.
   *   - 라우트 자체가 없으면 404
   *   - 같은 경로에 다른 메서드만 있으면 405
   *   - /api/clothes/{id} 같은 경로와 겹치면 400 invalid_request
   *     (예: "stats" 를 id 로 파싱하다 실패)
   * 이 셋은 "장애"가 아니라 "아직 없음"이다. 화면을 깨뜨리지 않고
   * 빈 값으로 흘려보낸다. 401·5xx 같은 진짜 문제는 그대로 통과시킨다.
   * ---------------------------------------------------------------- */
  function isNotDeployed(err) {
    if (!err || !err.isApiError) return false;
    if (err.status === 404 || err.status === 405 || err.status === 501) return true;
    if (err.status === 400 && (err.code === 'invalid_request' || err.code === 'parse_error')) return true;
    return false;
  }

  /** 없으면 fallback 을 돌려주는 요청. 결과에 __unavailable 을 달아 화면이 구분할 수 있게 한다. */
  function optional(promise, fallback) {
    return promise.catch(function (err) {
      if (!isNotDeployed(err)) throw err;
      var value = (typeof fallback === 'function') ? fallback() : fallback;
      if (value && typeof value === 'object') {
        try { Object.defineProperty(value, '__unavailable', { value: true, enumerable: false }); }
        catch (e) { /* 얼려진 객체면 넘어간다 */ }
      }
      return value;
    });
  }

  function emptyPage(size) {
    return { content: [], page: 0, size: size || 20, totalElements: 0, totalPages: 0, hasNext: false };
  }

  function qs(params) {
    var q = new URLSearchParams();
    Object.keys(params).forEach(function (k) {
      if (params[k] !== null && params[k] !== undefined && params[k] !== '') q.set(k, String(params[k]));
    });
    var s = q.toString();
    return s ? '?' + s : '';
  }

  /* ----------------------------------------------------------------
   * 이미지 캐시
   *
   * 서버가 /media/** 를 인증된 소유자에게만 내주므로 <img src="/media/...">
   * 로는 볼 수 없다 (헤더를 붙일 수 없다). fetch 로 받아 blob: URL 로 바꿔 끼운다.
   * 같은 경로는 한 번만 받고, 오래된 것부터 정리해 메모리가 무한정 늘지 않게 한다.
   * ---------------------------------------------------------------- */
  var mediaCache = {};
  var mediaOrder = [];
  var MEDIA_LIMIT = 80;

  function revokeEntry(path) {
    var p = mediaCache[path];
    if (!p) return;
    delete mediaCache[path];
    var i = mediaOrder.indexOf(path);
    if (i >= 0) mediaOrder.splice(i, 1);
    p.then(function (url) {
      if (typeof url === 'string' && url.indexOf('blob:') === 0) URL.revokeObjectURL(url);
    }, function () {});
  }

  /* ----------------------------------------------------------------
   * 엔드포인트
   * ---------------------------------------------------------------- */
  var api = {
    tokens: tokens,
    ApiError: ApiError,
    request: request,
    isNotDeployed: isNotDeployed,

    onSessionExpired: function (fn) { onSessionExpired = fn; },

    auth: {
      signup: function (email, password) {
        return rawRequest('/api/auth/signup', {
          method: 'POST', auth: false, json: { email: email, password: password }
        });
      },
      login: function (email, password) {
        return rawRequest('/api/auth/login', {
          method: 'POST', auth: false, json: { email: email, password: password }
        }).then(function (data) {
          tokens.save(data);
          return data;
        });
      },
      /** 세션을 버린다. 화면에 버튼은 없지만 만료 처리에는 필요하다. */
      end: function () { tokens.clear(); api.media.clearCache(); }
    },

    users: {
      me: function () { return request('/api/users/me'); },
      uploadBodyPhoto: function (file) {
        var fd = new FormData();
        fd.append('image', file);
        return request('/api/users/me/body-photo', { method: 'PUT', form: fd });
      },
      stylePreference: function () {
        return optional(request('/api/users/me/style-preference'), { preference: null });
      },
      saveStylePreference: function (preference) {
        return request('/api/users/me/style-preference', {
          method: 'PUT', json: { preference: preference }
        });
      }
    },

    clothes: {
      list: function (params) {
        params = params || {};
        return request('/api/clothes' + qs({
          page: params.page || 0,
          size: params.size || 20,
          mainCategory: params.mainCategory || null
        }));
      },
      get: function (id) {
        return request('/api/clothes/' + encodeURIComponent(id));
      },
      analyze: function (file, signal) {
        var fd = new FormData();
        fd.append('image', file);
        return request('/api/clothes/analyze', { method: 'POST', form: fd, signal: signal });
      },
      /**
       * 옷 등록.
       * subCategory·material·fit·season 은 백엔드가 만드는 중이다. 값이 있을 때만
       * 실어 보낸다 — 서버가 아직 모르는 필드는 무시할 뿐이고, 빈 값을 보내
       * 검증에 걸리는 일은 없어야 한다.
       */
      create: function (data) {
        var fd = new FormData();
        if (data.image) fd.append('image', data.image);
        fd.append('name', data.name);
        fd.append('mainCategory', data.mainCategory);
        ['subCategory', 'color', 'material', 'fit', 'season', 'detail'].forEach(function (k) {
          if (data[k]) fd.append(k, data[k]);
        });
        return request('/api/clothes', { method: 'POST', form: fd });
      },
      update: function (id, patch) {
        return request('/api/clothes/' + encodeURIComponent(id), { method: 'PATCH', json: patch });
      },
      remove: function (id) {
        return request('/api/clothes/' + encodeURIComponent(id), { method: 'DELETE' });
      },
      /** 이 옷이 쓰인 코디 (아직 없을 수 있다) */
      coordinations: function (id, params) {
        params = params || {};
        var size = params.size || 20;
        return optional(
          request('/api/clothes/' + encodeURIComponent(id) + '/coordinations' + qs({
            page: params.page || 0, size: size
          })),
          function () { return emptyPage(size); }
        );
      },
      /** 옷장 통계 (아직 없을 수 있다) */
      stats: function () {
        return optional(request('/api/clothes/stats'), function () {
          return { total: null, byCategory: {}, mostUsed: [], neverUsed: null };
        });
      }
    },

    coordinations: {
      /**
       * 오늘의 코디 추천.
       * situation 은 "비 오고 쌀쌀해" 같은 이번 한 번짜리 한 줄이다. 선택 항목이라
       * 비어 있으면 아예 실어 보내지 않는다 — 아직 이 필드를 모르는 서버에서도
       * 지금까지와 똑같이 동작해야 한다.
       */
      recommend: function (situation) {
        var body = {};
        if (situation) body.situation = String(situation).slice(0, 100);
        return request('/api/coordinations/recommend', { method: 'POST', json: body });
      },
      today: function () { return request('/api/coordinations/today'); },
      /** 전체 기록 최신순 (아직 없을 수 있다) */
      list: function (params) {
        params = params || {};
        var size = params.size || 12;
        return optional(
          request('/api/coordinations' + qs({ page: params.page || 0, size: size })),
          function () { return emptyPage(size); }
        );
      },
      get: function (id) {
        return request('/api/coordinations/' + encodeURIComponent(id));
      },
      remove: function (id) {
        return request('/api/coordinations/' + encodeURIComponent(id), { method: 'DELETE' });
      },
      toggleFavorite: function (id) {
        return request('/api/coordinations/' + encodeURIComponent(id) + '/favorite', { method: 'POST' });
      },
      tryOn: function (id) {
        return request('/api/coordinations/' + encodeURIComponent(id) + '/tryon', { method: 'POST' });
      },
      /**
       * 착용 사진만 지운다. 코디 기록과 옷은 남는다.
       *
       * 백엔드가 만드는 중이라 아직 없을 수 있다. 여기서 삼키지 않고 그대로
       * 던진다 — 화면이 isNotDeployed() 로 "아직 없음"과 "장애"를 구분해서
       * 다른 문구를 보여 줘야 하기 때문이다.
       */
      removeTryOn: function (id) {
        return request('/api/coordinations/' + encodeURIComponent(id) + '/tryon', { method: 'DELETE' });
      }
    },

    settings: {
      geminiKey: function () {
        return optional(request('/api/settings/gemini-key'), { configured: false, masked: null });
      },
      saveGeminiKey: function (key) {
        return request('/api/settings/gemini-key', { method: 'PUT', json: { key: key } });
      },
      removeGeminiKey: function () {
        return request('/api/settings/gemini-key', { method: 'DELETE' });
      }
    },

    media: {
      objectUrl: function (path) {
        if (!path) return Promise.reject(new Error('no path'));
        if (path.indexOf('/media/') === -1) return Promise.resolve(path); // 외부/공개 URL

        var i = mediaOrder.indexOf(path);
        if (i >= 0) mediaOrder.splice(i, 1);
        mediaOrder.push(path);

        if (!mediaCache[path]) {
          mediaCache[path] = request(path, { blob: true })
            .then(function (blob) { return URL.createObjectURL(blob); })
            .catch(function (err) { revokeEntry(path); throw err; });
        }
        // 오래 안 쓴 것부터 되돌려 준다
        while (mediaOrder.length > MEDIA_LIMIT) revokeEntry(mediaOrder[0]);
        return mediaCache[path];
      },
      /** 세션이 끝나면 메모리에 남은 개인 사진을 전부 버린다. */
      clearCache: function () {
        mediaOrder.slice().forEach(revokeEntry);
        mediaOrder.length = 0;
      }
    }
  };

  global.OrbitApi = api;
})(window);
