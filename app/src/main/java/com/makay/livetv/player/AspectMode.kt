package com.makay.livetv.player

import com.makay.livetv.R

/**
 * Goruntu modu (en-boy orani).
 *
 * Oneemli kisitlama: `android.media.tv.TvView`'un API'sinde **en-boy orani
 * yoktur** — sistem LiveTV bunu dogrudan DTVKit baglayicisi uzerinden yapar
 * (uc taraf uygulamalarda erisilemez). Burada uygulanabilir tek tasinabilir
 * yol kullaniliyor: `TvView` penceresi ekrana **sigdirilir**.
 *
 *  - [FULL_16_9]  : TvView tum ekran (varsayilan).
 *  - [PILLAR_4_3] : TvView 3:4 orana kisitlanir ve ortalanir → 16:9 ekranda
 *                   iki yanda dikey siyah seritler (pillarbox).
 *
 * Deger SharedPreferences'a [id] uzerinden yazilir.
 */
enum class AspectMode(val id: String, val labelRes: Int) {
    /** Tam ekran 16:9 (saydiz). */
    FULL_16_9("16:9", R.string.aspect_full),

    /** 4:3 dikey seritlerle ortalanmis goruntu. */
    PILLAR_4_3("4:3", R.string.aspect_pillar),
    ;

    companion object {
        fun fromId(id: String?): AspectMode =
            entries.firstOrNull { it.id == id } ?: FULL_16_9
    }
}
