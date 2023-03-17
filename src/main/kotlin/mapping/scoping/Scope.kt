package mapping.scoping

// TODO: How to know how scopes relate to a model? How to index/search?
//  Cannot be done with a UppaalPath. What if we have the fields/scope of a function, partial instantiation, or edge?
//  It must also handle that the "system" scope is below the "declaration" scope when a "system" and "declarations"
//   have the same parent. (Or could you instead add "in-code start-index" values to declarations to know which order they were
//   made in?)

open class Scope() : ScopeChildBase() {
    private val _subScopes = mutableListOf<Scope>()
    private val _declarations = mutableListOf<Declaration>()

    val subScopes: List<Scope> get() = _subScopes
    val declarations: List<Declaration> get() = _declarations


    @Suppress("LeakingThis")
    constructor(parent: Scope) : this() {
        parent.add(this)
    }


    fun add(scope: Scope): Boolean {
        assert(scope.parent != null) { "Tried to re-associate a Scope (INSERT IDENT)" } // TODO: Fix message
        if (scope in _subScopes)
            return false
        setThisAsParentOf(scope)
        return _subScopes.add(scope)
    }

    fun add(decl: Declaration): Boolean {
        assert(decl.parent != null) { "Tried to re-associate a Declaration (${decl.identifier})" }
        if (_declarations.any { it.identifier == decl.identifier })
            return false
        setThisAsParentOf(decl)
        return _declarations.add(decl)
    }

    // TODO: Get scope

    operator fun get(identifier: String): Declaration?
            = _declarations.find { it.identifier == identifier } ?: parent?.get(identifier)
}