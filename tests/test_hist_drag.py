# check: long-press drag reorders history, a plain swipe/tap does not
import json,time,os
from pathlib import Path
from playwright.sync_api import sync_playwright
H=[{"id":str(i),"text":f"متن {i}","voice":"achird","vname":"v","ts":"2026/11/13 10:00","model":"x","file":""} for i in range(25)]
with sync_playwright() as p:
    b=p.chromium.launch(executable_path=os.environ.get("CHROMIUM", "/opt/chromium/chrome-linux64/chrome"),args=["--no-sandbox","--allow-file-access-from-files"])
    cx=b.new_context(viewport={"width":390,"height":800},has_touch=True,is_mobile=True)
    pg=cx.new_page(); errs=[]; pg.on("pageerror",lambda e:errs.append(str(e)))
    pg.goto((Path(__file__).resolve().parents[1]/"app/src/main/assets/index.html").as_uri()); pg.evaluate("h=>localStorage.setItem('history',JSON.stringify(h))",H); pg.reload()
    pg.wait_for_selector("#splash.hide",state="attached",timeout=15000); time.sleep(.6)  # splash overlay swallows touches
    pg.evaluate("window.__plays=0; window.playHistory=()=>{window.__plays++}; 0")  # trailing 0: Playwright calls a returned function
    cdp=cx.new_cdp_session(pg)
    def touch(t,x=None,y=None): cdp.send("Input.dispatchTouchEvent",{"type":t,"touchPoints":[] if x is None else [{"x":x,"y":y}]})
    ids=lambda: pg.evaluate("[...document.querySelectorAll('#histList .hitem')].map(d=>d._h.id)")
    saved=lambda: [h["id"] for h in json.loads(pg.evaluate("localStorage.getItem('history')"))]
    def box(i):
        pg.locator("#histList .hitem").nth(i).scroll_into_view_if_needed()
        r=pg.locator("#histList .hitem").nth(i).bounding_box(); c=pg.locator("#histList .hitem .hcol").nth(i).bounding_box()
        return c["x"]+c["width"]/2,r["y"]+r["height"]/2,r["height"]  # RTL: buttons sit on the left, aim at the text
    def drag(i,rows,hold=True):
        x,y,h=box(i); touch("touchStart",x,y)
        if hold: time.sleep(.5)
        step=(h+9)*rows/12
        for k in range(1,13): touch("touchMove",x,y+step*k); time.sleep(.02)
        touch("touchEnd"); time.sleep(.15)
    # 1) quick swipe without holding = scroll, order untouched
    drag(0,2,hold=False); assert ids()[:4]==["0","1","2","3"],ids()[:4]; print("plays after swipe",pg.evaluate("__plays"))
    # 2) hold + drag item 0 down two rows
    drag(0,2); assert ids()[:4]==["1","2","0","3"],ids()[:4]; assert saved()[:4]==["1","2","0","3"]
    print("plays after drag",pg.evaluate("__plays"))
    assert pg.evaluate("__plays")==0, "drag must not start playback"
    # 3) hold + drag upward
    drag(3,-3); assert ids()[:4]==["3","1","2","0"],ids()[:4]
    # 4) page 2: order written at the right offset, page 1 untouched
    pg.locator("#hpNext").tap(); time.sleep(.1); assert ids()[:3]==["10","11","12"],ids()[:3]
    drag(0,1); assert ids()[:3]==["11","10","12"],ids()[:3]
    s=saved(); assert s[:4]==["3","1","2","0"] and s[10:13]==["11","10","12"] and len(s)==25 and len(set(s))==25,s
    # 5) plain tap still plays, arrow button still moves
    n=pg.evaluate("__plays"); pg.locator("#histList .hitem .hcol").nth(4).tap(); time.sleep(.1); assert pg.evaluate("__plays")==n+1
    pg.locator("#histList .hitem").nth(0).locator(".hmv button").nth(1).tap(); time.sleep(.1); assert ids()[:2]==["10","11"],ids()[:2]
    # 5b) hold without moving, then lift: no reorder, no playback
    before=ids(); n=pg.evaluate("__plays"); x,y,h=box(2); touch("touchStart",x,y); time.sleep(.5)
    assert pg.locator(".hitem.drag").count()==1; touch("touchEnd"); time.sleep(.2)
    assert ids()==before and pg.evaluate("__plays")==n and pg.locator(".hitem.drag").count()==0
    # 5c) English UI (LTR) drags the same way
    pg.evaluate("document.getElementById('lbEn').click()"); time.sleep(.4); assert pg.evaluate("document.documentElement.dir")=="ltr"
    drag(0,1); assert ids()[:2]==["11","10"],ids()[:2]; drag(1,-1); assert ids()[:2]==["10","11"],ids()[:2]
    pg.evaluate("document.getElementById('lbFa').click()"); time.sleep(.4)
    # 6) survives reload; no leftover drag styling
    pg.reload(); assert saved()[10:12]==["10","11"]; assert pg.locator(".hitem.drag").count()==0
    assert not errs,errs
    print("ALL OK")
