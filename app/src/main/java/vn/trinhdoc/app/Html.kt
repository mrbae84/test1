package vn.trinhdoc.app

/**
 * Khung trang HTML chung cho cả 3 loại file.
 * PDF và Word có 2 chế độ: cuộn dọc, và trình chiếu (lật từng trang như PowerPoint).
 */
object Html {

    fun page(c: ReaderContent, theme: String, slide: Boolean): String {
        val css = BASE_CSS + when (c.kind) {
            "pdf" -> PDF_CSS
            "docx" -> DOCX_CSS
            else -> XLSX_CSS
        }
        val js = BASE_JS + when (c.kind) {
            "pdf" -> SLIDE_JS + PDF_JS
            "docx" -> SLIDE_JS
            "xlsx" -> XLSX_JS
            else -> ""
        }
        val cls = c.bodyClass + " " + theme + if (slide) " slide" else ""
        return "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=8,user-scalable=yes\">" +
            "<style>" + css + "</style></head><body class=\"" + cls + "\">" +
            c.body() + "<script>" + js + "</script></body></html>"
    }

    private const val BASE_CSS = """
:root{--bg:#ffffff;--fg:#1d1d1f;--muted:#8a8a8e;--line:#d4d4d8;--head:#f2f2f4;--accent:#1a56c4}
body.sepia{--bg:#f6efe1;--fg:#3b3226;--muted:#8b7d66;--line:#dccfb4;--head:#ece2cc;--accent:#9a5b13}
body.dark{--bg:#121212;--fg:#e6e6e6;--muted:#8d8d92;--line:#3a3a3c;--head:#1e1e20;--accent:#8ab4f8}
html,body{margin:0;padding:0;background:var(--bg);color:var(--fg)}
body{font-family:sans-serif;-webkit-text-size-adjust:100%;-webkit-tap-highlight-color:transparent}
.empty{color:var(--muted);text-align:center;padding:40px 20px;font-size:16px}
body.slide{overflow:hidden;touch-action:pinch-zoom}
body.slide.zoomed{touch-action:auto}
#ind{position:fixed;left:50%;bottom:18px;transform:translateX(-50%);background:rgba(0,0,0,.62);color:#fff;
 font-size:14px;padding:5px 13px;border-radius:14px;opacity:0;transition:opacity .4s;pointer-events:none;z-index:9}
"""

    private const val PDF_CSS = """
body.pdf{background:#5a5a5e}
body.pdf.sepia{background:#cbbd9f}
body.pdf.dark{background:#0b0b0b}
.pages{padding:4px 0}
.pg{display:block;width:100%;height:auto;margin:0 0 6px;background:#fff}
body.two .pages{display:grid;grid-template-columns:1fr 1fr;gap:6px;align-items:start;padding:6px}
body.two .pg{margin:0}
body.dark .pg{filter:invert(.92) hue-rotate(180deg)}
body.sepia .pg{filter:sepia(.35) brightness(.98)}
body.slide.pdf{background:#000}
body.slide .pages,body.slide.two .pages{display:flex;flex-wrap:nowrap;position:fixed;top:0;left:0;
 width:100vw;height:100vh;overflow:hidden;padding:0;gap:0;background:#000}
body.slide .pg,body.slide.two .pg{flex:0 0 100vw;width:100vw;height:100vh;object-fit:contain;margin:0;background:transparent}
"""

