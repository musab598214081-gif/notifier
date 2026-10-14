package app.family.notifyer

/* The direct build: no in-app updater in this repository, nothing for the
   page to call. (The Play build's, through Google Play, is in src/play.) */
class Updater(@Suppress("UNUSED_PARAMETER") a: MainActivity) {
    fun cleanUp() {}
    fun onResume() {}
    fun callEnded() {}
    fun release() {}
}
