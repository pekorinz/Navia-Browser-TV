(function () {
  /* ===== Ajustes ===== */
  var FLAT_TOP   = 0.22;   // oscurecimiento plano sobre el video (0 a 1)
  var START_AT   = 38;     // % de alto donde empieza el degradado
  var BOTTOM_MAX = 0.66;   // oscuridad maxima abajo
  var LAYER      = 1;      // z-index del velo (0 o -1 si tapa las tarjetas; 10 si no se ve)
  var OPEN_BELOW = 0.6;    // panel de sugeridos abierto si la 1a tarjeta empieza por encima de este % del alto

  /* ===== Limpieza: permite pegarlo varias veces sin duplicar ===== */
  clearInterval(window.__tvScrimTimer);
  clearInterval(window.__tvBoxTimer);
  ['tv-fix', 'tv-fix2', 'tv-fix3', 'tv-scrim'].forEach(function (id) {
    var old = document.getElementById(id);
    if (old) old.remove();
  });
  document.documentElement.removeAttribute('data-tv-player');

  /* ===== 1) Estilos: SOLO activos con reproductor (html[data-tv-player]) ===== */
  var P = 'html[data-tv-player] ';
  var css = [
    /* Sin cajas negras: textos, tiempos y vista previa de la barra */
    P + 'yt-formatted-string,',
    P + '[idomkey="time-label"],',
    P + '[idomkey="time-label"] span,',
    P + '[idomkey="elapsedTime"],',
    P + '[idomkey="duration"] {',
    '  background: none !important;',
    '  background-color: transparent !important;',
    '  box-shadow: none !important;',
    '  text-shadow: 0 1px 3px rgba(0,0,0,.9) !important;',
    '}',
    /* Separadores de la linea de detalles (canal, vistas, fecha) */
    P + 'ytlr-video-metadata-line span::before,',
    P + 'ytlr-video-metadata-line span::after,',
    P + 'ytlr-video-metadata-line div::before,',
    P + 'ytlr-video-metadata-line div::after {',
    '  background: none !important;',
    '  background-color: transparent !important;',
    '  box-shadow: none !important;',
    '}',
    /* Gris mas claro en tarjetas sin foco */
    P + 'ytlr-tile-renderer:not(:focus-within) ytlr-tile-metadata-renderer yt-formatted-string {',
    '  color: #d6d6d6 !important;',
    '}'
  ].join('\n');

  var style = document.createElement('style');
  style.id = 'tv-fix';
  style.textContent = css;
  document.documentElement.appendChild(style);

  /* ===== 2) Deteccion de reproductor activo ===== */
  function playerActive() {
    var w = document.querySelector('ytlr-watch-default');
    if (!w) return false;
    var r = w.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) return false;
    var cs = getComputedStyle(w);
    return cs.display !== 'none' && cs.visibility !== 'hidden';
  }

  function panelOpen() {
    var tiles = document.querySelectorAll('ytlr-tile-renderer');
    for (var i = 0; i < tiles.length; i++) {
      var r = tiles[i].getBoundingClientRect();
      if (r.width > 0 && r.height > 0 && r.bottom > 0 && r.top < window.innerHeight * OPEN_BELOW) {
        return true;
      }
    }
    return false;
  }

  /* ===== 3) Velo + marca de reproductor ===== */
  var scrim = null;
  function buildScrim() {
    scrim = document.createElement('div');
    scrim.id = 'tv-scrim';
    scrim.style.cssText =
      'position:fixed;top:0;right:0;bottom:0;left:0;pointer-events:none;z-index:' + LAYER + ';' +
      'opacity:0;transition:opacity .25s ease;' +
      'background:linear-gradient(to bottom,' +
        'rgba(0,0,0,' + FLAT_TOP + ') 0%,' +
        'rgba(0,0,0,' + FLAT_TOP + ') ' + START_AT + '%,' +
        'rgba(0,0,0,' + BOTTOM_MAX + ') 100%);';
    document.body.appendChild(scrim);
  }

  window.__tvScrimTimer = setInterval(function () {
    var active = playerActive();
    var html = document.documentElement;
    if (active) html.setAttribute('data-tv-player', '1');
    else html.removeAttribute('data-tv-player');

    if (!scrim && document.body) buildScrim();
    if (scrim) scrim.style.opacity = (active && panelOpen()) ? '1' : '0';
  }, 250);
})();
