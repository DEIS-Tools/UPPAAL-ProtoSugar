package tools.indexing

import tools.parsing.GuardedParseTree
import uppaal.model.TextUppaalPojo

interface Textual {
    val parseTree: GuardedParseTree
    val source: TextUppaalPojo

    fun getOriginalText() = source.content.substring(parseTree.fullRange)
}