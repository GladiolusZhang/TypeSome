package com.shuyu.voiceime

import android.content.Context
import com.shuyu.voiceime.data.Contacts
import com.shuyu.voiceime.pinyin.Dictionary
import com.shuyu.voiceime.pinyin.EnglishWords
import java.util.concurrent.Executors

/** Process-wide singletons shared by the keyboard service and the settings screen. */
object Core {
    @Volatile private var dict: Dictionary? = null
    val bg = Executors.newSingleThreadExecutor()

    fun dictionary(ctx: Context): Dictionary {
        dict?.let { return it }
        synchronized(this) {
            dict?.let { return it }
            val d = Dictionary(ctx.applicationContext)
            dict = d
            bg.execute {
                d.load()
                if (Prefs(ctx).contactsEnabled) reloadContacts(ctx)
            }
            return d
        }
    }

    @Volatile private var en: EnglishWords? = null

    fun english(ctx: Context): EnglishWords {
        en?.let { return it }
        synchronized(this) {
            en?.let { return it }
            val e = EnglishWords(ctx.applicationContext)
            en = e
            bg.execute { e.load() }
            return e
        }
    }

    fun reloadContacts(ctx: Context) {
        val d = dictionary(ctx)
        bg.execute {
            val map = if (Prefs(ctx).contactsEnabled) Contacts.load(ctx) else emptyMap()
            if (d.loaded) d.setContacts(map.keys)
        }
    }
}