    private const val DOCX_CSS = """
.doc{max-width:900px;margin:0 auto;padding:22px 18px 80px;font-size:17px;line-height:1.55;overflow-wrap:break-word}
.doc p,.doc h1,.doc h2,.doc h3,.doc h4,.doc h5,.doc h6{margin:0 0 .55em}
.doc h1{font-size:1.55em}.doc h2{font-size:1.32em}.doc h3{font-size:1.16em}
.doc h4,.doc h5,.doc h6{font-size:1.04em}
.doc .e{min-height:1em}
.doc .blk{display:block}
.doc table{border-collapse:collapse;margin:.6em 0;display:block;overflow-x:auto;max-width:100%}
.doc td{border:1px solid var(--line);padding:4px 7px;vertical-align:top;min-width:36px}
.doc td.vm{border-top-color:transparent}
.doc td p,.doc td h1,.doc td h2,.doc td h3{margin:0 0 .2em}
.doc img{max-width:100%;height:auto}
.doc .num{display:inline-block;min-width:1.3em;margin-right:.35em}
.doc .ph{color:var(--muted);font-style:italic}
body.dark .doc [style*="color:"]{color:inherit!important}
body.dark .doc td[style]{background:none!important}
body.dark .doc span[style*="background:"]{color:#111!important}
body.slide .book{position:fixed;top:0;left:0;width:100vw;height:100vh;overflow:hidden}
body.slide .doc{box-sizing:border-box;max-width:none;margin:0;width:100vw;height:100vh;padding:30px 22px 44px;
 column-width:calc(100vw - 44px);column-gap:44px;column-fill:auto}
body.slide .doc img{max-height:calc(100vh - 110px);width:auto!important;max-width:100%}
"""

    private const val XLSX_CSS = """
html,body{height:100%}
.sheet{position:absolute;top:0;left:0;right:0;bottom:0;overflow:auto;-webkit-overflow-scrolling:touch}
body.multi .sheet{bottom:46px}
table.xl{border-collapse:separate;border-spacing:0;font-size:14px}
.xl td,.xl th{border-right:1px solid var(--line);border-bottom:1px solid var(--line);padding:3px 6px;white-space:nowrap;vertical-align:bottom}
.xl thead th{position:sticky;top:0;background:var(--head);z-index:2;font-weight:500;color:var(--muted);text-align:center}
.xl tbody th{position:sticky;left:0;background:var(--head);z-index:1;color:var(--muted);font-weight:400;text-align:center;min-width:34px}
.xl thead th.c0{left:0;z-index:3}
.tabs{position:fixed;left:0;right:0;bottom:0;height:46px;display:flex;overflow-x:auto;background:var(--head);border-top:1px solid var(--line)}
.tab{border:0;border-top:3px solid transparent;background:none;color:var(--fg);padding:0 16px;font-size:15px;white-space:nowrap}
.tab.on{border-top-color:var(--accent);font-weight:600}
body.dark .xl td[style*="background:"]{background:none!important}
"""

    // Chạm giữa màn hình: hiện/ẩn thanh công cụ. Khi trình chiếu: chạm 30% trái/phải để lật trang.
    private const val BASE_JS = """
function setTheme(t){var b=document.body.classList;b.remove('light','sepia','dark');b.add(t);}
document.addEventListener('click',function(e){
  if(e.target&&e.target.closest&&e.target.closest('.tabs'))return;
  if(typeof isSlide==='function'&&isSlide()&&!zoomed()){
    var w=document.documentElement.clientWidth;
    if(e.clientX<w*0.3){prevPage();return;}
    if(e.clientX>w*0.7){nextPage();return;}
  }
  if(window.Reader)Reader.tap();
});
"""

