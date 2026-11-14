/* Mera Office – điều khiển đọc: cài đặt, cuộn, trượt trang, lật sách (cong trang theo ngón tay). */
(function () {
  'use strict';
  var S = window.S || {};
  var KIND = window.KIND || '';
  var B = document.body;
  var DE = document.documentElement;
  var DEF = { theme: 'light', font: 'sans', fs: 18, lh: 'm', mg: 'm', spread: 'auto' };
  var THEMES = ['light', 'sepia', 'green', 'dark'];
  var PAGED = KIND === 'pdf' || KIND === 'docx' || KIND === 'pptx';

  function vw() { return DE.clientWidth; }
  function vh() { return DE.clientHeight; }
  function clamp(v, a, b) { return v < a ? a : (v > b ? b : v); }
  function get(k) { return S[k] !== undefined && S[k] !== null && S[k] !== '' ? S[k] : DEF[k]; }
  function save(k, v) {
    S[k] = v;
    try { if (window.Reader && Reader.save) Reader.save(k, String(v)); } catch (e) { }
  }
  function zoomed() { return !!(window.visualViewport && window.visualViewport.scale > 1.05); }

  // ---------- Giao diện: màu nền, phông, cỡ chữ ----------
  function applyLook() {
    THEMES.forEach(function (t) { B.classList.remove('t-' + t); });
    B.classList.add('t-' + get('theme'));
    ['sans', 'serif', 'light'].forEach(function (f) { B.classList.remove('f-' + f); });
    B.classList.add('f-' + get('font'));
    var st = DE.style;
    st.setProperty('--fs', get('fs') + 'px');
    st.setProperty('--lh', { s: '1.4', m: '1.6', l: '1.85' }[get('lh')] || '1.6');
    st.setProperty('--mg', { s: '14px', m: '24px', l: '40px' }[get('mg')] || '24px');
    var deck = document.querySelector('.deck');
    if (deck) st.setProperty('--r', deck.style.getPropertyValue('--r') || '1.333');
  }

  var mode = PAGED ? (S['mode_' + KIND] || (KIND === 'pptx' ? 'slide' : 'scroll')) : 'scroll';
  if (['scroll', 'slide', 'curl'].indexOf(mode) < 0) mode = 'scroll';

  function applySpread() {
    if (KIND !== 'pdf') return;
    var sp = get('spread');
    var two = sp === '2' || (sp !== '1' && vw() >= 700 && vw() > vh() * 0.8);
    B.classList.toggle('two', two && mode === 'scroll');
  }

  // ---------- Bộ lật trang ----------
  var view = null, A = null, U = null, F = null, FW = null, SH = null;
  var idx = 0, count = 1, anim = null, srcDoc = null, slides = [];

  function pageCount() {
    if (KIND === 'pdf') return Math.max(1, document.querySelectorAll('.pages .pg').length);
    if (KIND === 'pptx') return Math.max(1, slides.length);
    if (KIND === 'docx') {
      var d = A && A.querySelector('.doc');
      if (!d) return 1;
      return Math.max(1, Math.ceil((d.scrollWidth - 2) / vw()));
    }
    return 1;
  }

  function mkHolder(extra) {
    var h = document.createElement('div');
    h.className = 'pgh';
    var pc = document.createElement('div');
    pc.className = 'pc';
    h.appendChild(pc);
    if (KIND === 'docx') {
      pc.appendChild(srcDoc.cloneNode(true));
      var pn = document.createElement('div');
      pn.className = 'pno';
      h.appendChild(pn);
    } else if (KIND === 'pdf') {
      var im = document.createElement('img');
      im.className = 'pimg';
      im.alt = '';
      pc.appendChild(im);
    }
    if (extra) {
      var fp = document.createElement('div'); fp.className = 'fpaper'; h.appendChild(fp);
      var fb = document.createElement('div'); fb.className = 'fback'; h.appendChild(fb);
    }
    h._i = -1;
    return h;
  }

  function setPage(h, i) {
    var pc = h.firstChild;
    if (KIND === 'docx') {
      pc.firstChild.style.transform = 'translate3d(' + (-i * vw()) + 'px,0,0)';
      var pn = h.querySelector('.pno');
      if (pn) pn.textContent = (i + 1) + ' / ' + count;
      h._i = i;
      return;
    }
    if (h._i === i) return;
    h._i = i;
    if (KIND === 'pdf') {
      var s = '/pdf/' + i + '.jpg';
      if (pc.firstChild.getAttribute('src') !== s) pc.firstChild.setAttribute('src', s);
    } else if (KIND === 'pptx') {
      pc.innerHTML = '';
      if (slides[i]) {
        var fr = document.createElement('div');
        fr.className = 'sframe';
        fr.appendChild(slides[i].cloneNode(true));
        pc.appendChild(fr);
      }
    }
  }

  var pre = {};
  function preload() {
    if (KIND !== 'pdf') return;
    for (var k = idx - 1; k <= idx + 2; k++) {
      if (k >= 0 && k < count && !pre[k]) { var im = new Image(); im.src = '/pdf/' + k + '.jpg'; pre[k] = im; }
    }
    for (var key in pre) { if (Math.abs(key - idx) > 6) delete pre[key]; }
  }

  function vis(el, on) { el.style.visibility = on ? 'visible' : 'hidden'; }

  function enterPaged(pos) {
    B.classList.add('paged');
    window.scrollTo(0, 0);
    if (KIND === 'docx') srcDoc = document.querySelector('.book .doc');
    slides = [].slice.call(document.querySelectorAll('.deck > .slide'));
    view = document.createElement('div');
    view.id = 'view';
    U = mkHolder(false);
    A = mkHolder(false);
    SH = document.createElement('div'); SH.id = 'shu';
    FW = document.createElement('div'); FW.id = 'flapw';
    F = mkHolder(true);
    FW.appendChild(F);
    view.appendChild(U); view.appendChild(SH); view.appendChild(A); view.appendChild(FW);
    B.appendChild(view);
    count = pageCount();
    idx = pos.i >= 0 ? pos.i : Math.round(pos.f * (count - 1));
    idx = clamp(idx, 0, count - 1);
    flat();
  }

  function exitPaged() {
    stopAnim();
    if (view && view.parentNode) view.parentNode.removeChild(view);
    view = A = U = F = FW = SH = null;
    B.classList.remove('paged');
  }

  function resetStyles(h) { h.style.transform = ''; h.style.clipPath = ''; }

  function flat() {
    if (!view) return;
    setPage(A, idx);
    resetStyles(A); resetStyles(U);
    vis(A, true); vis(U, false); vis(FW, false); vis(SH, false);
    preload();
  }

  /* Trang a đang lật sang a+1. t: 0 = chưa lật, 1 = lật xong. gy: độ cao điểm cầm, dy: lệch dọc của ngón tay. */
  function renderTurn(a, t, gy, dy) {
    var w = vw(), h = vh();
    setPage(A, a); setPage(U, a + 1);
    vis(A, true); vis(U, true);
    if (mode === 'slide') {
      vis(FW, false); vis(SH, false);
      A.style.clipPath = ''; U.style.clipPath = '';
      A.style.transform = 'translate3d(' + (-t * w) + 'px,0,0)';
      U.style.transform = 'translate3d(' + ((1 - t) * w) + 'px,0,0)';
      return;
    }
    resetStyles(U); A.style.transform = '';
    if (t <= 0.0005) { A.style.clipPath = ''; vis(U, false); vis(FW, false); vis(SH, false); return; }
    if (t >= 0.9995) { vis(A, false); vis(FW, false); vis(SH, false); return; }
    setPage(F, a);
    vis(FW, true); vis(SH, true);
    // Điểm cầm G ở mép phải được kéo tới T; nếp gấp là trung trực của GT.
    var gx = w, tx = w - 2 * t * w, ty = gy + dy;
    var nx = gx - tx, ny = gy - ty, L = Math.sqrt(nx * nx + ny * ny);
    if (L < 0.5) { A.style.clipPath = ''; vis(FW, false); vis(SH, false); return; }
    nx /= L; ny /= L;
    var px = (gx + tx) / 2, py = (gy + ty) / 2;
    A.style.clipPath = poly(clipHalf(w, h, px, py, nx, ny, false));
    F.style.clipPath = poly(clipHalf(w, h, px, py, nx, ny, true));
    var d = px * nx + py * ny;
    F.style.transform = 'matrix(' + (1 - 2 * nx * nx) + ',' + (-2 * nx * ny) + ',' + (-2 * nx * ny) + ',' +
      (1 - 2 * ny * ny) + ',' + (2 * d * nx) + ',' + (2 * d * ny) + ')';
    // Đổ bóng: mặt sau trang (theo hệ toạ độ trước khi gấp) và bóng trên trang dưới.
    var ang = Math.atan2(nx, -ny);
    var deg = ang * 180 / Math.PI;
    var gl = Math.abs(w * Math.sin(ang)) + Math.abs(h * Math.cos(ang));
    var s0 = (px - w / 2) * nx + (py - h / 2) * ny + gl / 2;
    var depth = L / 2;
    F.lastChild.style.background = 'linear-gradient(' + deg + 'deg,rgba(0,0,0,0) ' + (s0 - 1) + 'px,rgba(0,0,0,.22) ' + s0 +
      'px,rgba(0,0,0,.05) ' + (s0 + depth * 0.2) + 'px,rgba(255,255,255,.12) ' + (s0 + depth * 0.5) + 'px,rgba(0,0,0,.12) ' +
      (s0 + depth) + 'px)';
    var sw = Math.min(70, 18 + depth * 0.12);
    SH.style.background = 'linear-gradient(' + deg + 'deg,rgba(0,0,0,0) ' + (s0 - 1) + 'px,rgba(0,0,0,' +
      (0.38 * (1 - t) + 0.08).toFixed(3) + ') ' + s0 + 'px,rgba(0,0,0,0) ' + (s0 + sw) + 'px)';
  }

  function clipHalf(w, h, px, py, nx, ny, positive) {
    var pts = [[0, 0], [w, 0], [w, h], [0, h]], out = [];
    function side(p) { var v = (p[0] - px) * nx + (p[1] - py) * ny; return positive ? v : -v; }
    for (var i = 0; i < 4; i++) {
      var a = pts[i], b = pts[(i + 1) % 4], sa = side(a), sb = side(b);
      if (sa >= 0) out.push(a);
      if ((sa >= 0) !== (sb >= 0)) {
        var k = sa / (sa - sb);
        out.push([a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k]);
      }
    }
    return out;
  }

  function poly(p) {
    if (p.length < 3) return 'polygon(0 0,0 0,0 0)';
    return 'polygon(' + p.map(function (q) { return q[0].toFixed(1) + 'px ' + q[1].toFixed(1) + 'px'; }).join(',') + ')';
  }

  function stopAnim() { if (anim) { cancelAnimationFrame(anim.raf); anim = null; } }
  function finishAnim() { if (!anim) return; cancelAnimationFrame(anim.raf); var d = anim.done; anim = null; if (d) d(); }

  function animate(a, t0, t1, gy, dy0, dy1, dur, done) {
    stopAnim();
    var start = performance.now();
    anim = { done: done, raf: 0 };
    function step(now) {
      if (!anim) return;
      var k = clamp((now - start) / dur, 0, 1), e = 1 - Math.pow(1 - k, 3);
      renderTurn(a, t0 + (t1 - t0) * e, gy, dy0 + (dy1 - dy0) * e);
      if (k < 1) anim.raf = requestAnimationFrame(step);
      else { var d = anim.done; anim = null; if (d) d(); }
    }
    anim.raf = requestAnimationFrame(step);
  }

  function turnBy(dir) {
    if (!view) return;
    finishAnim();
    var h = vh(), dur = mode === 'curl' ? 560 : 300, a;
    if (dir > 0) {
      if (idx >= count - 1) { showInd(); return; }
      a = idx;
      animate(a, 0, 1, h * 0.82, -h * 0.1, 0, dur, function () { idx = a + 1; flat(); showInd(); });
    } else {
      if (idx <= 0) { showInd(); return; }
      a = idx - 1;
      animate(a, 1, 0, h * 0.82, 0, -h * 0.1, dur, function () { idx = a; flat(); showInd(); });
    }
  }
  window.nextPage = function () { turnBy(1); };
  window.prevPage = function () { turnBy(-1); };

  var indTimer = null;
  function showInd() {
    if (!view) return;
    var el = document.getElementById('ind');
    if (!el) { el = document.createElement('div'); el.id = 'ind'; B.appendChild(el); }
    el.textContent = (idx + 1) + ' / ' + count;
    el.style.opacity = '1';
    clearTimeout(indTimer);
    indTimer = setTimeout(function () { el.style.opacity = '0'; }, 1300);
  }

  function relayout() {
    applySpread();
    if (!view) return;
    stopAnim();
    var f = count > 1 ? idx / (count - 1) : 0;
    count = pageCount();
    idx = KIND === 'docx' ? clamp(Math.round(f * (count - 1)), 0, count - 1) : clamp(idx, 0, count - 1);
    flat();
  }
  window.addEventListener('resize', function () { setTimeout(relayout, 30); });
  window.addEventListener('load', function () { if (view && KIND === 'docx') relayout(); });

  // ---------- Vị trí đọc khi đổi kiểu xem ----------
  function scrollEls() {
    return KIND === 'pdf' ? document.querySelectorAll('.pages .pg') : document.querySelectorAll('.deck > .slide');
  }
  function currentPos() {
    if (view) return { i: KIND === 'docx' ? -1 : idx, f: count > 1 ? idx / (count - 1) : 0 };
    if (KIND === 'pdf' || KIND === 'pptx') {
      var els = scrollEls();
      for (var k = 0; k < els.length; k++) { if (els[k].getBoundingClientRect().bottom > vh() * 0.3) return { i: k, f: 0 }; }
      return { i: 0, f: 0 };
    }
    var m = Math.max(1, DE.scrollHeight - vh());
    return { i: -1, f: window.scrollY / m };
  }
  function restorePos(p) {
    if (KIND === 'pdf' || KIND === 'pptx') {
      var els = scrollEls();
      if (p.i >= 0 && els[p.i]) els[p.i].scrollIntoView();
    } else {
      window.scrollTo(0, p.f * Math.max(0, DE.scrollHeight - vh()));
    }
  }

  function setMode(m) {
    if (!PAGED || m === mode) return;
    var pos = currentPos();
    if (view) exitPaged();
    mode = m;
    save('mode_' + KIND, m);
    applySpread();
    if (m === 'scroll') { requestAnimationFrame(function () { restorePos(pos); }); }
    else enterPaged(pos);
  }

  // ---------- Cử chỉ ----------
  var t0 = null, drag = null, lastX = 0, lastT = 0, vx = 0;

  document.addEventListener('touchstart', function (e) {
    if (!view || sheetOpen) { t0 = null; return; }
    if (e.touches.length !== 1 || zoomed()) { if (drag) release(true); t0 = null; return; }
    if (anim) finishAnim();
    var p = e.touches[0];
    t0 = { x: p.clientX, y: p.clientY };
    drag = null; lastX = p.clientX; lastT = performance.now(); vx = 0;
  }, { passive: true });

  document.addEventListener('touchmove', function (e) {
    if (!t0 || !view) return;
    if (e.touches.length !== 1) { if (drag) release(true); t0 = null; return; }
    var p = e.touches[0], dx = p.clientX - t0.x, dy = p.clientY - t0.y;
    var now = performance.now(), dt = Math.max(1, now - lastT);
    vx = 0.6 * vx + 0.4 * ((p.clientX - lastX) / dt);
    lastX = p.clientX; lastT = now;
    if (!drag) {
      if (Math.abs(dx) < 10 || Math.abs(dx) < Math.abs(dy)) return;
      var dir = dx < 0 ? 1 : -1;
      if ((dir > 0 && idx >= count - 1) || (dir < 0 && idx <= 0)) { showInd(); t0 = null; return; }
      drag = { dir: dir, a: dir > 0 ? idx : idx - 1, gy: t0.y, t: dir > 0 ? 0 : 1, dy: 0 };
    }
    var w = vw();
    drag.t = drag.dir > 0 ? clamp(-dx / w, 0, 1) : clamp(1 - dx / w, 0, 1);
    drag.dy = clamp(dy * 0.5, -vh() * 0.2, vh() * 0.2);
    renderTurn(drag.a, drag.t, drag.gy, drag.dy);
  }, { passive: true });

  function release(cancel) {
    var d = drag; drag = null;
    if (!d) return;
    var v = vx, done;
    if (cancel) done = false;
    else if (d.dir > 0) done = v < -0.3 || (d.t > 0.4 && v < 0.25);
    else done = v > 0.3 || (d.t < 0.6 && v > -0.25);
    var target = d.dir > 0 ? (done ? 1 : 0) : (done ? 0 : 1);
    var flick = Math.abs(v) > 0.3;
    var dur = clamp(Math.abs(target - d.t) * (flick ? 260 : 480) + 60, 90, 560);
    animate(d.a, d.t, target, d.gy, d.dy, 0, dur, function () {
      if (done) idx = d.dir > 0 ? d.a + 1 : d.a;
      flat();
      if (done) showInd();
    });
  }
  document.addEventListener('touchend', function () { if (drag) release(false); t0 = null; }, { passive: true });
  document.addEventListener('touchcancel', function () { if (drag) release(true); t0 = null; }, { passive: true });

  document.addEventListener('click', function (e) {
    var tg = e.target;
    if (tg && tg.closest && (tg.closest('.tabs') || tg.closest('#sheet') || tg.closest('#backdrop'))) return;
    if (sheetOpen) { closeSheet(); return; }
    if (view && !zoomed()) {
      var w = vw();
      if (e.clientX < w * 0.28) { turnBy(-1); return; }
      if (e.clientX > w * 0.72) { turnBy(1); return; }
    }
    if (window.Reader && Reader.tap) Reader.tap();
  });

  document.addEventListener('keydown', function (e) {
    if (!view) return;
    var k = e.key;
    if (k === 'ArrowRight' || k === 'ArrowDown' || k === 'PageDown' || k === ' ' || k === 'Enter') { turnBy(1); e.preventDefault(); }
    else if (k === 'ArrowLeft' || k === 'ArrowUp' || k === 'PageUp' || k === 'Backspace') { turnBy(-1); e.preventDefault(); }
  });

  if (window.visualViewport) {
    window.visualViewport.addEventListener('resize', function () { B.classList.toggle('zoomed', zoomed()); });
  }

  // ---------- Bảng cài đặt ----------
  var sheet = null, sheetOpen = false;

  function seg(k, opts) {
    return '<div class="seg">' + opts.map(function (o) {
      return '<button data-k="' + k + '" data-v="' + o[0] + '"' + (o[2] ? ' class="' + o[2] + '"' : '') + '>' + o[1] + '</button>';
    }).join('') + '</div>';
  }
  function row(label, ctl) { return '<div class="row"><div class="lbl">' + label + '</div>' + ctl + '</div>'; }

  function buildSheet() {
    sheet = document.createElement('div');
    sheet.id = 'sheet';
    var h = '<div class="grab"></div>';
    if (PAGED) h += row('Kiểu xem', seg('mode', [['scroll', 'Cuộn'], ['slide', 'Trượt'], ['curl', 'Lật sách']]));
    h += row('Màu nền', '<div class="sw">' + THEMES.map(function (t) {
      return '<button data-k="theme" data-v="' + t + '" class="c-' + t + '"></button>';
    }).join('') + '</div>');
    if (KIND === 'docx') {
      h += row('Phông chữ', seg('font', [['sans', 'Không chân'], ['serif', 'Có chân', 'f-serif'], ['light', 'Mảnh', 'f-light']]));
      h += row('Cỡ chữ', '<div class="stp"><button data-act="fs-">A−</button><span id="fsv"></span><button data-act="fs+">A+</button></div>');
      h += row('Giãn dòng', seg('lh', [['s', 'Hẹp'], ['m', 'Vừa'], ['l', 'Rộng']]));
      h += row('Lề trang', seg('mg', [['s', 'Hẹp'], ['m', 'Vừa'], ['l', 'Rộng']]));
    }
    if (KIND === 'pdf') h += row('Khi cuộn', seg('spread', [['1', '1 trang'], ['2', '2 trang'], ['auto', 'Tự động']]));
    sheet.innerHTML = h;
    sheet.addEventListener('click', onSheetClick);
    var bd = document.createElement('div');
    bd.id = 'backdrop';
    bd.addEventListener('click', function (e) { e.stopPropagation(); closeSheet(); });
    B.appendChild(bd);
    B.appendChild(sheet);
  }

  function refreshSheet() {
    if (!sheet) return;
    var bs = sheet.querySelectorAll('[data-k]');
    for (var i = 0; i < bs.length; i++) {
      var b = bs[i], k = b.getAttribute('data-k');
      var cur = k === 'mode' ? mode : get(k);
      b.classList.toggle('on', String(cur) === b.getAttribute('data-v'));
    }
    var fv = document.getElementById('fsv');
    if (fv) fv.textContent = get('fs');
  }

  function onSheetClick(e) {
    var b = e.target.closest ? e.target.closest('button') : null;
    e.stopPropagation();
    if (!b) return;
    var act = b.getAttribute('data-act');
    if (act) {
      save('fs', clamp((+get('fs')) + (act === 'fs+' ? 1 : -1), 12, 34));
      applyLook(); relayout();
    } else {
      var k = b.getAttribute('data-k'), v = b.getAttribute('data-v');
      if (k === 'mode') setMode(v);
      else {
        save(k, v); applyLook();
        if (k === 'spread') applySpread();
        if (k === 'font' || k === 'lh' || k === 'mg') relayout();
      }
    }
    refreshSheet();
  }

  function closeSheet() { B.classList.remove('sheet-open'); sheetOpen = false; }
  window.openSheet = function () {
    if (!sheet) buildSheet();
    refreshSheet();
    sheet.getBoundingClientRect();
    B.classList.add('sheet-open');
    sheetOpen = true;
  };
  window.closeSheet = closeSheet;
  window.toggleSheet = function () { if (sheetOpen) closeSheet(); else window.openSheet(); };

  // Tab trang tính Excel
  window.showSheet = function (i) {
    var s = document.querySelectorAll('.sheet'), t = document.querySelectorAll('.tab');
    for (var k = 0; k < s.length; k++) s[k].style.display = (k === i) ? 'block' : 'none';
    for (var k2 = 0; k2 < t.length; k2++) t[k2].classList.toggle('on', k2 === i);
  };

  window.isPaged = function () { return !!view; };

  // ---------- Khởi động ----------
  applyLook();
  applySpread();
  if (mode !== 'scroll') {
    var go = function () { enterPaged({ i: 0, f: 0 }); };
    if (KIND === 'docx' && document.fonts && document.fonts.ready) document.fonts.ready.then(go); else go();
  }
})();
