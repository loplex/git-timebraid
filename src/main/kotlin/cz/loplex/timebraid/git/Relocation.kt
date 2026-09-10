package cz.loplex.timebraid.git

/**
 * What a refusal about where an input lands tells the user to do with the input at a destination.
 *
 * The refusals are raised while trees are assembled, far from the command line that knows how the
 * input was written; this is how they say it as the command line does, spelled from the argument
 * the user gave.
 */
fun interface Relocation {

    /** The remedy for the input at [destination], as a clause naming it: `give the repository at …`. */
    fun remedy(destination: String): String

    companion object {
        /** For a caller with no command line behind it: the argument's form, not its spelling. */
        val UNSPELLED = Relocation { destination ->
            "give the repository at '$destination' another subdirectory, with ::<subdir> after its location"
        }
    }
}
