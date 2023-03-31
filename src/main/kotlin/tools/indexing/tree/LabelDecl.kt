package tools.indexing.tree

import tools.indexing.DeclarationBase
import tools.indexing.DeclarationHolder
import tools.indexing.text.Textual
import tools.parsing.GuardedParseTree
import uppaal.model.Label
import uppaal.model.TextUppaalPojo
import uppaal.model.UppaalPojo

class LabelDecl(
    parent: DeclarationHolder,
    val element: Label,
    override val parseTree: GuardedParseTree,
) : DeclarationBase(null, parent), TreeNode, Textual {
    override val uppaalPojo: UppaalPojo = element
    override val source: TextUppaalPojo = element
}