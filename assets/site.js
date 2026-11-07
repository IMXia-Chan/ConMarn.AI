(function(){
  var de = document.documentElement;
  de.classList.add('js');
  var reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  var bars = document.querySelectorAll('.fstep .bar');
  function lightBars(){
    bars.forEach(function(el, i){ setTimeout(function(){ el.classList.add('go'); }, 90 + i * 110); });
  }
  if (reduce) bars.forEach(function(el){ el.classList.add('go'); });

  /* 截图横向滑轨 */
  var rail = document.querySelector('.rail');
  if (rail) {
    var prev = document.querySelector('.rn[data-dir="-1"]');
    var next = document.querySelector('.rn[data-dir="1"]');
    var sync = function(){
      var max = rail.scrollWidth - rail.clientWidth - 2;
      if (prev) prev.disabled = rail.scrollLeft <= 2;
      if (next) next.disabled = rail.scrollLeft >= max;
    };
    var step = function(dir){
      var card = rail.querySelector('.shot');
      var w = card ? card.getBoundingClientRect().width + 24 : rail.clientWidth * 0.8;
      rail.scrollBy({ left: dir * w, behavior: reduce ? 'auto' : 'smooth' });
      setTimeout(sync, reduce ? 80 : 520);
    };
    if (prev) prev.addEventListener('click', function(){ step(-1); });
    if (next) next.addEventListener('click', function(){ step(1); });
    rail.addEventListener('scroll', sync, { passive: true });
    window.addEventListener('resize', sync);
    sync();
  }

  /* 入场 */
  var revealables = document.querySelectorAll('.rv');
  if ('IntersectionObserver' in window) {
    var io = new IntersectionObserver(function(entries){
      entries.forEach(function(e){
        if (!e.isIntersecting) return;
        e.target.classList.add('on');
        if (e.target.classList.contains('flow')) lightBars();
        io.unobserve(e.target);
      });
    }, { rootMargin: '0px 0px -10% 0px', threshold: 0.06 });
    revealables.forEach(function(el){ io.observe(el); });
  } else {
    revealables.forEach(function(el){ el.classList.add('on'); });
    lightBars();
  }

  /* 导航高亮 */
  var links = Array.prototype.slice.call(document.querySelectorAll('.nav-links a'));
  var targets = links.map(function(a){
    var id = a.getAttribute('href');
    return id && id.charAt(0) === '#' ? document.querySelector(id) : null;
  });
  if ('IntersectionObserver' in window && targets.some(Boolean)) {
    var spy = new IntersectionObserver(function(entries){
      entries.forEach(function(e){
        if (!e.isIntersecting) return;
        var i = targets.indexOf(e.target);
        if (i < 0) return;
        links.forEach(function(a, j){ a.classList.toggle('active', i === j); });
      });
    }, { rootMargin: '-45% 0px -50% 0px', threshold: 0 });
    targets.forEach(function(t){ if (t) spy.observe(t); });
  }
})();
