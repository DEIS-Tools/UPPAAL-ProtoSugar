package tools.indexing

// TODO: How to know how scopes relate to a model? How to index/search?
//  Cannot be done with a UppaalPath. What if we have the fields/scope of a function, partial instantiation, or edge?

abstract class DeclarationBase(val identifier: String?) {
    private val _subElements = mutableListOf<DeclarationBase>() // Order of elements may affect visibility

    val subElements: List<DeclarationBase> get() = _subElements
    var parent: DeclarationBase? = null
        private set

    fun add(element: DeclarationBase): Boolean {
        assert(element.parent != null) { "Tried to re-associate a Scope ${element.identifier}" }
        if (element in _subElements)
            return false
        element.parent = this
        return _subElements.add(element)
    }


    // TODO: May require different resolve-strategies
    /** Based on the current scope/declaration, find the declaration that "first" matches the given identifier **/
    fun <T> resolve(identifier: String): DeclarationBase? {
        return null
    }

    /** Find an identifier based on "something" **/
    fun <T> find(/* Something */): DeclarationBase? {
        return null
    }
}