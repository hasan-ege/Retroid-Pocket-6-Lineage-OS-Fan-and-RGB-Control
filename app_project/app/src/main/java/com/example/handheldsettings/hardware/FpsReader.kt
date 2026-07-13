package com.example.handheldsettings.hardware

import com.topjohnwu.superuser.Shell

/**
 * Reads frame rate for the foreground app via SurfaceFlinger TimeStats using libsu.
 */
object FpsReader {
    private var timestatsEnabled = false

    fun start() {
        if (!timestatsEnabled) {
            Shell.cmd("dumpsys SurfaceFlinger --timestats -enable", "dumpsys SurfaceFlinger --timestats -clear").submit()
            timestatsEnabled = true
        }
    }

    fun stop() {
        if (timestatsEnabled) {
            Shell.cmd("dumpsys SurfaceFlinger --timestats -disable", "dumpsys SurfaceFlinger --timestats -clear").submit()
            timestatsEnabled = false
        }
    }

    fun readFps(): Float {
        if (!timestatsEnabled) {
            start()
            return 0f
        }
        val script = """
            dumpsys SurfaceFlinger --timestats -dump -maxlayers 16 2>/dev/null | awk '
              function commitLayer(){ if((lf+0)>(blf+0) && lname !~ /= none/){ blf=lf; blaf=laf } }
              BEGIN{ g=1 }
              /layerName =/ { commitLayer(); g=0; lf=0; laf=0; lname=${'$'}0 }
              g==1 && ${'$'}1=="totalFrames" { gf=${'$'}3 }
              g==1 && ${'$'}1=="displayRefreshRate" { rr=${'$'}3 }
              g==1 && /presentToPresent histogram/ { gh=1; next }
              g==1 && gh==1 { gh=0; for(i=1;i<=NF;i++){ split(${'$'}i,a,"="); ms=a[1]+0; c=a[2]+0; if(c>0 && ms<100){ sc+=c; sm+=ms*c; if(ms>worst)worst=ms; } } }
              g==0 && ${'$'}1=="totalFrames" { lf=${'$'}3 }
              g==0 && ${'$'}1=="averageFPS" { laf=${'$'}3 }
              END{
                commitLayer();
                fps=(sm>0)?(1000.0*sc/sm):0;
                if(rr>0 && fps>rr) fps=rr;
                printf "%.1f\n", (fps>0)?fps:blaf
              }
            '
            dumpsys SurfaceFlinger --timestats -clear >/dev/null 2>&1
        """.trimIndent()

        val lines = Shell.cmd(script).exec().out
        val fpsStr = lines.firstOrNull()?.trim()
        val fps = fpsStr?.toFloatOrNull() ?: 0f
        return if (fps in 1f..240f) fps else 0f
    }
}
