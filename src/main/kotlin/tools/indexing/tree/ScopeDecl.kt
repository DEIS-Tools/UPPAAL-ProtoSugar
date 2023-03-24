package tools.indexing.tree

import tools.indexing.DeclarationBase
import uppaal.model.UppaalPojo

// TODO: How to know how scopes relate to a model? How to index/search?
//  Cannot be done with a UppaalPath. What if we have the fields/scope of a function, partial instantiation, or edge?
//  It must also handle that the "system" scope is below the "declaration" scope when a "system" and "declarations"
//   have the same parent. (Or could you instead add "in-code start-index" values to declarations to know which order they were
//   made in?)

open class ScopeDecl(identifier: String, val element: UppaalPojo) : DeclarationBase(identifier) {
    @Suppress("LeakingThis")
    constructor(identifier: String, element: UppaalPojo, parent: DeclarationBase) : this(identifier, element) {
        parent.add(this)
    }
}