package com.dji.fccgpsoff

/**
 * UI language of the whole app, chosen by the user rather than by the device
 * locale — the RC ships with its own system language and the two need not agree.
 *
 * Deliberately not Android string resources + `values-ru/`: the entire UI is
 * built in code, the About page shows both languages at once, and the language
 * must be switchable inside the app independently of the system setting. A
 * per-locale resource split expresses none of that.
 *
 * Reads [AppState.uiRu], which is loaded before any UI is built and persisted on
 * change. Screens re-read it on `recreate()`.
 */
fun t(ru: String, en: String): String = if (AppState.uiRu) ru else en
