package tools.indexing.tree

import tools.indexing.DeclarationBase
import uppaal.model.UppaalPojo

open class ScopeDecl(identifier: String, val element: UppaalPojo) : DeclarationBase(identifier) {
    @Suppress("LeakingThis")
    constructor(identifier: String, element: UppaalPojo, parent: DeclarationBase) : this(identifier, element) {
        parent.add(this)
    }
}