package cz.loplex.timebraid.cli

/**
 * Where the program narrates what it is doing. Every line goes to stderr — stdout is reserved for
 * results (the plan summary), so `git-timebraid --dry-run … | diff` stays clean.
 */
class Progress(
    private val level: Level,
    private val sink: (String) -> Unit,
) {

    enum class Level { QUIET, NORMAL, VERBOSE }

    /** A milestone — cloning, reading, planning, writing. Printed unless `--quiet`. */
    fun step(message: String) {
        if (level != Level.QUIET) sink("git-timebraid: $message")
    }

    /** A detail — every git subprocess, mostly. Printed only with `--verbose`. */
    fun detail(message: String) {
        if (level == Level.VERBOSE) sink("  $message")
    }

    companion object {
        fun level(quiet: Boolean, verbose: Boolean): Level = when {
            quiet -> Level.QUIET
            verbose -> Level.VERBOSE
            else -> Level.NORMAL
        }
    }
}
