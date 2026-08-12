/**
 * api.js — 서버와 이야기하는 층. UI 는 여기서 아무것도 그리지 않는다.
 *
 * 여기에만 있는 것:
 *   - 토큰 보관 (localStorage)
 *   - 401 → refresh 1회 → 원래 요청 재시도 (실패하면 세션 종료 신호)
 *   - 서버 오류를 ApiError(status, body) 로 통일
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

  /** 경로 → blob URL 약속. 같은 사진을 여러 번 받지 않게 한다. */
  var mediaCache = {};

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
   * 엔드포인트 (계약 그대로)
   * ---------------------------------------------------------------- */
  var api = {
    tokens: tokens,
    ApiError: ApiError,
    request: request,

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
      logout: function () { tokens.clear(); api.media.clearCache(); }
    },

    users: {
      me: function () { return request('/api/users/me'); },
      uploadBodyPhoto: function (file) {
        var fd = new FormData();
        fd.append('image', file);
        return request('/api/users/me/body-photo', { method: 'PUT', form: fd });
      }
    },

    clothes: {
      list: function (params) {
        params = params || {};
        var q = new URLSearchParams();
        q.set('page', String(params.page || 0));
        q.set('size', String(params.size || 20));
        // 계약에 없는 선택 파라미터. 서버가 지원하면 페이지네이션이 정확해지고,
        // 무시해도 클라이언트 필터가 그대로 남아 있어 동작은 같다.
        if (params.mainCategory) q.set('mainCategory', params.mainCategory);
        return request('/api/clothes?' + q.toString());
      },
      analyze: function (file, signal) {
        var fd = new FormData();
        fd.append('image', file);
        return request('/api/clothes/analyze', { method: 'POST', form: fd, signal: signal });
      },
      create: function (data) {
        var fd = new FormData();
        if (data.image) fd.append('image', data.image);
        fd.append('name', data.name);
        fd.append('mainCategory', data.mainCategory);
        if (data.color) fd.append('color', data.color);
        return request('/api/clothes', { method: 'POST', form: fd });
      },
      remove: function (id) {
        return request('/api/clothes/' + encodeURIComponent(id), { method: 'DELETE' });
      }
    },

    /**
     * 이미지. 서버가 /media/** 를 인증된 소유자에게만 내주므로,
     * <img src="/media/..."> 는 헤더를 붙일 수 없어 그대로는 못 본다.
     * 그래서 fetch 로 받아 blob: URL 로 바꿔 끼운다. 같은 경로는 한 번만 받는다.
     */
    media: {
      objectUrl: function (path) {
        if (!path) return Promise.reject(new Error('no path'));
        if (path.indexOf('/media/') === -1) return Promise.resolve(path); // 외부/공개 URL
        if (!mediaCache[path]) {
          mediaCache[path] = request(path, { blob: true })
            .then(function (blob) { return URL.createObjectURL(blob); })
            .catch(function (err) { delete mediaCache[path]; throw err; });
        }
        return mediaCache[path];
      },
      /** 로그아웃 시 메모리에 남은 개인 사진을 버린다. */
      clearCache: function () {
        Object.keys(mediaCache).forEach(function (k) {
          var p = mediaCache[k];
          delete mediaCache[k];
          p.then(function (url) {
            if (typeof url === 'string' && url.indexOf('blob:') === 0) URL.revokeObjectURL(url);
          }, function () {});
        });
      }
    },

    coordinations: {
      recommend: function () {
        return request('/api/coordinations/recommend', { method: 'POST', json: {} });
      },
      today: function () { return request('/api/coordinations/today'); },
      tryOn: function (id) {
        return request('/api/coordinations/' + encodeURIComponent(id) + '/tryon', { method: 'POST' });
      }
    }
  };

  global.OrbitApi = api;
})(window);
