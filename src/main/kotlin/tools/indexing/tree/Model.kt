package tools.indexing.tree

import tools.indexing.DeclarationHolder
import uppaal.model.Nta
import uppaal.model.UppaalPojo

class Model(val nta: Nta) : DeclarationHolder(), TreeNode {
    override val uppaalPojo: UppaalPojo = nta
}