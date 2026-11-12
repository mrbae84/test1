package vn.trinhdoc.app

/** Khung trang HTML chung cho cả 3 loại file. */
object Html {

    fun page(c: ReaderContent, theme: String): String {
        val css = BASE_CSS + when (c.kind) {
            "pdf" -> PDF_CSS
            "docx" -> DOCX_CSS
            else -> XLSX_CSS
        }
        val js = BASE_JS + when (c.kind) {
            "pdf" -> PDF_JS
            "xlsx" -> XLSX_JS
            else -> ""
        }
        return "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=8,user-scalable=yes\">" +
            "<style>" + css + "</style></head><body class=\"" + c.bodyClass + " " + theme + "\">" +
            c.body() + "<script>" + js + "</script></body></html>"
    }

    private const val BASE_CSS = """
:root{--bg:#ffffff;--fg:#1d1d1f;--muted:#8a8a8e;--line:#d4d4d8;--head:#f2f2f4;--accent:#1a56c4}
body.sepia{--bg:#f6efe1;--fg:#3b3226;--muted:#8b7d66;--line:#dccfb4;--head:#ece2cc;--accent:#9a5b13}
body.dark{--bg:#121212;--fg:#e6e6e6;--muted:#8d8d92;--line:#3a3a3c;--head:#1e1e20;--accent:#8ab4f8}
html,body{margin:0;padding:0;background:var(--bg);color:var(--fg)}
body{font-family:sans-serif;-webkit-text-size-adjust:100%;-webkit-tap-highlight-color:transparent}
.empty{color:var(--muted);text-align:center;padding:40px 20px;font-size:16px}
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

    private const val BASE_JS = """
function setTheme(t){var b=document.body.classList;b.remove('light','sepia','dark');b.add(t);}
document.addEventListener('click',function(e){
  if(e.target&&e.target.closest&&e.target.closest('.tabs'))return;
  if(window.Reader)Reader.tap();
});
"""

    private const val PDF_JS = """
var forced=null;
function applySpread(){
  var d=document.documentElement,w=d.clientWidth,h=d.clientHeight;
  var two=forced!==null?forced:(w>=700&&w>h*0.8);
  document.body.classList.toggle('two',two);
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
