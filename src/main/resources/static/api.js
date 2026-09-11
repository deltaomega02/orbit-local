/**
 * 서버 통신 층. 토큰 보관, 세션 자동 발급, 401 시 refresh 후 재시도, 오류 통일을 맡는다.
 * 화면 문구와 재시도 정책은 app.js 에서 정한다.
 */
(function (global) {
  'use strict';

  var ACCESS_KEY = 'orbit.accessToken';
  var REFRESH_KEY = 'orbit.refreshToken';

  // --- 토큰
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

  // --- 오류 타입
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

  // 세션 자동 발급까지 실패했을 때의 콜백. app.js 가 등록한다.
  var onSessionExpired = function () {};

  // 여러 요청이 동시에 401 을 받아도 refresh 는 한 번만 한다.
  // refresh 토큰이 회전하면서 서로를 무효화하기 때문이다.
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

  // --- 요청 본체
  function rawRequest(path, opts) {
    opts = opts || {};
    var headers = {};
    var body;

    if (opts.json !== undefined) {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(opts.json);
    } else if (opts.form) {
      // Content-Type 을 직접 지정하면 boundary 가 빠져 서버 파싱이 실패한다.
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

  // --- 세션 자동 발급 (1인용 로컬 앱이라 로그인 화면이 없다)
  var sessionInFlight = null;

  /**
   * 자격증명 없이 주인 계정 토큰을 받는다(POST /api/auth/session).
   * 서버가 127.0.0.1 에만 바인딩되어 있다는 전제에 기댄다.
   */
  function issueSession() {
    return rawRequest('/api/auth/session', { method: 'POST', auth: false })
      .then(function (data) {
        if (!data || !data.accessToken) throw ApiError(502, { error: 'no_session' });
        tokens.save(data);
        return data;
      });
  }

  /** 동시에 호출돼도 발급은 한 번만 한다. force 는 끊긴 세션을 다시 받을 때 쓴다. */
  function ensureSession(force) {
    if (!force && tokens.exists()) return Promise.resolve(null);
    if (sessionInFlight) return sessionInFlight;
    sessionInFlight = issueSession(!force).finally(function () { sessionInFlight = null; });
    return sessionInFlight;
  }

  /** 되살릴 수 없는 토큰을 버리고 새 세션을 받는다. */
  function renewSession() {
    tokens.clear();
    api.media.clearCache();
    return ensureSession(true).catch(function () { return failSession(); });
  }

  /** 발급까지 실패했다. 화면에 알리고 항상 reject 한다. */
  function failSession() {
    tokens.clear();
    api.media.clearCache();
    onSessionExpired();
    return Promise.reject(ApiError(401, { error: 'session_expired' }));
  }

  /** 인증 요청. 401 이면 refresh 를 한 번 시도하고, 안 되면 세션을 새로 받아 원 요청을 재실행한다. */
  function request(path, opts) {
    opts = opts || {};
    return rawRequest(path, opts).catch(function (err) {
      var retryable = err.isApiError && err.status === 401
        && opts.auth !== false && !opts._retried;
      if (!retryable) throw err;

      // catch 가 재시도 요청까지 감싸면 재시도의 500 도 세션 만료로 오인한다.
      return doRefresh().catch(function () {
        return renewSession();
      }).then(function () {
        return rawRequest(path, Object.assign({}, opts, { _retried: true }))
          .catch(function (err2) {
            // 새 토큰으로도 401 이면 복구 불가
            if (err2.isApiError && err2.status === 401) return failSession();
            throw err2;
          });
      });
    });
  }

  // --- 아직 배포되지 않은 엔드포인트
  // 404·405·501 과 /api/clothes/{id} 에 걸린 400 invalid_request("stats" 를 id 로 파싱)는
  // 장애가 아니라 미구현으로 보고 빈 값으로 넘긴다. 401·5xx 는 그대로 던진다.
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

  // --- 이미지 캐시
  // /media/** 는 인증 헤더가 필요해 <img src> 로 못 불러온다. fetch 해서 blob: URL 로 바꾸고,
  // 오래된 항목부터 정리해 메모리가 계속 늘지 않게 한다.
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

  // --- 엔드포인트
  var api = {
    tokens: tokens,
    ApiError: ApiError,
    request: request,
    isNotDeployed: isNotDeployed,

    onSessionExpired: function (fn) { onSessionExpired = fn; },

    auth: {
      /** 앱이 켜질 때. 쓸 수 있는 토큰이 없으면 그때만 새로 받는다. */
      ensure: function () { return ensureSession(false); },
      /** 토큰을 버리고 처음부터 다시 받는다. */
      renew: function () { return renewSession(); }
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
      },
      saveDisplayName: function (displayName) {
        return request('/api/users/me/display-name', {
          method: 'PUT', json: { displayName: displayName }
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
      /** 옷 등록. 선택 필드는 빈 값이 검증에 걸리지 않도록 값이 있을 때만 보낸다. */
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
       * 오늘의 코디 추천. situation("비 오고 쌀쌀해" 같은 한 줄)은 선택 항목이라
       * 비어 있으면 보내지 않는다. 이 필드를 모르는 서버와도 호환된다.
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
       * 미구현 오류도 삼키지 않는다. 화면이 isNotDeployed() 로 구분해 문구를 고른다.
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
