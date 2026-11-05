(function () {
  if (window.__keepAlive) return;
  window.__keepAlive = true;

  // 1) La pagina siempre 'visible' y 'con foco'
  var define = function (obj, prop, value) {
    try { Object.defineProperty(obj, prop, { get: function () { return value; }, configurable: true }); } catch (e) {}
  };
  define(Document.prototype, 'hidden', false);
  define(Document.prototype, 'webkitHidden', false);
  define(Document.prototype, 'visibilityState', 'visible');
  define(Document.prototype, 'webkitVisibilityState', 'visible');
  Document.prototype.hasFocus = function () { return true; };

  // 2) Bloquear los eventos que avisan del cambio
  var BLOCK = ['visibilitychange', 'webkitvisibilitychange', 'blur', 'pagehide', 'freeze'];
  var stop = function (e) { e.stopImmediatePropagation(); };
  BLOCK.forEach(function (t) {
    window.addEventListener(t, stop, true);
    document.addEventListener(t, stop, true);
  });

  // 3) Si algo pausa el video sin accion del usuario, reanudarlo
  var userPaused = false;
  window.addEventListener('keydown', function (e) {
    if (e.key === ' ' || e.key === 'k' || e.key === 'MediaPlayPause' || e.key === 'Enter') userPaused = true;
  }, true);
  window.addEventListener('pointerdown', function () { userPaused = true; }, true);

  document.addEventListener('pause', function (e) {
    var v = e.target;
    if (!(v instanceof HTMLVideoElement) || v.ended) return;
    if (userPaused) { userPaused = false; return; }
    setTimeout(function () { if (v.paused && !v.ended) { var p = v.play(); if (p && p.catch) p.catch(function () {}); } }, 150);
  }, true);

  // 4) Anti 'sigues ahi?'
  setInterval(function () { window._lact = Date.now(); }, 15000);
})();
