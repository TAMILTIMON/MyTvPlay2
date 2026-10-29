package com.mytv.player;

import java.util.Locale;

/**
 * Scripts injected into browser pages:
 *  • keep playing in the background / screen off (pages are told they stay visible, like Brave does)
 *  • tell the app when a video / song plays or pauses (for the notification, lock screen and the EQ button)
 *  • equalizer on the page's own sound (Web Audio: preamp + 10 bands + tone + balance + limiter)
 */
public final class BrowserMedia {
    private BrowserMedia() {
    }

    /** Runs on every page (safe to run more than once). */
    public static String js(boolean background) {
        return "(function(){if(window.__zh)return;window.__zh=1;"
                + (background
                ? "try{Object.defineProperty(document,'hidden',{get:function(){return false}});"
                + "Object.defineProperty(document,'visibilityState',{get:function(){return 'visible'}});"
                + "Object.defineProperty(document,'webkitHidden',{get:function(){return false}});"
                + "Object.defineProperty(document,'webkitVisibilityState',{get:function(){return 'visible'}});}catch(e){}"
                + "['visibilitychange','webkitvisibilitychange','pagehide','freeze'].forEach(function(t){"
                + "window.addEventListener(t,function(e){e.stopImmediatePropagation();},true);"
                + "document.addEventListener(t,function(e){e.stopImmediatePropagation();},true);});"
                : "")
                // report play / pause of any video or song on the page
                + "function rep(){try{var m=[].slice.call(document.querySelectorAll('video,audio'));"
                + "var p=m.filter(function(x){return !x.paused&&!x.ended&&x.readyState>2;});"
                + "var v=p.length?p[0]:(m.length?m[0]:null);"
                + "ZHMedia.state(p.length>0,document.title||'',v?(v.tagName=='VIDEO'):false,m.length>0,"
                + "v?(v.currentTime||0):0,v?(isFinite(v.duration)?v.duration:0):0);"
                // the song's title / artist / cover as the site publishes them (YouTube does)
                + "var md=navigator.mediaSession&&navigator.mediaSession.metadata;if(md&&ZHMedia.meta){var aw=md.artwork||[];"
                + "var best=aw.length?aw[aw.length-1].src:'';ZHMedia.meta(md.title||'',md.artist||'',best||'');}"
                + "}catch(e){}}"
                + "['play','playing','pause','ended','loadedmetadata','emptied'].forEach(function(t){"
                + "document.addEventListener(t,function(e){rep();if(t=='play'||t=='playing')try{window.__zhEqAttach&&window.__zhEqAttach(e.target);}catch(x){}},true);});"
                + "setInterval(rep,2000);rep();"
                + "window.__zhCmd=function(c){var m=[].slice.call(document.querySelectorAll('video,audio'));"
                + "var v=m.filter(function(x){return !x.paused;})[0]||m[0];if(!v)return;"
                + "if(c=='toggle'){if(v.paused)v.play();else v.pause();}else if(c=='play')v.play();else if(c=='pause')v.pause();"
                + "else if(c=='fwd')v.currentTime=Math.min(v.duration||1e9,v.currentTime+10);else if(c=='back')v.currentTime=Math.max(0,v.currentTime-10);"
                + "else if(c.indexOf('seek:')==0)v.currentTime=Math.max(0,parseFloat(c.substring(5))/1000);"
                + "setTimeout(rep,300);};"
                + EQ
                + "})();";
    }

    /** Web Audio equalizer; settings arrive through window.__zhEqSet(cfg). */
    private static final String EQ =
            "var F=[31,62,125,250,500,1000,2000,4000,8000,16000];var CFG=null;var CH=[];"
            + "function lin(db){return Math.pow(10,db/20);}"
            + "function canUse(v){try{var s=v.currentSrc||v.src||'';if(!s)return true;if(s.indexOf('blob:')==0||s.indexOf('data:')==0)return true;"
            + "var u=new URL(s,location.href);return u.origin==location.origin||v.crossOrigin!=null;}catch(e){return false;}}"
            + "function build(v){if(v.__zhEq)return v.__zhEq;var C=window.AudioContext||window.webkitAudioContext;if(!C)return null;"
            + "if(!window.__zhCtx)window.__zhCtx=new C();var ctx=window.__zhCtx;"
            + "var src=ctx.createMediaElementSource(v);var pre=ctx.createGain();var bands=F.map(function(f){var b=ctx.createBiquadFilter();"
            + "b.type='peaking';b.frequency.value=f;b.Q.value=1.4;b.gain.value=0;return b;});"
            + "var pan=ctx.createStereoPanner?ctx.createStereoPanner():null;var lim=ctx.createDynamicsCompressor();"
            + "lim.threshold.value=-1;lim.knee.value=0;lim.ratio.value=20;lim.attack.value=0.003;lim.release.value=0.25;"
            + "var by=ctx.createGain();"
            + "var n={ctx:ctx,src:src,pre:pre,bands:bands,pan:pan,lim:lim,by:by};v.__zhEq=n;CH.push(n);wire(n);return n;}"
            + "function wire(n){try{n.src.disconnect();n.pre.disconnect();n.bands.forEach(function(b){b.disconnect();});"
            + "if(n.pan)n.pan.disconnect();n.lim.disconnect();}catch(e){}"
            + "if(!CFG||!CFG.on){n.src.connect(n.ctx.destination);return;}"
            + "var x=n.src;x.connect(n.pre);x=n.pre;n.bands.forEach(function(b){x.connect(b);x=b;});"
            + "if(n.pan){x.connect(n.pan);x=n.pan;}if(CFG.lim){x.connect(n.lim);x=n.lim;}x.connect(n.ctx.destination);}"
            + "function set(n){if(!CFG)return;n.pre.gain.value=lin(CFG.pre);"
            + "for(var i=0;i<10;i++)n.bands[i].gain.value=CFG.b[i];"
            + "if(n.pan)n.pan.pan.value=Math.max(-1,Math.min(1,CFG.bal/100));n.lim.threshold.value=CFG.th;wire(n);}"
            + "window.__zhEqAttach=function(v){if(!CFG||!CFG.on||!v||v.__zhEq)return;"
            + "if(!canUse(v)){try{ZHMedia.eq('blocked');}catch(e){}return;}"
            + "try{var n=build(v);if(n){set(n);if(n.ctx.state=='suspended')n.ctx.resume();ZHMedia.eq('ok');}}catch(e){try{ZHMedia.eq('blocked');}catch(x){}}};"
            + "window.__zhEqSet=function(c){CFG=c;CH.forEach(set);"
            + "if(c.on)[].slice.call(document.querySelectorAll('video,audio')).forEach(function(v){if(!v.paused)window.__zhEqAttach(v);});};";

    /** Current equalizer settings (shared with the music player) as a JS call. */
    public static String eqCall() {
        int[] eff = MusicFx.effective();
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < 10; i++) b.append(i > 0 ? "," : "").append(String.format(Locale.US, "%.2f", eff[i] / 100f));
        b.append("]");
        return String.format(Locale.US, "window.__zhEqSet&&window.__zhEqSet({on:%b,pre:%.2f,b:%s,bal:%d,lim:%b,th:%.1f});",
                MusicFx.on() && Store.getBool("b_eq", true), MusicFx.preamp() / 100f, b, Store.getInt("mfx_balance", 0),
                MusicFx.limiterOn(), MusicFx.limiterTh() / 10f);
    }
}
