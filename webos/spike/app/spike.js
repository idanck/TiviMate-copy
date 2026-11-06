/*
 * Spike for webOS 5 (Chromium 68): which way of scaling the page and of scrolling a list works on
 * the television (ADR-056). One screen: a list of 100 rows, a strip that scrolls right to left, one,
 * two or four <video>s in a box, and a full-screen toggle. Every mode is switched at runtime and
 * kept in localStorage, and what the DOM says is printed on screen; what the screen shows is for
 * the person in front of it to say.
 *
 * Plain ES2017 with no build step, so nothing here is newer than Chromium 68.
 */
(function () {
  'use strict';

  var ROWS = 100;
  var CELLS = 30;
  var LIST_MODES = ['into-view', 'scroll-top', 'hidden', 'translate'];
  var SOURCES = {
    local: 'frame.mp4',
    network: 'https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8'
  };
  // Remote keys (webOS): Back 461, red 403, green 404, yellow 405, blue 406. Letters for a desktop.
  var KEY = { UP: 38, DOWN: 40, LEFT: 37, RIGHT: 39, OK: 13, BACK: 461, ESC: 27,
    RED: 403, GREEN: 404, YELLOW: 405, BLUE: 406 };

  var html = document.documentElement;
  var frame = document.getElementById('frame');
  var list = document.getElementById('list');
  var rows = document.getElementById('rows');
  var strip = document.getElementById('strip');
  var cells = document.getElementById('cells');
  var stage = document.getElementById('stage');
  var diag = document.getElementById('diag');
  var modesEl = document.getElementById('modes');
  var clock = document.getElementById('clock');

  // The window is 1280 wide when appinfo.json asks for 1280x720 and 1920 when it asks for 1920x1080.
  var windowScale = Math.round((window.innerWidth / 1280) * 100) / 100;
  var SCALE_MODES = windowScale > 1 ? ['native', 'transform', 'zoom'] : ['none'];

  var state = load({
    scale: SCALE_MODES[0],
    list: LIST_MODES[0],
    videos: 1,
    placement: 'inside',
    source: 'local'
  });
  if (SCALE_MODES.indexOf(state.scale) < 0) state.scale = SCALE_MODES[0];
  if (LIST_MODES.indexOf(state.list) < 0) state.list = LIST_MODES[0];

  var focusIndex = 0;
  var listOffset = 0; // the translate mode's own scroll position, in CSS pixels
  var cellIndex = 0;
  var stripLeft = 0; // the strip's visible left edge, measured from its content's left edge
  var full = false;
  var lastKey = '-';
  var listScrollEvents = 0;
  var stripScrollEvents = 0;
  var videos = [];
  var rtlModel = 'unknown'; // set once at start, by detectRtlModel()

  function load(defaults) {
    try {
      var saved = JSON.parse(localStorage.getItem('spike') || '{}');
      for (var k in defaults) if (!(k in saved)) saved[k] = defaults[k];
      return saved;
    } catch (e) {
      return defaults;
    }
  }

  function save() {
    try { localStorage.setItem('spike', JSON.stringify(state)); } catch (e) { /* no storage */ }
  }

  // ---- building the screen ----

  for (var i = 0; i < ROWS; i++) {
    var row = document.createElement('div');
    row.className = 'row';
    row.tabIndex = -1;
    row.textContent = 'ערוץ ' + (i + 1);
    rows.appendChild(row);
  }

  for (var c = 0; c < CELLS; c++) {
    var cell = document.createElement('div');
    cell.className = 'cell';
    cell.textContent = String(c + 1);
    cells.appendChild(cell);
  }

  var outside = document.createElement('div');
  outside.id = 'outside';
  document.body.appendChild(outside);

  list.addEventListener('scroll', function () { listScrollEvents++; }, { passive: true });
  strip.addEventListener('scroll', function () { stripScrollEvents++; }, { passive: true });

  // ---- modes ----

  function applyScale() {
    html.classList.remove('scale-none', 'scale-native', 'scale-transform', 'scale-zoom');
    html.classList.add('scale-' + state.scale);
  }

  /** Pixels per rem as laid out (the transform and zoom modes scale a 16px layout). */
  function rem() {
    return parseFloat(getComputedStyle(html).fontSize);
  }

  function applyListMode() {
    LIST_MODES.forEach(function (m) {
      list.classList.remove('m-' + m);
      strip.classList.remove('m-' + m);
    });
    list.classList.add('m-' + state.list);
    strip.classList.add('m-' + state.list);
    rows.style.transform = '';
    cells.style.transform = '';
    list.scrollTop = 0;
    listOffset = 0;
    layoutStrip();
    focusRow(focusIndex);
    focusCell(cellIndex);
  }

  // The strip's cells are placed by hand, cell 1 at the right as in a right-to-left guide. In the
  // translate mode the strip is left to right and never scrolls, so only the transform moves it.
  function layoutStrip() {
    var w = 7.5 * rem();
    var width = CELLS * w;
    cells.style.width = width + 'px';
    strip.dir = state.list === 'translate' ? 'ltr' : 'rtl';
    var kids = cells.children;
    for (var k = 0; k < kids.length; k++) kids[k].style.left = (width - (k + 1) * w) + 'px';
    stripLeft = Math.max(0, width - strip.clientWidth);
    setStripLeft(stripLeft);
  }

  // Chromium before 85 starts a right-to-left scroller at its largest scrollLeft and counts down to
  // 0; newer ones start at 0 and go negative. Asked of a probe of its own, made once, so a strip
  // already scrolled cannot mislead it.
  function detectRtlModel() {
    var probe = document.createElement('div');
    probe.dir = 'rtl';
    probe.style.cssText = 'position:absolute;top:-1000px;left:0;width:100px;height:10px;overflow:scroll;visibility:hidden';
    var wide = document.createElement('div');
    wide.style.cssText = 'width:300px;height:1px';
    probe.appendChild(wide);
    document.body.appendChild(probe);
    var start = probe.scrollLeft;
    probe.scrollLeft = -50;
    var moved = probe.scrollLeft;
    document.body.removeChild(probe);
    if (start > 0) return 'old';
    if (moved < 0) return 'new';
    return 'other';
  }

  function stripMax() {
    return Math.max(0, cells.offsetWidth - strip.clientWidth);
  }

  function setStripLeft(left) {
    stripLeft = left;
    if (state.list === 'translate') {
      strip.scrollLeft = 0;
      cells.style.transform = 'translateX(' + (-left) + 'px)';
    } else if (rtlModel === 'new') {
      strip.scrollLeft = left - stripMax();
    } else {
      strip.scrollLeft = left;
    }
  }

  // ---- the list ----

  function focusRow(index) {
    focusIndex = Math.max(0, Math.min(ROWS - 1, index));
    var row = rows.children[focusIndex];
    if (state.list === 'into-view') {
      row.focus();
      row.scrollIntoView({ block: 'nearest' });
      return;
    }
    row.focus({ preventScroll: true });
    var top = row.offsetTop;
    var h = row.offsetHeight;
    var view = list.clientHeight;
    var pos = state.list === 'translate' ? listOffset : list.scrollTop;
    if (top < pos) pos = top;
    else if (top + h > pos + view) pos = top + h - view;
    if (state.list === 'translate') {
      listOffset = pos;
      rows.style.transform = 'translateY(' + (-pos) + 'px)';
    } else {
      list.scrollTop = pos;
    }
  }

  function focusCell(index) {
    cellIndex = Math.max(0, Math.min(CELLS - 1, index));
    var kids = cells.children;
    for (var k = 0; k < kids.length; k++) kids[k].classList.toggle('on', k === cellIndex);
    var cell = kids[cellIndex];
    if (state.list === 'into-view') {
      cell.scrollIntoView({ block: 'nearest', inline: 'nearest' });
      return;
    }
    var left = cell.offsetLeft;
    var w = cell.offsetWidth;
    var view = strip.clientWidth;
    var pos = stripLeft;
    if (left < pos) pos = left;
    else if (left + w > pos + view) pos = left + w - view;
    setStripLeft(pos);
  }

  // ---- video ----

  function buildVideos() {
    videos.forEach(function (v) {
      v.el.pause();
      v.el.removeAttribute('src');
      v.el.load();
      if (v.el.parentNode) v.el.parentNode.removeChild(v.el);
    });
    videos = [];
    while (stage.firstChild) stage.removeChild(stage.firstChild);
    var n = state.videos;
    for (var k = 0; k < n; k++) {
      var slot = document.createElement('div');
      slot.className = 'slot';
      // Slots in percent of the box. The box is 16:9, so a slot as wide as it is high in percent is
      // 16:9 too, and the test picture's red edge should meet the yellow outline on every side.
      var size = n === 1 ? 100 : 48.75;
      var x = n === 1 ? 0 : (k % 2) * 50.25 + 0.5;
      var y = n === 1 ? 0 : n === 2 ? 25.625 : Math.floor(k / 2) * 50.25 + 0.5;
      slot.style.left = x + '%';
      slot.style.top = y + '%';
      slot.style.width = size + '%';
      slot.style.height = size + '%';
      var label = document.createElement('span');
      label.className = 'label';
      label.textContent = 'video ' + (k + 1);
      slot.appendChild(label);
      stage.appendChild(slot);

      var el = document.createElement('video');
      el.muted = k > 0;
      el.loop = true;
      el.autoplay = true;
      el.setAttribute('playsinline', '');
      var entry = { el: el, slot: slot, error: '' };
      el.addEventListener('error', onVideoError(entry));
      el.addEventListener('playing', function (en) { return function () { en.error = ''; }; }(entry));
      (state.placement === 'inside' ? slot : outside).appendChild(el);
      el.src = SOURCES[state.source];
      var p = el.play();
      if (p && p.catch) p.catch(onPlayRefused(entry));
      videos.push(entry);
    }
    placeVideos();
  }

  function onVideoError(entry) {
    return function () {
      var e = entry.el.error;
      entry.error = e ? 'code ' + e.code + (e.message ? ' ' + e.message : '') : 'error';
    };
  }

  function onPlayRefused(entry) {
    return function (err) { entry.error = 'play(): ' + (err && err.name); };
  }

  /** In the outside placement a video sits in window pixels over its slot, wherever that is. */
  function placeVideos() {
    if (state.placement !== 'outside') return;
    videos.forEach(function (v) {
      var r = v.slot.getBoundingClientRect();
      v.el.style.left = r.left + 'px';
      v.el.style.top = r.top + 'px';
      v.el.style.width = r.width + 'px';
      v.el.style.height = r.height + 'px';
    });
  }

  function setFull(on) {
    full = on;
    stage.classList.toggle('full', on);
    placeVideos();
  }

  // ---- what the DOM says ----

  function rect(el) {
    var r = el.getBoundingClientRect();
    return Math.round(r.left) + ',' + Math.round(r.top) + ' ' + Math.round(r.width) + 'x' + Math.round(r.height);
  }

  function inside(inner, outer) {
    var a = inner.getBoundingClientRect();
    var b = outer.getBoundingClientRect();
    return a.top >= b.top - 1 && a.bottom <= b.bottom + 1 && a.left >= b.left - 1 && a.right <= b.right + 1;
  }

  function sameRect(a, b) {
    var x = a.getBoundingClientRect();
    var y = b.getBoundingClientRect();
    return Math.abs(x.left - y.left) < 2 && Math.abs(x.top - y.top) < 2 &&
      Math.abs(x.width - y.width) < 2 && Math.abs(x.height - y.height) < 2;
  }

  function chrome() {
    var m = /Chrome\/(\d+)/.exec(navigator.userAgent);
    return m ? m[1] : '?';
  }

  function appId() {
    try { return window.PalmSystem ? window.PalmSystem.identifier : 'no PalmSystem'; } catch (e) { return '?'; }
  }

  function report() {
    var row = rows.children[focusIndex];
    var cell = cells.children[cellIndex];
    var lines = [
      'app ' + appId() + ' · Chrome ' + chrome() + ' · window ' + window.innerWidth + 'x' + window.innerHeight +
        ' · dpr ' + window.devicePixelRatio + ' · 1rem=' + rem() + 'px · last key ' + lastKey,
      'list: row ' + (focusIndex + 1) + ' · ' + (state.list === 'translate' ? 'translateY ' + Math.round(-listOffset) : 'scrollTop ' + Math.round(list.scrollTop)) +
        ' · scroll events ' + listScrollEvents + ' · row inside list (DOM) ' + (inside(row, list) ? 'yes' : 'NO') +
        ' · focus on row ' + (document.activeElement === row ? 'yes' : 'NO'),
      'strip: cell ' + (cellIndex + 1) + ' · scrollLeft ' + Math.round(strip.scrollLeft) + (state.list === 'into-view' ? '' : ' · left ' + Math.round(stripLeft)) +
        ' · scroll events ' + stripScrollEvents + ' · cell inside (DOM) ' + (inside(cell, strip) ? 'yes' : 'NO'),
      'rtl scrollLeft model: ' + rtlModel + ' (old = starts at max and counts down, new = 0 and negative)',
      'frame ' + rect(frame) + ' · stage ' + rect(stage) + (full ? ' (full screen)' : '')
    ];
    videos.forEach(function (v, k) {
      var e = v.el;
      lines.push('video ' + (k + 1) + ': ready ' + e.readyState + ' net ' + e.networkState + (e.paused ? ' paused' : ' playing') +
        ' t ' + e.currentTime.toFixed(1) + ' ' + e.videoWidth + 'x' + e.videoHeight +
        ' · el ' + rect(e) + (sameRect(e, v.slot) ? ' = slot' : ' ≠ slot ' + rect(v.slot)) +
        (v.error ? ' · ' + v.error : ''));
    });
    return lines.join('\n');
  }

  function showModes() {
    modesEl.textContent = 'scale ' + state.scale + ' · list ' + state.list + ' · videos ' + state.videos +
      ' ' + state.placement + ' frame · ' + state.source;
  }

  var frames = 0;
  function tick() {
    frames++;
    if (frames % 6 === 0) {
      clock.textContent = String(frames);
      diag.textContent = report();
    }
    requestAnimationFrame(tick);
  }

  // ---- keys ----

  function cycle(list, value) {
    return list[(list.indexOf(value) + 1) % list.length];
  }

  function onKey(e) {
    var code = e.keyCode;
    lastKey = String(code);
    var handled = true;
    if (code === KEY.DOWN) focusRow(focusIndex + 1);
    else if (code === KEY.UP) focusRow(focusIndex - 1);
    else if (code === KEY.LEFT) focusCell(cellIndex + 1);
    else if (code === KEY.RIGHT) focusCell(cellIndex - 1);
    else if (code === KEY.OK) setFull(!full);
    else if (code === KEY.BACK || code === KEY.ESC) { if (full) setFull(false); }
    else if (code === KEY.RED || code === 82) setMode('scale', cycle(SCALE_MODES, state.scale));
    else if (code === KEY.GREEN || code === 71) setMode('list', cycle(LIST_MODES, state.list));
    else if (code === KEY.YELLOW || code === 89) setMode('videos', cycle([1, 2, 4], state.videos));
    else if (code === KEY.BLUE || code === 66) setMode('placement', state.placement === 'inside' ? 'outside' : 'inside');
    else if (code === 55) setMode('source', state.source === 'local' ? 'network' : 'local');
    else if (code === 48) location.reload();
    else handled = false;
    if (handled) e.preventDefault();
  }

  function setMode(name, value) {
    state[name] = value;
    save();
    if (name === 'scale') {
      applyScale();
      applyListMode(); // the strip's cell widths are in pixels of the new layout
      placeVideos();
    } else if (name === 'list') {
      applyListMode();
    } else {
      buildVideos();
    }
    showModes();
  }

  document.addEventListener('keydown', onKey);
  window.addEventListener('resize', placeVideos);

  // ---- for tools/cdp.mjs: what the DOM says, without anyone pressing a key ----

  function wait(ms) {
    return new Promise(function (resolve) { setTimeout(resolve, ms); });
  }

  /** Walks the list and the strip in every list mode and says what the DOM reports. */
  async function selfTest(steps) {
    steps = steps || 20;
    var saved = state.list;
    var out = [];
    for (var m = 0; m < LIST_MODES.length; m++) {
      setMode('list', LIST_MODES[m]);
      focusRow(0);
      focusCell(0);
      await wait(150);
      var before = listScrollEvents;
      var beforeStrip = stripScrollEvents;
      for (var s = 0; s < steps; s++) {
        focusRow(focusIndex + 1);
        focusCell(cellIndex + 1);
        await wait(40);
      }
      await wait(200);
      out.push({
        list: LIST_MODES[m],
        scrollTop: Math.round(list.scrollTop),
        translateY: Math.round(-listOffset),
        listScrollEvents: listScrollEvents - before,
        rowInside: inside(rows.children[focusIndex], list),
        scrollLeft: Math.round(strip.scrollLeft),
        stripScrollEvents: stripScrollEvents - beforeStrip,
        cellInside: inside(cells.children[cellIndex], strip)
      });
    }
    setMode('list', saved);
    return {
      app: appId(), chrome: chrome(), window: window.innerWidth + 'x' + window.innerHeight,
      scale: state.scale, rtl: rtlModel, modes: out,
      videos: videos.map(function (v) {
        return { ready: v.el.readyState, paused: v.el.paused, t: v.el.currentTime, error: v.error, onSlot: sameRect(v.el, v.slot) };
      })
    };
  }

  window.spike = {
    state: function () { return { state: state, report: report() }; },
    set: setMode,
    selfTest: selfTest
  };

  rtlModel = detectRtlModel();
  applyScale();
  showModes();
  applyListMode();
  buildVideos();
  requestAnimationFrame(tick);
})();