    private const val SLIDE_JS = """
var idx=0,lastFrac=0,indTimer=null;
function vw(){return document.documentElement.clientWidth;}
function vh(){return document.documentElement.clientHeight;}
function isPdf(){return document.body.classList.contains('pdf');}
function isSlide(){return document.body.classList.contains('slide');}
function isTwo(){return isPdf()&&!isSlide()&&document.body.classList.contains('two');}
function zoomed(){return !!(window.visualViewport&&window.visualViewport.scale>1.05);}
function pager(){return document.querySelector(isPdf()?'.pages':'.book');}
function stepN(){return isTwo()?2:1;}
function pageW(){return isTwo()?vw()/2:vw();}
function total(){
  if(isPdf())return document.querySelectorAll('.pg').length||1;
  var b=pager();return b?Math.max(1,Math.ceil(b.scrollWidth/vw()-0.02)):1;
}
function preload(){
  if(!isPdf())return;
  var im=document.querySelectorAll('.pg');
  for(var k=idx-2;k<=idx+4;k++){if(k>=0&&k<im.length)im[k].loading='eager';}
}
function showInd(){
  var el=document.getElementById('ind');
  if(!el){el=document.createElement('div');el.id='ind';document.body.appendChild(el);}
  var n=total(),a=idx+1,b=Math.min(idx+stepN(),n);
  el.textContent=(b>a?(a+'-'+b):a)+' / '+n;
  el.style.opacity='1';
  clearTimeout(indTimer);indTimer=setTimeout(function(){el.style.opacity='0';},1500);
}
function goTo(i,smooth){
  var n=total(),s=stepN();
  i=Math.max(0,Math.min(i,n-1));i=i-(i%s);idx=i;lastFrac=n>1?i/(n-1):0;
  var b=pager();if(b)b.scrollTo({left:i*pageW(),top:0,behavior:smooth?'smooth':'auto'});
  preload();showInd();
}
function nextPage(){if(isSlide())goTo(idx+stepN(),true);}
function prevPage(){if(isSlide())goTo(idx-stepN(),true);}
function scrollPos(){
  if(isPdf()){
    var im=document.querySelectorAll('.pg');
    for(var k=0;k<im.length;k++){if(im[k].getBoundingClientRect().bottom>vh()*0.3)return {p:k,f:0};}
    return {p:0,f:0};
  }
  var d=document.documentElement,m=Math.max(1,d.scrollHeight-vh());
  return {p:-1,f:window.scrollY/m};
}
function setMode(m){
  var slide=(m==='slide');
  if(slide===isSlide())return;
  if(slide){
    var pos=scrollPos();
    document.body.classList.add('slide');window.scrollTo(0,0);
    var n=total();goTo(pos.p>=0?pos.p:Math.round(pos.f*(n-1)),false);
  }else{
    var n2=total(),f=n2>1?idx/(n2-1):0,p=idx;
    document.body.classList.remove('slide');
    if(isPdf()){var im=document.querySelectorAll('.pg');if(im[p])im[p].scrollIntoView();}
    else{var d=document.documentElement;window.scrollTo(0,f*Math.max(0,d.scrollHeight-vh()));}
  }
}
var sx=0,sy=0,multi=false;
document.addEventListener('touchstart',function(e){
  if(e.touches.length>1){multi=true;return;}
  sx=e.touches[0].clientX;sy=e.touches[0].clientY;
},{passive:true});
document.addEventListener('touchend',function(e){
  if(multi){if(e.touches.length===0)multi=false;return;}
  if(!isSlide()||zoomed()||e.changedTouches.length!==1)return;
  var dx=e.changedTouches[0].clientX-sx,dy=e.changedTouches[0].clientY-sy;
  if(Math.abs(dx)>50&&Math.abs(dx)>Math.abs(dy)){if(dx<0)nextPage();else prevPage();}
  else if(Math.abs(dy)>70&&Math.abs(dy)>Math.abs(dx)){if(dy<0)nextPage();else prevPage();}
},{passive:true});
document.addEventListener('keydown',function(e){
  if(!isSlide())return;var k=e.key;
  if(k==='ArrowRight'||k==='ArrowDown'||k==='PageDown'||k===' '||k==='Enter'){nextPage();e.preventDefault();}
  else if(k==='ArrowLeft'||k==='ArrowUp'||k==='PageUp'||k==='Backspace'){prevPage();e.preventDefault();}
});
window.addEventListener('resize',function(){if(isSlide())goTo(Math.round(lastFrac*(total()-1)),false);});
window.addEventListener('load',function(){if(isSlide())goTo(idx,false);});
if(window.visualViewport){window.visualViewport.addEventListener('resize',function(){document.body.classList.toggle('zoomed',zoomed());});}
if(isSlide())goTo(0,false);
"""

    private const val PDF_JS = """
var forced=null;
function applySpread(){
  var w=vw(),h=vh();
  var two=forced!==null?forced:(w>=700&&w>h*0.8);
  document.body.classList.toggle('two',two);
  if(isSlide())goTo(idx,false);
}
function toggleSpread(){forced=!document.body.classList.contains('two');applySpread();}
window.addEventListener('resize',applySpread);applySpread();
"""

    private const val XLSX_JS = """
function showSheet(i){
  var s=document.querySelectorAll('.sheet'),t=document.querySelectorAll('.tab');
  for(var k=0;k<s.length;k++){s[k].style.display=(k==i)?'block':'none';}
  for(var k=0;k<t.length;k++){t[k].classList.toggle('on',k==i);}
}
"""
}
