package cz.loplex.timebraid.git

/*
 * Option values as the command line parses them, for tests that call the reader directly: each
 * helper is the parse MergeCommand makes of one option, against the inputs' names in order.
 */

/** `--ref` [values], over [repos]. */
internal fun refPatterns(repos: List<SourceRepository>, values: List<String>): ScopedPatterns {
    val names = repos.map { it.name }
    val parsed = ScopedPatterns.parse(values, "--ref", names, destinations = true)
    return ScopedPatterns(parsed, "--ref", names, emptyMeans = true)
}

/** `--interleave-ref` [values], over [repos]. */
internal fun interleavePatterns(repos: List<SourceRepository>, values: List<String>): ScopedPatterns {
    val names = repos.map { it.name }
    val option = "--interleave-ref"
    return ScopedPatterns(ScopedPatterns.parse(values, option, names), option, names, emptyMeans = false)
}

/** `--mainline-branch` [values], over [repos]. */
internal fun mainlines(repos: List<SourceRepository>, values: List<String>): MainlineRequest =
    MainlineRequest.parse(values, repos.map { it.name })
