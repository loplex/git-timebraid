package cz.loplex.timebraid.git

import org.eclipse.jgit.errors.ConfigInvalidException
import org.eclipse.jgit.lib.Config

/**
 * One input's `.gitmodules`, rewritten for the place that input's content takes in the output.
 *
 * The sections are held apart rather than as one blob of text because a section is the unit this
 * class works in: [SubmoduleWiring.rewire] prefixes a name and a path one section at a time, and
 * [SubmoduleWiring.merge] joins them without a second parse, which is only valid because a
 * `[submodule "…"]` section is self-contained.
 */
class RewiredGitmodules(val sections: List<Section>) {

    /** One `[submodule "…"]` section, already in the output's coordinates. */
    class Section(
        /** The section's subsection name, prefixed for the output. */
        val name: String,
        /** The section on its own, as config text. */
        val text: String,
    )

    /** The sections as one config text. */
    val text: String get() = sections.joinToString("") { it.text }
}

/**
 * Rebuilds the `.gitmodules` of the inputs into the one the output needs.
 *
 * `.gitmodules` is the only file in a repository whose *location* is part of its meaning: git reads
 * it from the repository root and nowhere else. Every other blob can therefore be carried into the
 * output by reference, inside its input's subdirectory, but this one cannot — moved to
 * `<subdir>/.gitmodules` it becomes text nothing reads, and the `path` it records no longer says
 * where the gitlink it describes actually sits. So the output's copy is synthesized instead: one
 * root-level file per commit, merging the `.gitmodules` of every input that has content at that
 * point in the braid, with each `path` rewritten to where that input's content landed.
 *
 * The gitlink entries themselves need no help — they ride along in their input's tree like any other
 * entry, and the commit they name is fetched from the submodule's own url, not from this repository.
 *
 * A section's *name* is prefixed as well, not just its path. The name is what git uses to store a
 * populated submodule under `.git/modules/`, so two inputs whose submodules happen to share a name
 * would otherwise be handed the same directory. Where no destination contains another, which the
 * planner enforces, `<subdir>/<name>` is unique by construction: `libs/a` with a section `b/x`
 * cannot collide with `libs/a/b` and its section `x`, because the pair never arrives. `--splice`
 * lets it arrive, and so does the repository at the output root, which every destination is inside
 * of, and a blank name, which [prefixed] leaves as it is, meets another blank one wherever it
 * stands; in each the uniqueness is not a property of the destinations but a refusal in [merge],
 * which names the submodule and the commit carrying it.
 */
object SubmoduleWiring {

    private const val SUBMODULE = "submodule"
    private const val PATH = "path"

    /**
     * What an input that describes no submodule at this commit contributes.
     *
     * Having a value for that case rather than a `null` is what lets the writer cache the answer for
     * every tree it looks at, including the overwhelmingly common one where there is nothing to find.
     */
    val NOTHING = RewiredGitmodules(emptyList())

    /**
     * [text] as it should appear in the output, given that the input's content lands in [subdir] —
     * possibly a nested path (`null` for the `--root-repo`, whose paths are already the output's).
     *
     * [repo] and [at] only name the input and the commit in an error message.
     */
    fun rewire(text: String, subdir: String?, repo: String, at: () -> String): RewiredGitmodules {
        val input = Config()
        try {
            input.fromText(text)
        } catch (e: ConfigInvalidException) {
            throw IllegalArgumentException(
                "the .gitmodules of '$repo' at ${at()} is not valid git config, so the " +
                    "output's submodule wiring cannot be rebuilt from it: ${e.message}",
                e,
            )
        }

        val sections = ArrayList<RewiredGitmodules.Section>()
        for (name in input.getSubsections(SUBMODULE)) {
            val outputName = prefixed(name, subdir)
            val output = Config()
            for (key in input.getNames(SUBMODULE, name)) {
                val values = input.getStringList(SUBMODULE, name, key).toList()
                val rewritten =
                    if (key.equals(PATH, ignoreCase = true)) values.map { prefixed(it, subdir) }
                    else values
                output.setStringList(SUBMODULE, outputName, key, rewritten)
            }
            sections += RewiredGitmodules.Section(outputName, output.toText())
        }
        return RewiredGitmodules(sections)
    }

    /**
     * The one file the output root gets, or `null` when [parts] describe no submodule at all — an
     * input may well carry a `.gitmodules` holding only comments, and an empty file at the root
     * would be a change to the tree for no reason.
     */
    fun merge(parts: List<RewiredGitmodules>, at: () -> String): String? {
        val claimed = HashSet<String>()
        for (part in parts) {
            for (section in part.sections) {
                require(claimed.add(section.name)) {
                    // A blank name takes no prefix, so no subdirectory moves one off another.
                    if (section.name.isBlank()) {
                        "two inputs both describe a submodule with a blank name at ${at()}; " +
                            "no subdirectory parts them, only renaming that section in one input"
                    } else {
                        "two inputs both describe a submodule named '${section.name}' at ${at()}; " +
                            "give one of them another subdirectory with <repo>::=<subdir>"
                    }
                }
            }
        }

        val merged = parts.joinToString("") { it.text }
        return merged.ifEmpty { null }
    }

    /**
     * `<subdir>/<value>`, or [value] unchanged when there is no subdirectory.
     *
     * A blank value is left alone: a section with no usable `path` describes nothing git can find,
     * and turning it into `<subdir>/` would invent a path the input never had.
     *
     * Called on a section's `path`, which is what that reason is about, and on its *name*, where
     * the prefix is what keeps two inputs' `.git/modules/` directories apart — see this class's own
     * KDoc. A blank name is left unprefixed too: JGit reads `[submodule ""]` as a section like any
     * other, so two inputs that both carry one meet under that one name, and [merge] refuses them.
     */
    private fun prefixed(value: String, subdir: String?): String =
        if (subdir == null || value.isBlank()) value else "$subdir/$value"
}
